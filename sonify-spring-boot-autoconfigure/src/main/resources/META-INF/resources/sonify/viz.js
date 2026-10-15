// Canvas renderer for JVM Sonify: a scrolling timeline, newest on the right.
//
// Everything is placed by the event's own timestamp rather than by arrival order, because
// Flight Recorder delivers in batches of up to a second. A collection that happened 800ms ago
// therefore lands 800ms to the left of the playhead, where it belongs, instead of piling up
// at the right edge with everything else in its batch.
window.SonifyViz = (function () {
  'use strict';

  var WINDOW_MS = 30000;
  var LANE_GAP = 10;
  var MAX_THREAD_ROWS = 16;
  var MIN_ROW_HEIGHT = 11;
  var AXIS_WIDTH = 98;

  function create(canvas, state) {
    var ctx = canvas.getContext('2d');
    var width = 0;
    var height = 0;
    var colors = {};

    function readColors() {
      var style = getComputedStyle(document.documentElement);
      colors = {
        grid: style.getPropertyValue('--grid').trim(),
        axis: style.getPropertyValue('--muted').trim(),
        heap: style.getPropertyValue('--heap').trim(),
        heapCommitted: style.getPropertyValue('--heap-committed').trim(),
        alloc: style.getPropertyValue('--alloc').trim(),
        gc: style.getPropertyValue('--gc').trim(),
        running: style.getPropertyValue('--running').trim(),
        contended: style.getPropertyValue('--contended').trim(),
        parked: style.getPropertyValue('--parked').trim(),
        label: style.getPropertyValue('--fg').trim()
      };
    }

    /** Sizes the backing store to the device pixel ratio so lines stay crisp. */
    function resize() {
      var ratio = window.devicePixelRatio || 1;
      var rect = canvas.getBoundingClientRect();
      width = Math.max(320, Math.floor(rect.width));
      height = Math.max(260, Math.floor(rect.height));
      canvas.width = Math.floor(width * ratio);
      canvas.height = Math.floor(height * ratio);
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      readColors();
    }

    function lanes() {
      var usable = height - LANE_GAP * 2;
      var heap = Math.round(usable * 0.40);
      var alloc = Math.round(usable * 0.22);
      var threads = usable - heap - alloc;
      return {
        heap: { top: 0, height: heap },
        alloc: { top: heap + LANE_GAP, height: alloc },
        threads: { top: heap + alloc + LANE_GAP * 2, height: threads }
      };
    }

    function xOf(timestamp, now) {
      var age = now - timestamp;
      return AXIS_WIDTH + (1 - age / WINDOW_MS) * (width - AXIS_WIDTH);
    }

    /** Truncates a label to the gutter, keeping the tail where thread names differ. */
    function fit(text, maxWidth) {
      if (ctx.measureText(text).width <= maxWidth) {
        return text;
      }
      var trimmed = text;
      while (trimmed.length > 4 && ctx.measureText('\u2026' + trimmed).width > maxWidth) {
        trimmed = trimmed.slice(1);
      }
      return '\u2026' + trimmed;
    }

    function formatMb(bytes) {
      var mb = bytes / 1048576;
      return mb >= 1024 ? (mb / 1024).toFixed(1) + 'G' : Math.round(mb) + 'M';
    }

    function laneFrame(lane, title, topLabel) {
      ctx.strokeStyle = colors.grid;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(AXIS_WIDTH, lane.top + lane.height + 0.5);
      ctx.lineTo(width, lane.top + lane.height + 0.5);
      ctx.stroke();

      ctx.fillStyle = colors.axis;
      ctx.font = '10px ui-monospace, Menlo, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(title, 4, lane.top + 10);
      if (topLabel) {
        ctx.fillText(topLabel, 4, lane.top + 22);
      }
    }

    function drawHeap(lane, ticks, now) {
      if (!ticks.length) {
        return;
      }
      // Scaled to committed rather than max: a 512MB heap sitting at 65MB would otherwise
      // press the line flat against the floor and hide the sawtooth worth looking at.
      var committed = 0;
      var used = 0;
      var limit = 0;
      ticks.forEach(function (tick) {
        committed = Math.max(committed, tick.heapCommitted);
        used = Math.max(used, tick.heapUsed);
        limit = Math.max(limit, tick.heapMax);
      });
      var ceiling = Math.max(committed, used) * 1.08;
      if (limit > 0) {
        ceiling = Math.min(ceiling, limit);
      }
      if (ceiling <= 0) {
        return;
      }
      var yOf = function (bytes) {
        return lane.top + lane.height - (bytes / ceiling) * lane.height;
      };

      // Committed heap first, as a dim reference line behind the used line.
      ctx.strokeStyle = colors.heapCommitted;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ticks.forEach(function (tick, index) {
        var x = xOf(tick.t, now);
        var y = yOf(tick.heapCommitted);
        if (index === 0) { ctx.moveTo(x, y); } else { ctx.lineTo(x, y); }
      });
      ctx.stroke();

      // GC bars sit behind the used line so the line stays readable across them.
      state.gcEvents.forEach(function (event) {
        var x = xOf(event.t, now);
        if (x < AXIS_WIDTH) {
          return;
        }
        var long = event.pauseMs > 100;
        ctx.globalAlpha = long ? 0.75 : 0.3 + Math.min(0.4, event.pauseMs / 100);
        ctx.fillStyle = colors.gc;
        var barWidth = long ? 3 : 1.5;
        ctx.fillRect(x - barWidth / 2, lane.top + 4, barWidth, lane.height - 4);
        ctx.globalAlpha = 1;
      });

      ctx.strokeStyle = colors.heap;
      ctx.lineWidth = 1.75;
      ctx.beginPath();
      ticks.forEach(function (tick, index) {
        var x = xOf(tick.t, now);
        var y = yOf(tick.heapUsed);
        if (index === 0) { ctx.moveTo(x, y); } else { ctx.lineTo(x, y); }
      });
      ctx.stroke();

      // The configured maximum, when it is off the top of the scaled lane, as a reminder of
      // how much room is left.
      if (limit > 0 && limit > ceiling) {
        ctx.fillStyle = colors.axis;
        ctx.font = '9px ui-monospace, Menlo, monospace';
        ctx.textAlign = 'right';
        ctx.fillText('max ' + formatMb(limit), width - 4, lane.top + 10);
        ctx.textAlign = 'left';
      }
      laneFrame(lane, 'HEAP', formatMb(ceiling) + ' full scale');
    }

    /**
     * Allocation on a log scale. The measured range runs from an idle flat line to tens of
     * gigabytes a second, so a linear axis would show nothing but a spike and a floor.
     */
    function drawAlloc(lane, ticks, now) {
      if (!ticks.length) {
        return;
      }
      var peak = 10;
      ticks.forEach(function (tick) {
        peak = Math.max(peak, tick.allocMBps);
      });
      var top = Math.log10(1 + peak);
      var yOf = function (mbps) {
        var scaled = top > 0 ? Math.log10(1 + Math.max(0, mbps)) / top : 0;
        return lane.top + lane.height - scaled * (lane.height - 6);
      };

      ctx.beginPath();
      ctx.moveTo(xOf(ticks[0].t, now), lane.top + lane.height);
      ticks.forEach(function (tick) {
        ctx.lineTo(xOf(tick.t, now), yOf(tick.allocMBps));
      });
      ctx.lineTo(xOf(ticks[ticks.length - 1].t, now), lane.top + lane.height);
      ctx.closePath();
      ctx.globalAlpha = 0.28;
      ctx.fillStyle = colors.alloc;
      ctx.fill();
      ctx.globalAlpha = 1;

      ctx.strokeStyle = colors.alloc;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ticks.forEach(function (tick, index) {
        var x = xOf(tick.t, now);
        var y = yOf(tick.allocMBps);
        if (index === 0) { ctx.moveTo(x, y); } else { ctx.lineTo(x, y); }
      });
      ctx.stroke();

      laneFrame(lane, 'ALLOC', Math.round(peak) + ' MB/s');
    }

    /**
     * One row per thread, most interesting first. Dots are drawn grouped by colour rather
     * than per dot, which keeps a full window of sixteen rows cheap to repaint.
     */
    function drawThreads(lane, ticks, now) {
      // Only as many rows as can carry a readable label: cramming sixteen names into a short
      // lane produced an unreadable block of overlapping text.
      var capacity = Math.max(1, Math.floor((lane.height - 18) / MIN_ROW_HEIGHT));
      var visible = Math.min(MAX_THREAD_ROWS, capacity);
      var rows = state.threadRows.slice(0, visible);
      if (!rows.length) {
        laneFrame(lane, 'THREADS', '');
        return;
      }
      var hidden = state.threadRows.length - rows.length;
      var rowHeight = Math.max(MIN_ROW_HEIGHT, Math.floor((lane.height - 18) / rows.length));
      var index = new Map();
      rows.forEach(function (row, position) {
        index.set(row.name, position);
      });

      var buckets = { running: [], contended: [], parked: [] };
      ticks.forEach(function (tick) {
        if (!tick.threadDetail) {
          return;
        }
        var x = xOf(tick.t, now);
        if (x < AXIS_WIDTH) {
          return;
        }
        tick.threadDetail.forEach(function (detail) {
          if (!index.has(detail.name)) {
            return;
          }
          var y = lane.top + 14 + index.get(detail.name) * rowHeight;
          (buckets[detail.state] || buckets.running).push(x, y);
        });
      });

      Object.keys(buckets).forEach(function (stateName) {
        var points = buckets[stateName];
        if (!points.length) {
          return;
        }
        ctx.fillStyle = colors[stateName];
        var size = stateName === 'contended' ? 3 : 2;
        for (var i = 0; i < points.length; i += 2) {
          ctx.fillRect(points[i] - size / 2, points[i + 1] - size / 2, size, size);
        }
      });

      ctx.font = '9px ui-monospace, Menlo, monospace';
      ctx.textAlign = 'left';
      rows.forEach(function (row, position) {
        ctx.fillStyle = row.contended ? colors.contended : colors.axis;
        var label = row.name.length > 22 ? row.name.slice(0, 21) + '…' : row.name;
        ctx.fillText(label, 4, lane.top + 17 + position * rowHeight);
      });

      laneFrame(lane, 'THREADS', '');
    }

    function drawTimeAxis(now) {
      ctx.strokeStyle = colors.grid;
      ctx.lineWidth = 1;
      ctx.fillStyle = colors.axis;
      ctx.font = '9px ui-monospace, Menlo, monospace';
      ctx.textAlign = 'center';
      for (var seconds = 0; seconds <= WINDOW_MS / 1000; seconds += 5) {
        var x = xOf(now - seconds * 1000, now);
        ctx.globalAlpha = 0.5;
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, height);
        ctx.stroke();
        ctx.globalAlpha = 1;
        if (seconds > 0) {
          ctx.fillText('-' + seconds + 's', x, height - 2);
        }
      }
      ctx.textAlign = 'left';
    }

    function draw() {
      if (!width) {
        resize();
      }
      var now = Date.now();
      var cutoff = now - WINDOW_MS;
      var ticks = state.ticks.filter(function (tick) {
        return tick.t >= cutoff;
      });

      ctx.clearRect(0, 0, width, height);
      drawTimeAxis(now);

      var frame = lanes();
      drawHeap(frame.heap, ticks, now);
      drawAlloc(frame.alloc, ticks, now);
      drawThreads(frame.threads, ticks, now);
    }

    return { draw: draw, resize: resize, windowMs: WINDOW_MS };
  }

  return { create: create };
})();
