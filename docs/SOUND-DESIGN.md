# Sound design

The organising idea is a **multi-parameter patient monitor**. Each vital owns one timbre in its
own register, so an abnormal reading is identifiable by ear alone and the voices layer instead of
masking each other. A healthy JVM should sound like a calm heartbeat with a melody over it; a
struggling one should sound tense.

---

## The voices

| Vital | Source | Sound | Register |
| --- | --- | --- | --- |
| Heap used % | MXBean | continuous sine drone, 90→260 Hz | low, a bed under everything |
| CPU load | MXBean | tremolo rate and filter cutoff **on the drone** | shapes the drone, adds no voice |
| Threads stuck | MXBean + JFR | breath pad, filtered noise swelling | low-mid, the sound of waiting |
| Allocation rate | MXBean | hi-hat tempo, log-scaled | high and short |
| GC pause | JFR | kick, sine sweep 140→40 Hz | sub-bass transient, the heartbeat |
| Long GC (>100ms) | JFR | broadband noise burst sweeping down | alarm, cuts through everything |
| Lock contention | JFR | detuned square from 330 Hz, pitch per thread | mid, deliberately dissonant |
| Live threads | MXBean | occasional pentatonic notes | mid-high, pleasant when healthy |
| Heap not recovering | derived | slow dissonant swell | a collection that reclaimed nothing |
| Parked threads | JFR | **nothing** — drawn only | a parked thread is usually just idle |

Registers are kept apart on purpose: kick 40–140 Hz, drone 90–260, contention 330–660, melody
440–880, hi-hat above 7 kHz.

---

## Four decisions that matter more than the mapping

### The drone is a bed, not a lead

The first version ran the drone from 180 Hz to 700 Hz. That put the one *continuous* voice
directly on top of the contention squares (160–320 Hz) and the pentatonic notes (440–880 Hz), so
it masked precisely the events it was meant to sit beneath. The feedback was exact: *"the heap
tone is so high that I can't hear the thread changes."*

It now runs 90–260 Hz at lower gain. Its floor stays near 90 Hz because laptop speakers roll off
below that, and a drone nobody can hear is not a reading.

### The drone ducks

Whenever a collection or a contention event fires, the drone dips 45% for about 200ms and comes
straight back. This is the ordinary mixing answer to a continuous bed competing with transients,
and it is what actually makes thread activity audible rather than merely present.

### Waiting needs a voice, or a stalled application is silent

A pool that has run dry, a slow third-party API or an event loop held by a blocking call allocate
nothing, collect nothing and contend over nothing. Without a voice for waiting they produce no
sound at all — and silence is the one thing a monitor must never produce for a system in
trouble.

The breath pad is band-passed noise with a slow swell, so it reads as breathing rather than as a
tone and cannot be confused with the drone. It breathes faster as more threads pile up.

It responds to the **rise above the resting level**, not the absolute count. An idle application
always has parked threads — pool workers with nothing to do park exactly like a request waiting
for a connection — so an absolute measure would hum constantly.

### Contention suppresses the melody

The pentatonic notes only play when nothing is contended. The pleasant layer falls away exactly
when things go wrong, which is a stronger signal than adding yet another alarm.

---

## Why there is a compressor

A `DynamicsCompressor` sits on the master bus. Without it, a contention storm landing on top of a
GC kick clips into mush. That matters most in a recorded clip, where there is no second chance.

---

## Reading the sound

Eight voices layered tell you that *something* is happening without telling you what. The
**What you are hearing** panel above the timeline gives each voice a meter that lights as it
sounds, with the reading that caused it beside it:

- continuous voices report their **actual gain**, so the drone visibly dips when it ducks
- transient voices report a level **decaying from the moment they fired**, so the panel shows
  which one just made a noise rather than only that something did
- the detail line names the collector and its pause, the thread that blocked and for how long,
  the allocation rate behind the hi-hat, and says plainly when a voice is silent

---

## The latency caveat

Flight Recorder flushes in batches of up to a second, and that is not tunable. Collections and
contention therefore *sound* up to a second after they happen. They are drawn and sounded at
their own timestamps, so the timeline stays truthful, but the kick for a collection lands
slightly after the pause itself.

Heap, CPU, threads, allocation and the stuck-thread count have no such delay. See
[FINDINGS.md](FINDINGS.md) §2.

---

## Tuning it

Three constants at the top of `audio.js` do most of the work:

| constant | default | raise it to |
| --- | --- | --- |
| `DRONE_GAIN` | 0.10 | make heap more present |
| `DRONE_HIGH_HZ` | 260 | widen the heap pitch range |
| `DUCK_DEPTH` | 0.45 | make events punch through harder |
| `BREATH_MAX_GAIN` | 0.16 | make waiting more obvious |
| `BREATH_FULL_SCALE` | 24 | require more stuck threads before the pad maxes out |

`window.sonify.describeAudio()` in the browser console reports the live state of the engine, and
`window.sonify.audio.voices()` returns exactly what the panel is showing.

---

## What is not verified

**Nobody has evaluated this by ear except the person using it.** The mapping was verified
numerically — the drone tracks heap, the filter tracks CPU, the meters match the engine — but
whether a struggling JVM actually *sounds* tense is a judgement no measurement here can make.
