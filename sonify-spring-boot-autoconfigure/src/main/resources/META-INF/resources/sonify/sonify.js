// JVM Sonify: connects to the stream, keeps the rolling state, and drives the canvas,
// the audio engine and clip recording.
(function () {
  'use strict';

  var MAX_TICKS = 400;
  var MAX_EVENTS = 300;
  var MAX_LOG = 20;
  var THREAD_DECAY = 0.82;
  var LEAK_COOLDOWN_MS = 4000;
  var PARKED_FLOOR_WINDOW = 300;

  var state = {
    ticks: [],
    gcEvents: [],
    threadRows: [],
    connected: false
  };

  var threads = new Map();
  var audio = window.SonifyAudio.create();
  var viz = null;
  var source = null;
  var backoffMs = 500;
  var lastLeakAt = 0;
  var recorder = null;
  var recordedChunks = [];
  var counters = { ticks: 0, gc: 0, contention: 0 };
  var parkedHistory = [];

  var el = {};

  function byId(id) {
    return document.getElementById(id);
  }

  function setState(name, label) {
    state.connected = name === 'live';
    el.state.className = 'pill pill--' + name;
    el.state.textContent = label;
  }

  function mb(bytes) {
    return bytes / 1048576;
  }

  function formatMb(bytes) {
    var value = mb(bytes);
    return value >= 1024 ? (value / 1024).toFixed(2) + ' GB' : value.toFixed(0) + ' MB';
  }

  function formatPercent(ratio) {
    return ratio === null || ratio === undefined ? '--' : Math.round(ratio * 100) + '%';
  }

  function formatRate(mbps) {
    if (mbps >= 1024) {
      return (mbps / 1024).toFixed(2) + ' GB/s';
    }
    return mbps.toFixed(mbps < 10 ? 1 : 0) + ' MB/s';
  }

  // ---- rolling state -------------------------------------------------------

  /**
   * How many threads are waiting on something, as opposed to idling.
   *
   * <p>An application at rest still has parked threads: pool workers with nothing to do park
   * exactly like a request waiting for a connection. What distinguishes trouble is the rise
   * above that resting level, so the floor is taken as the lowest parked count seen recently
   * and only the excess counts. Threads blocked in a socket read always count, since a thread
   * in a read is never merely idle.
   */
  function waitingLevel(tick) {
    // The instantaneous thread-state count when available, since it stays truthful when nothing
    // ever recovers; the event-based parked count is the fallback for older payloads.
    var parked = tick.waiting === undefined ? (tick.parked || 0) : tick.waiting;
    parkedHistory.push(parked);
    if (parkedHistory.length > PARKED_FLOOR_WINDOW) {
      parkedHistory.shift();
    }
    var floor = parkedHistory[0];
    for (var i = 1; i < parkedHistory.length; i++) {
      if (parkedHistory[i] < floor) {
        floor = parkedHistory[i];
      }
    }
    return (tick.ioWait || 0) + (tick.blocked || 0) + Math.max(0, parked - floor) * 0.8;
  }

  function onTick(tick) {
    counters.ticks++;
    tick.waitingLevel = waitingLevel(tick);
    state.ticks.push(tick);
    if (state.ticks.length > MAX_TICKS) {
      state.ticks.shift();
    }
    updateThreadRows(tick);
    audio.update(tick);
    renderStats(tick);
  }

  /**
   * Keeps a stable, slowly changing ordering of thread rows. A score that decays rather than
   * resetting each tick stops rows jumping around between frames, which would make the lane
   * unreadable while still following where the work actually is.
   */
  function updateThreadRows(tick) {
    threads.forEach(function (row) {
      row.score *= THREAD_DECAY;
      row.contended = false;
      row.waiting = false;
    });

    (tick.threadDetail || []).forEach(function (detail) {
      var row = threads.get(detail.name);
      if (!row) {
        row = { name: detail.name, score: 0, contended: false, waiting: false, lastSeen: 0 };
        threads.set(detail.name, row);
      }
      row.score += 1 + mb(detail.allocBytes) + detail.blocks * 40 + (detail.io || 0) * 25;
      row.contended = detail.blocks > 0;
      row.waiting = (detail.io || 0) > 0;
      row.lastSeen = tick.t;
    });

    threads.forEach(function (row, name) {
      if (row.score < 0.05 && tick.t - row.lastSeen > 5000) {
        threads.delete(name);
      }
    });

    state.threadRows = Array.from(threads.values()).sort(function (a, b) {
      if (a.contended !== b.contended) {
        return a.contended ? -1 : 1;
      }
      if (a.waiting !== b.waiting) {
        return a.waiting ? -1 : 1;
      }
      return b.score - a.score;
    });
  }

  function onGc(event) {
    counters.gc++;
    state.gcEvents.push(event);
    if (state.gcEvents.length > MAX_EVENTS) {
      state.gcEvents.shift();
    }
    audio.gc(event);

    var detail = event.pauseMs.toFixed(2) + 'ms';
    if (event.reclaimedBytes !== null && event.reclaimedBytes !== undefined) {
      detail += ', reclaimed ' + formatMb(event.reclaimedBytes);
    }
    log(event.pauseMs > 100 ? 'bad' : 'gc', event.name, detail + ' · ' + event.cause);
    detectLeak(event);
  }

  /**
   * A collection that reclaims almost nothing while the heap is already high is the signature
   * of a leak, and it is the one thing a pause-only sonification would miss entirely.
   */
  function detectLeak(event) {
    if (event.reclaimedBytes === null || event.heapUsedAfter === null) {
      return;
    }
    var latest = state.ticks[state.ticks.length - 1];
    var ceiling = latest && latest.heapMax > 0 ? latest.heapMax : (event.heapCommittedAfter || 0);
    if (!ceiling) {
      return;
    }
    var occupancy = event.heapUsedAfter / ceiling;
    var reclaimedShare = event.reclaimedBytes / Math.max(1, event.heapUsedAfter);
    if (occupancy > 0.6 && reclaimedShare < 0.15 && Date.now() - lastLeakAt > LEAK_COOLDOWN_MS) {
      lastLeakAt = Date.now();
      audio.leak(Math.min(1, occupancy));
      log('bad', 'heap not recovering',
        formatPercent(occupancy) + ' of heap live after a collection that reclaimed '
        + formatMb(event.reclaimedBytes));
    }
  }

  function onContention(event) {
    counters.contention++;
    audio.contention(event);
    log('contended', event.thread,
      'blocked ' + event.durationMs.toFixed(0) + 'ms on ' + event.monitor);
  }

  function log(kind, title, detail) {
    var row = document.createElement('div');
    row.className = 'log__row log__row--' + kind;
    var time = document.createElement('span');
    time.className = 'log__time';
    time.textContent = new Date().toLocaleTimeString();
    var name = document.createElement('span');
    name.className = 'log__title';
    name.textContent = title;
    var text = document.createElement('span');
    text.className = 'log__detail';
    text.textContent = detail;
    row.appendChild(time);
    row.appendChild(name);
    row.appendChild(text);
    el.log.insertBefore(row, el.log.firstChild);
    while (el.log.childElementCount > MAX_LOG) {
      el.log.removeChild(el.log.lastChild);
    }
  }

  function renderStats(tick) {
    var ceiling = tick.heapMax > 0 ? tick.heapMax : tick.heapCommitted;
    var occupancy = ceiling > 0 ? tick.heapUsed / ceiling : 0;
    // Just the used figure: "438 / 512 MB" wrapped the tile, and the next tile already
    // carries the percentage while the canvas shows the full scale.
    el.statHeap.textContent = formatMb(tick.heapUsed);
    el.statHeapPct.textContent = formatPercent(occupancy);
    el.statAlloc.textContent = formatRate(tick.allocMBps);
    el.statCpu.textContent = formatPercent(tick.cpu);
    el.statMachine.textContent = formatPercent(tick.machineCpu);
    el.statThreads.textContent = tick.threads;
    el.statContended.textContent = tick.contended;
    el.statContended.className = 'stat__value' + (tick.contended > 0 ? ' stat__value--bad' : '');
    var stuck = Math.round(tick.waitingLevel || 0);
    el.statIoWait.textContent = stuck;
    el.statIoWait.className = 'stat__value' + (stuck > 0 ? ' stat__value--waiting' : '');
    el.statParked.textContent = tick.parked;
  }

  // ---- stream --------------------------------------------------------------

  function connect() {
    setState('connecting', 'connecting');
    source = new EventSource('stream');

    source.addEventListener('open', function () {
      setState('live', 'live');
      backoffMs = 500;
    });

    source.addEventListener('tick', function (message) {
      onTick(JSON.parse(message.data));
    });
    source.addEventListener('gc', function (message) {
      onGc(JSON.parse(message.data));
    });
    source.addEventListener('contention', function (message) {
      onContention(JSON.parse(message.data));
    });

    source.addEventListener('error', function () {
      source.close();
      setState('down', 'disconnected · retrying');
      // EventSource retries transport errors itself but gives up on an HTTP error such as the
      // 503 returned when max-clients is reached, so reconnection is driven here.
      window.setTimeout(connect, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 10000);
    });
  }

  // ---- clip recording ------------------------------------------------------

  function startRecording() {
    var canvasStream = el.canvas.captureStream(30);
    var tracks = canvasStream.getVideoTracks();
    var audioStream = audio.stream();
    if (audioStream) {
      audioStream.getAudioTracks().forEach(function (track) {
        tracks.push(track);
      });
    }
    var combined = new MediaStream(tracks);

    recordedChunks = [];
    var options = {};
    ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].some(function (type) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported(type)) {
        options.mimeType = type;
        return true;
      }
      return false;
    });

    recorder = new MediaRecorder(combined, options);
    recorder.ondataavailable = function (event) {
      if (event.data && event.data.size > 0) {
        recordedChunks.push(event.data);
      }
    };
    recorder.onstop = function () {
      var blob = new Blob(recordedChunks, { type: recorder.mimeType || 'video/webm' });
      var url = URL.createObjectURL(blob);
      var link = document.createElement('a');
      link.href = url;
      link.download = 'jvm-sonify-' + new Date().toISOString().replace(/[:.]/g, '-') + '.webm';
      link.click();
      window.setTimeout(function () {
        URL.revokeObjectURL(url);
      }, 2000);
      log('gc', 'clip saved', (blob.size / 1048576).toFixed(1) + ' MB of picture and sound');
    };
    recorder.start();
    el.record.textContent = 'Stop recording';
    el.record.classList.add('button--recording');
    if (!audio.running()) {
      log('bad', 'recording without sound',
        'press Play sound first if you want audio in the clip');
    }
  }

  function stopRecording() {
    if (recorder && recorder.state !== 'inactive') {
      recorder.stop();
    }
    recorder = null;
    el.record.textContent = 'Record clip';
    el.record.classList.remove('button--recording');
  }

  // ---- wiring --------------------------------------------------------------

  function wireControls() {
    el.play.addEventListener('click', function () {
      if (audio.running()) {
        audio.stop();
        el.play.textContent = 'Play sound';
        el.play.classList.remove('button--active');
        return;
      }
      audio.start().then(function () {
        el.play.textContent = 'Sound on';
        el.play.classList.add('button--active');
      });
    });

    el.mute.addEventListener('click', function () {
      var muted = el.mute.classList.toggle('button--active');
      audio.setMuted(muted);
      el.mute.textContent = muted ? 'Unmute' : 'Mute';
    });

    el.volume.addEventListener('input', function () {
      audio.setVolume(Number(el.volume.value) / 100);
    });

    el.record.addEventListener('click', function () {
      if (recorder) {
        stopRecording();
      } else {
        startRecording();
      }
    });
  }

  function frame() {
    viz.draw();
    window.requestAnimationFrame(frame);
  }

  function init() {
    el = {
      state: byId('state'),
      canvas: byId('timeline'),
      log: byId('log'),
      play: byId('play'),
      mute: byId('mute'),
      volume: byId('volume'),
      record: byId('record'),
      statHeap: byId('stat-heap'),
      statHeapPct: byId('stat-heap-pct'),
      statAlloc: byId('stat-alloc'),
      statCpu: byId('stat-cpu'),
      statMachine: byId('stat-machine'),
      statThreads: byId('stat-threads'),
      statContended: byId('stat-contended'),
      statIoWait: byId('stat-iowait'),
      statParked: byId('stat-parked')
    };

    viz = window.SonifyViz.create(el.canvas, state);
    viz.resize();
    window.addEventListener('resize', viz.resize);
    // Light and dark mode use different line colours, so they are re-read on a theme change.
    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', viz.resize);
    }

    wireControls();
    connect();
    window.requestAnimationFrame(frame);

    // Exposed so the page can be inspected from a console or a test.
    window.sonify = {
      state: state,
      counters: counters,
      audio: audio,
      describeAudio: audio.describe
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
