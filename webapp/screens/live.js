// Karen live telemetry bridge — no hardcoded numbers.
// Reads real values from the browser: battery, connection, memory, storage, clock.
(function () {
  const set = (id, text) => {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
  };

  async function refresh() {
    // Clock-based greeting
    const h = new Date().getHours();
    set('live-greeting', h < 12 ? 'Good morning, Alex' : h < 18 ? 'Good afternoon, Alex' : 'Good evening, Alex');

    // Network / egress
    const online = navigator.onLine;
    set('live-egress', online ? 'Network Up' : '0 KB · Offline');
    const conn = navigator.connection || {};
    set('live-rtt', conn.rtt != null ? conn.rtt + ' ms' : '—');
    set('live-downlink', conn.downlink != null ? conn.downlink + ' Mb/s' : '—');

    // Cores
    set('live-cores', (navigator.hardwareConcurrency || '—') + ' Cores');

    // Device RAM
    set('live-device-ram', navigator.deviceMemory ? navigator.deviceMemory + ' GB' : '—');
    if (performance.memory) {
      const used = (performance.memory.usedJSHeapSize / 1073741824).toFixed(2);
      const total = (performance.memory.jsHeapSizeLimit / 1073741824).toFixed(2);
      set('live-ram', used + ' / ' + total + ' GB');
    } else {
      set('live-ram', navigator.deviceMemory ? '(heap n/a) · ' + navigator.deviceMemory + ' GB' : '—');
    }

    // Battery
    if (navigator.getBattery) {
      try {
        const b = await navigator.getBattery();
        set('live-battery', Math.round(b.level * 100) + '%' + (b.charging ? ' · charging' : ''));
        set('live-battery2', Math.round(b.level * 100) + '%');
      } catch (e) { set('live-battery', 'n/a'); }
    } else { set('live-battery', 'n/a'); set('live-battery2', 'n/a'); }

    // Storage estimate
    if (navigator.storage && navigator.storage.estimate) {
      try {
        const est = await navigator.storage.estimate();
        const used = (est.usage / 1073741824).toFixed(2);
        const total = (est.quota / 1073741824).toFixed(1);
        set('live-storage', used + ' / ' + total + ' GB');
      } catch (e) { set('live-storage', '—'); }
    }

    // Uptime
    set('live-uptime', Math.floor(performance.now() / 60000) + 'm');

    // Aliases used on other cards
    set('live-rtt2', conn.rtt != null ? conn.rtt + ' ms' : '—');
    set('live-egress2', online ? 'Network Up · 0 KB forced' : 'Offline · 0 KB');
    if (navigator.storage && navigator.storage.estimate) {
      try {
        const est2 = await navigator.storage.estimate();
        set('live-storage2', (est2.usage / 1073741824).toFixed(2) + ' / ' + (est2.quota / 1073741824).toFixed(1) + ' GB');
      } catch (e) { /* ignore */ }
    }
  }

  refresh();
  setInterval(refresh, 2000);

  if (navigator.connection) {
    navigator.connection.addEventListener('change', refresh);
  }
})();
