// Web Audio engine for JVM Sonify.
//
// Modelled on a multi-parameter patient monitor: every vital owns one timbre in its own
// register, so an abnormal reading is identifiable with your eyes shut and the voices layer
// instead of masking each other. A healthy JVM should sound like a calm heartbeat with a
// melody over it; a struggling one should sound tense.
//
//   heap used %      continuous sine drone, 90-260Hz           low, a bed under everything
//   cpu load         tremolo rate and filter cutoff on drone   shapes the drone, no new voice
//   allocation rate  hi-hat tempo                              high and short
//   gc pause         kick, sine sweep 140->40Hz                sub-bass transient, the heartbeat
//   long gc          filtered noise burst                      broadband alarm
//   contention       detuned square from 330Hz, pitch by thread mid, deliberately dissonant
//   live threads     pentatonic notes                          mid-high, pleasant when healthy
//   threads waiting  breath pad, filtered noise                low-mid, swells as waits build
//   leak             slow dissonant swell                      a collection that reclaims nothing
//
// The drone ducks briefly whenever a collection or a contention event fires, so those punch
// through instead of competing with a tone that never stops.
//
// The breath pad is what makes a stalled application audible. A pool that has run dry, a slow
// third-party API or an event loop held by a blocking call all allocate nothing, collect nothing
// and contend over nothing: without a voice for waiting they are simply silent, which is the one
// thing a monitor must never be. It is noise rather than a tone so it cannot be confused with
// the heap drone, and it breathes faster as more threads pile up, like respiration on a bedside
// monitor.
window.SonifyAudio = (function () {
  'use strict';

  var PENTATONIC = [0, 3, 5, 7, 10];
  var ROOT_HZ = 220;
  var LONG_GC_MS = 100;

  // Registers are kept apart on purpose. The drone is a bed that everything else sits on top
  // of, so it lives low; contention and the melody own the midrange where the ear picks out
  // detail. An earlier version ran the drone from 180Hz to 700Hz, which put it straight on top
  // of both and masked them. Its floor stays near 90Hz because laptop speakers roll off below
  // that and a drone nobody can hear is not a reading.
  var DRONE_LOW_HZ = 90;
  var DRONE_HIGH_HZ = 260;
  var DRONE_GAIN = 0.10;
  var CONTENTION_BASE_HZ = 330;
  var DUCK_DEPTH = 0.45;

  // Waiting threads are counted against this before the pad reaches full depth.
  var BREATH_FULL_SCALE = 24;
  var BREATH_MAX_GAIN = 0.16;
  var SCHEDULE_AHEAD = 0.12;
  var SCHEDULER_MS = 25;

  function create() {
    var ctx = null;
    var master = null;
    var recordDestination = null;
    var noise = null;

    var drone = null;
    var droneGain = null;
    var droneFilter = null;
    var tremolo = null;
    var tremoloDepth = null;

    var hatBus = null;
    var voiceBus = null;

    var breathSource = null;
    var breathFilter = null;
    var breathGain = null;
    var breathRate = null;
    var breathDepth = null;

    var scheduler = null;
    var nextHatTime = 0;
    var nextNoteTime = 0;
    var threadPitch = new Map();
    var muted = false;
    var volume = 0.7;

    // Latest readings, written by the stream and read by the scheduler.
    var vitals = { heapRatio: 0, allocMBps: 0, cpu: 0, threads: 1, contended: 0, waiting: 0 };

    function running() {
      return ctx !== null && ctx.state === 'running';
    }

    function started() {
      return ctx !== null;
    }

    /** Web Audio needs a user gesture, so this is called from the Play button. */
    function start() {
      if (ctx) {
        return ctx.resume();
      }
      var Ctor = window.AudioContext || window.webkitAudioContext;
      ctx = new Ctor();

      master = ctx.createGain();
      master.gain.value = volume;

      // Without this a contention storm and a GC kick land together and clip into mush,
      // which matters most in a recorded clip.
      var compressor = ctx.createDynamicsCompressor();
      compressor.threshold.value = -18;
      compressor.knee.value = 24;
      compressor.ratio.value = 6;
      compressor.attack.value = 0.004;
      compressor.release.value = 0.2;

      master.connect(compressor);
      compressor.connect(ctx.destination);

      // A second tap so Record clip captures exactly what is being heard.
      recordDestination = ctx.createMediaStreamDestination();
      compressor.connect(recordDestination);

      hatBus = ctx.createGain();
      hatBus.gain.value = 0.18;
      hatBus.connect(master);

      voiceBus = ctx.createGain();
      voiceBus.gain.value = 0.5;
      voiceBus.connect(master);

      noise = buildNoiseBuffer(ctx);
      buildDrone();
      buildBreath();

      nextHatTime = ctx.currentTime;
      nextNoteTime = ctx.currentTime;
      scheduler = window.setInterval(tick, SCHEDULER_MS);
      return Promise.resolve();
    }

    function buildNoiseBuffer(context) {
      var buffer = context.createBuffer(1, context.sampleRate, context.sampleRate);
      var data = buffer.getChannelData(0);
      for (var i = 0; i < data.length; i++) {
        data[i] = Math.random() * 2 - 1;
      }
      return buffer;
    }

    /** The drone is the one continuous voice: pitch is heap, timbre is CPU. */
    function buildDrone() {
      drone = ctx.createOscillator();
      drone.type = 'sine';
      drone.frequency.value = 180;

      droneFilter = ctx.createBiquadFilter();
      droneFilter.type = 'lowpass';
      droneFilter.frequency.value = 900;
      droneFilter.Q.value = 1;

      droneGain = ctx.createGain();
      droneGain.gain.value = DRONE_GAIN;

      // Tremolo: a slow pulse when the JVM is calm, faster and deeper as CPU climbs.
      tremolo = ctx.createOscillator();
      tremolo.type = 'sine';
      tremolo.frequency.value = 0.6;
      tremoloDepth = ctx.createGain();
      tremoloDepth.gain.value = 0.015;
      tremolo.connect(tremoloDepth);
      tremoloDepth.connect(droneGain.gain);

      drone.connect(droneFilter);
      droneFilter.connect(droneGain);
      droneGain.connect(master);
      drone.start();
      tremolo.start();
    }

    /**
     * The sound of threads that are waiting rather than working.
     *
     * <p>Band-passed noise with a slow swell, so it reads as breathing rather than as a tone.
     * Silent when nothing is waiting, which is most of the time.
     */
    function buildBreath() {
      breathSource = ctx.createBufferSource();
      breathSource.buffer = noise;
      breathSource.loop = true;

      breathFilter = ctx.createBiquadFilter();
      breathFilter.type = 'bandpass';
      breathFilter.frequency.value = 520;
      breathFilter.Q.value = 0.9;

      breathGain = ctx.createGain();
      breathGain.gain.value = 0;

      // The swell itself: a slow oscillator opening and closing the filter.
      breathRate = ctx.createOscillator();
      breathRate.type = 'sine';
      breathRate.frequency.value = 0.22;
      breathDepth = ctx.createGain();
      breathDepth.gain.value = 260;
      breathRate.connect(breathDepth);
      breathDepth.connect(breathFilter.frequency);

      breathSource.connect(breathFilter);
      breathFilter.connect(breathGain);
      breathGain.connect(master);
      breathSource.start();
      breathRate.start();
    }

    /** Called on every tick so the continuous voices track the vitals. */
    function update(tick) {
      var max = tick.heapMax > 0 ? tick.heapMax : tick.heapCommitted;
      vitals.heapRatio = max > 0 ? Math.min(1, tick.heapUsed / max) : 0;
      vitals.allocMBps = tick.allocMBps;
      vitals.cpu = tick.cpu === null || tick.cpu === undefined ? 0 : tick.cpu;
      vitals.threads = tick.threads;
      vitals.contended = tick.contended;
      // Supplied by the page, which measures the rise above the resting parked level rather
      // than the absolute count, so an idle pool does not hum.
      vitals.waiting = tick.waitingLevel === undefined
          ? (tick.ioWait || 0)
          : tick.waitingLevel;

      if (!running()) {
        return;
      }
      var now = ctx.currentTime;
      var ramp = 0.12;

      // Heap drives pitch across about an octave and a half, low enough to stay out of the way.
      var hz = DRONE_LOW_HZ + vitals.heapRatio * (DRONE_HIGH_HZ - DRONE_LOW_HZ);
      drone.frequency.setTargetAtTime(hz, now, ramp);

      // CPU opens the filter and quickens the pulse, so load is audible as brightness.
      droneFilter.frequency.setTargetAtTime(600 + vitals.cpu * 3400, now, ramp);
      tremolo.frequency.setTargetAtTime(0.6 + vitals.cpu * 7, now, ramp);
      tremoloDepth.gain.setTargetAtTime(0.015 + vitals.cpu * 0.04, now, ramp);

      // More threads waiting means a louder pad breathing faster, the way respiration climbs.
      var load = Math.min(1, vitals.waiting / BREATH_FULL_SCALE);
      breathGain.gain.setTargetAtTime(load * BREATH_MAX_GAIN, now, 0.35);
      breathRate.frequency.setTargetAtTime(0.22 + load * 0.75, now, 0.35);
      breathFilter.frequency.setTargetAtTime(420 + load * 700, now, 0.35);
    }

    /** Lookahead scheduler for the rhythmic voices. */
    function tick() {
      if (!running()) {
        return;
      }
      var horizon = ctx.currentTime + SCHEDULE_AHEAD;

      // Allocation rate sets hi-hat tempo on a log scale: the measured range spans a flat
      // idle through tens of gigabytes a second, which no linear mapping can carry.
      var hatsPerSecond = 0;
      if (vitals.allocMBps > 0.5) {
        hatsPerSecond = Math.min(16, 0.5 + Math.log10(1 + vitals.allocMBps) * 3.2);
      }
      if (hatsPerSecond > 0) {
        var hatInterval = 1 / hatsPerSecond;
        if (nextHatTime < ctx.currentTime) {
          nextHatTime = ctx.currentTime;
        }
        while (nextHatTime < horizon) {
          hat(nextHatTime, Math.min(1, 0.25 + hatsPerSecond / 24));
          nextHatTime += hatInterval;
        }
      } else {
        nextHatTime = ctx.currentTime;
      }

      // A healthy JVM gets an occasional pentatonic note; contention suppresses the melody,
      // so the pleasant layer drops away exactly when things go wrong.
      if (vitals.contended === 0) {
        if (nextNoteTime < ctx.currentTime) {
          nextNoteTime = ctx.currentTime;
        }
        while (nextNoteTime < horizon) {
          var degree = PENTATONIC[Math.floor(Math.random() * PENTATONIC.length)];
          var octave = vitals.threads > 60 ? 2 : 1;
          note(nextNoteTime, ROOT_HZ * Math.pow(2, (degree + 12 * octave) / 12), 0.10, 0.45);
          nextNoteTime += 1.1 + Math.random() * 1.6;
        }
      } else {
        nextNoteTime = ctx.currentTime + 1.5;
      }
    }

    /**
     * Dips the drone for a moment so a transient is heard rather than competing with it.
     *
     * <p>The same trick a mixing engineer uses: the continuous bed gets out of the way of the
     * events that matter, then comes straight back.
     */
    function duck(when, depth, length) {
      if (!droneGain) {
        return;
      }
      var floor = Math.max(0.005, DRONE_GAIN * (1 - depth));
      droneGain.gain.cancelScheduledValues(when);
      droneGain.gain.setValueAtTime(droneGain.gain.value, when);
      droneGain.gain.exponentialRampToValueAtTime(floor, when + 0.02);
      droneGain.gain.exponentialRampToValueAtTime(DRONE_GAIN, when + length);
    }

    function hat(when, gain) {
      var source = ctx.createBufferSource();
      source.buffer = noise;
      source.playbackRate.value = 1.6;

      var highpass = ctx.createBiquadFilter();
      highpass.type = 'highpass';
      highpass.frequency.value = 7000;

      var envelope = ctx.createGain();
      envelope.gain.setValueAtTime(gain * 0.5, when);
      envelope.gain.exponentialRampToValueAtTime(0.0001, when + 0.035);

      source.connect(highpass);
      highpass.connect(envelope);
      envelope.connect(hatBus);
      source.start(when);
      source.stop(when + 0.05);
    }

    function note(when, hz, gain, length) {
      var osc = ctx.createOscillator();
      osc.type = 'triangle';
      osc.frequency.value = hz;

      var envelope = ctx.createGain();
      envelope.gain.setValueAtTime(0.0001, when);
      envelope.gain.exponentialRampToValueAtTime(gain, when + 0.02);
      envelope.gain.exponentialRampToValueAtTime(0.0001, when + length);

      osc.connect(envelope);
      envelope.connect(voiceBus);
      osc.start(when);
      osc.stop(when + length + 0.02);
    }

    /** A collection: the heartbeat. Longer pauses hit harder and ring longer. */
    function gc(event) {
      if (!running()) {
        return;
      }
      var when = ctx.currentTime + 0.01;
      var pause = Math.max(0.1, event.pauseMs);
      var weight = Math.min(1, pause / 60);
      var length = 0.10 + weight * 0.22;

      var osc = ctx.createOscillator();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(140, when);
      osc.frequency.exponentialRampToValueAtTime(40, when + length);

      var envelope = ctx.createGain();
      envelope.gain.setValueAtTime(0.0001, when);
      envelope.gain.exponentialRampToValueAtTime(0.35 + weight * 0.5, when + 0.012);
      envelope.gain.exponentialRampToValueAtTime(0.0001, when + length);

      osc.connect(envelope);
      envelope.connect(master);
      osc.start(when);
      osc.stop(when + length + 0.02);
      duck(when, DUCK_DEPTH * weight, length + 0.15);

      if (pause > LONG_GC_MS) {
        alarm(when, Math.min(1, pause / 400));
      }
    }

    /** The long-pause alarm: a broadband downward scrape that cuts through everything. */
    function alarm(when, weight) {
      var source = ctx.createBufferSource();
      source.buffer = noise;
      source.playbackRate.value = 0.8;

      var band = ctx.createBiquadFilter();
      band.type = 'bandpass';
      band.Q.value = 1.4;
      band.frequency.setValueAtTime(2600, when);
      band.frequency.exponentialRampToValueAtTime(180, when + 0.45);

      var envelope = ctx.createGain();
      envelope.gain.setValueAtTime(0.0001, when);
      envelope.gain.exponentialRampToValueAtTime(0.18 + weight * 0.3, when + 0.03);
      envelope.gain.exponentialRampToValueAtTime(0.0001, when + 0.5);

      source.connect(band);
      band.connect(envelope);
      envelope.connect(master);
      source.start(when);
      source.stop(when + 0.55);
    }

    /** Contention: detuned squares, pitched per thread so a storm becomes a cluster chord. */
    function contention(event) {
      if (!running()) {
        return;
      }
      if (!threadPitch.has(event.thread)) {
        threadPitch.set(event.thread, threadPitch.size);
      }
      var index = threadPitch.get(event.thread) % 12;
      var hz = CONTENTION_BASE_HZ * Math.pow(2, index / 12);
      var when = ctx.currentTime + 0.01;
      var weight = Math.min(1, event.durationMs / 200);
      var length = 0.16 + weight * 0.3;

      [0, 1].forEach(function (voice) {
        var osc = ctx.createOscillator();
        osc.type = 'square';
        // The detune is the point: two near-identical squares beat against each other.
        osc.frequency.value = hz * (voice === 0 ? 1 : 1.031);

        var lowpass = ctx.createBiquadFilter();
        lowpass.type = 'lowpass';
        lowpass.frequency.value = 2200;

        var envelope = ctx.createGain();
        envelope.gain.setValueAtTime(0.0001, when);
        envelope.gain.exponentialRampToValueAtTime(0.11 + weight * 0.12, when + 0.03);
        envelope.gain.exponentialRampToValueAtTime(0.0001, when + length);

        osc.connect(lowpass);
        lowpass.connect(envelope);
        envelope.connect(voiceBus);
        osc.start(when);
        osc.stop(when + length + 0.02);
      });
      duck(when, DUCK_DEPTH, length + 0.1);
    }

    /** A collection that reclaimed almost nothing while the heap is high: the leak sound. */
    function leak(severity) {
      if (!running()) {
        return;
      }
      var when = ctx.currentTime + 0.01;
      [1, 1.0594].forEach(function (ratio) {
        var osc = ctx.createOscillator();
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(70 * ratio, when);
        osc.frequency.linearRampToValueAtTime(96 * ratio, when + 1.1);

        var lowpass = ctx.createBiquadFilter();
        lowpass.type = 'lowpass';
        lowpass.frequency.value = 500;

        var envelope = ctx.createGain();
        envelope.gain.setValueAtTime(0.0001, when);
        envelope.gain.exponentialRampToValueAtTime(0.05 + severity * 0.1, when + 0.4);
        envelope.gain.exponentialRampToValueAtTime(0.0001, when + 1.2);

        osc.connect(lowpass);
        lowpass.connect(envelope);
        envelope.connect(master);
        osc.start(when);
        osc.stop(when + 1.25);
      });
    }

    function setMuted(value) {
      muted = value;
      if (master) {
        master.gain.setTargetAtTime(muted ? 0 : volume, ctx.currentTime, 0.05);
      }
    }

    function setVolume(value) {
      volume = value;
      if (master && !muted) {
        master.gain.setTargetAtTime(volume, ctx.currentTime, 0.05);
      }
    }

    function stream() {
      return recordDestination ? recordDestination.stream : null;
    }

    function stop() {
      if (scheduler) {
        window.clearInterval(scheduler);
        scheduler = null;
      }
      if (ctx) {
        ctx.close();
        ctx = null;
      }
    }

    /** Exposed so the page can report what the engine is actually doing. */
    function describe() {
      return {
        started: started(),
        running: running(),
        muted: muted,
        volume: volume,
        state: ctx ? ctx.state : 'none',
        sampleRate: ctx ? ctx.sampleRate : 0,
        heapRatio: vitals.heapRatio,
        droneHz: drone ? drone.frequency.value : 0,
        droneGain: droneGain ? droneGain.gain.value : 0,
        filterHz: droneFilter ? droneFilter.frequency.value : 0,
        tremoloHz: tremolo ? tremolo.frequency.value : 0,
        allocMBps: vitals.allocMBps,
        waiting: vitals.waiting,
        breathGain: breathGain ? breathGain.gain.value : 0,
        breathRateHz: breathRate ? breathRate.frequency.value : 0,
        threadVoices: threadPitch.size
      };
    }

    return {
      start: start,
      stop: stop,
      update: update,
      gc: gc,
      contention: contention,
      leak: leak,
      setMuted: setMuted,
      setVolume: setVolume,
      stream: stream,
      running: running,
      started: started,
      describe: describe
    };
  }

  return { create: create };
})();
