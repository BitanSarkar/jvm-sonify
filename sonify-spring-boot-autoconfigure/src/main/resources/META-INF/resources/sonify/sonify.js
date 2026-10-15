// Minimal client for milestone 2: proves the stream arrives. The visual and audible UI
// replaces this page in milestone 3.
(function () {
  'use strict';

  var LOG_LIMIT = 40;
  var stateEl = document.getElementById('state');
  var statsEl = document.getElementById('stats');
  var logEl = document.getElementById('log');
  var lines = [];
  var backoffMs = 500;

  function setState(name, label) {
    stateEl.className = 'state state--' + name;
    stateEl.textContent = label;
  }

  function mb(bytes) {
    return (bytes / 1048576).toFixed(0);
  }

  function percent(value) {
    return value === null || value === undefined ? '--' : (value * 100).toFixed(0) + '%';
  }

  function onTick(tick) {
    statsEl.textContent =
      'heap ' + mb(tick.heapUsed) + ' / ' + mb(tick.heapCommitted) + ' MB' +
      '   alloc ' + tick.allocMBps.toFixed(1) + ' MB/s' +
      '   cpu ' + percent(tick.cpu) +
      '   machine ' + percent(tick.machineCpu) +
      '   threads ' + tick.threads +
      '   contended ' + tick.contended +
      '   parked ' + tick.parked;
  }

  function append(text) {
    lines.push(text);
    if (lines.length > LOG_LIMIT) {
      lines.shift();
    }
    logEl.textContent = lines.join('\n');
  }

  function connect() {
    var source = new EventSource('stream');

    source.addEventListener('open', function () {
      setState('live', 'live');
      backoffMs = 500;
    });

    source.addEventListener('tick', function (event) {
      onTick(JSON.parse(event.data));
    });

    source.addEventListener('gc', function (event) {
      var gc = JSON.parse(event.data);
      append('GC  ' + gc.name + '  ' + gc.pauseMs.toFixed(2) + 'ms  cause=' + gc.cause +
        (gc.reclaimedBytes === null ? '' : '  reclaimed ' + mb(gc.reclaimedBytes) + 'MB'));
    });

    source.addEventListener('contention', function (event) {
      var hit = JSON.parse(event.data);
      append('LOCK ' + hit.thread + ' blocked ' + hit.durationMs.toFixed(1) + 'ms on ' + hit.monitor);
    });

    source.addEventListener('error', function () {
      source.close();
      setState('down', 'disconnected');
      // EventSource reconnects on its own, but only for transport errors; a 503 is terminal,
      // so reconnection is driven here with a backoff.
      setTimeout(connect, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 10000);
    });
  }

  connect();
})();
