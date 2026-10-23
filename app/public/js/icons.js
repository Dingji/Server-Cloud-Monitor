/* Cloud Monitor - SVG icon system (industrial, meaningful, no unicode glyphs) */
(function () {
  'use strict';

  /* 24x24 viewBox, stroke-based, currentColor */
  const P = {
    /* navigation */
    dashboard: '<rect x="3" y="3" width="7.5" height="7.5" rx="1"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1"/>',
    process: '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="7.5" cy="6" r="1.6"/><circle cx="12.5" cy="12" r="1.6"/><circle cx="9.5" cy="18" r="1.6"/>',
    cpu: '<rect x="7" y="7" width="10" height="10" rx="1.5"/><rect x="10.5" y="10.5" width="3" height="3"/><path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2"/>',
    memory: '<rect x="3" y="7" width="18" height="10" rx="1.5"/><path d="M7 7v10M12 7v10M17 7v10M6 17v2M18 17v2"/>',
    disk: '<ellipse cx="12" cy="6" rx="7.5" ry="2.8"/><path d="M4.5 6v12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8V6"/><path d="M4.5 12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8"/>',
    network: '<rect x="3" y="3" width="6" height="6" rx="1"/><rect x="15" y="15" width="6" height="6" rx="1"/><rect x="3" y="15" width="6" height="6" rx="1"/><path d="M9 6h6a3 3 0 013 3v6M9 18h3"/>',
    activity: '<path d="M3 12h3.5l2.5-6 3.5 13 3-9 2 2H21"/>',
    shield: '<path d="M12 3l7.5 3v5.5c0 4.6-3.1 7.9-7.5 9-4.4-1.1-7.5-4.4-7.5-9V6L12 3z"/><path d="M9 12l2 2 4-4"/>',
    gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.4M12 18.8v2.4M4.5 12H2.1M21.9 12h-2.4M6.4 6.4L4.7 4.7M19.3 19.3l-1.7-1.7M6.4 17.6l-1.7 1.7M19.3 4.7l-1.7 1.7"/>',
    database: '<ellipse cx="12" cy="6" rx="7.5" ry="2.8"/><path d="M4.5 6v12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8V6"/><path d="M4.5 12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8"/><path d="M9 9.7v4.6M15 9.7v4.6"/>',
    docker: '<path d="M3 13h15.5c0 4-3 6.5-7.5 6.5S3 17.2 3 13z"/><path d="M6.5 13V10h3v3M9.8 13V10h3v3M13.1 13V10h3v3M9.8 9.5V6.5h3v3"/><path d="M18.8 10.6c.7-.5 1.4-.6 2.2-.4"/>',
    browser: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M3 9h18"/><circle cx="6.2" cy="6.8" r=".6"/><circle cx="8.4" cy="6.8" r=".6"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5.2l3.4 2"/>',
    archive: '<rect x="3" y="4.5" width="18" height="4" rx="1"/><path d="M5 8.5V19a1.5 1.5 0 001.5 1.5h11A1.5 1.5 0 0019 19V8.5M10 12.5h4"/>',
    terminal: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7.5 10l2.5 2.5-2.5 2.5M12.5 15h4"/>',
    logs: '<path d="M5 3.5h9.5L19 8v12.5H5z"/><path d="M14.5 3.5V8H19"/><path d="M8 12h8M8 15.5h8M8 8.5h3"/>',
    server: '<rect x="3" y="4.5" width="18" height="6" rx="1.5"/><rect x="3" y="13.5" width="18" height="6" rx="1.5"/><path d="M6.5 7.5h.01M6.5 16.5h.01"/>',
    package: '<path d="M3.5 7.6L12 3.4l8.5 4.2v8.8L12 20.6l-8.5-4.2z"/><path d="M3.5 7.6L12 11.9l8.5-4.3M12 11.9v8.7"/><path d="M7.8 5.5l8.4 4.1"/>',
    box: '<rect x="3.5" y="3.5" width="17" height="17" rx="1.5"/><path d="M3.5 9h17M9 9v11.5"/>',
    /* ui */
    bell: '<path d="M18 15.5V10a6 6 0 10-12 0v5.5L4.5 18h15z"/><path d="M10 21h4"/>',
    search: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15l5 5"/>',
    filter: '<path d="M3.5 5h17l-6.5 8v5.5l-4 2V13z"/>',
    refresh: '<path d="M20 12a8 8 0 11-2.4-5.7"/><path d="M20 4v4.5h-4.5"/>',
    download: '<path d="M12 3.5v11M7.5 10.5L12 15l4.5-4.5"/><path d="M4 19.5h16"/>',
    upload: '<path d="M12 15.5v-11M7.5 8.5L12 4l4.5 4.5"/><path d="M4 19.5h16"/>',
    play: '<path d="M7 4.5l12 7.5-12 7.5z"/>',
    stop: '<rect x="6" y="6" width="12" height="12" rx="1"/>',
    restart: '<path d="M20 12a8 8 0 10-3 6.2"/><path d="M20 4.5V10h-5.5"/>',
    trash: '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13M10.5 11v5.5M13.5 11v5.5"/>',
    edit: '<path d="M4 20h4L20 8l-4-4L4 16z"/><path d="M14.5 5.5l4 4"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    check: '<path d="M4.5 12.5l5 5 10-11"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
    warning: '<path d="M12 3.5l9 16H3z"/><path d="M12 9.5v5M12 17.2v.1"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.8v.1"/>',
    chevronLeft: '<path d="M14.5 6L9 12l5.5 6"/>',
    chevronRight: '<path d="M9.5 6L15 12l-5.5 6"/>',
    chevronDown: '<path d="M6 9.5L12 15l6-5.5"/>',
    chevronUp: '<path d="M6 14.5L12 9l6 5.5"/>',
    copy: '<rect x="8.5" y="8.5" width="12" height="12" rx="1.5"/><path d="M15.5 5.5v-2h-12v12h2"/>',
    key: '<circle cx="8" cy="15" r="3.5"/><path d="M10.5 12.5L20 3M17 6l2 2M14.5 8.5l2 2"/>',
    link: '<path d="M9.5 14.5l5-5"/><path d="M8 11l-1.6 1.6a3.5 3.5 0 005 5L13 16M16 13l1.6-1.6a3.5 3.5 0 00-5-5L11 8"/>',
    lock: '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 017 0v2.5"/>',
    unlock: '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 016.6-1.7"/>',
    user: '<circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20c.8-3.6 3.8-5.5 7.5-5.5s6.7 1.9 7.5 5.5"/>',
    users: '<circle cx="9" cy="8.5" r="3.3"/><path d="M2.8 19.5c.7-3.1 3.2-4.8 6.2-4.8s5.5 1.7 6.2 4.8"/><path d="M16 5.5a3.3 3.3 0 010 6.3M18 19.5c-.3-1.6-1-3-2-4"/>',
    save: '<path d="M4.5 4.5h11L19.5 8.5v11h-15z"/><path d="M8 4.5v5h7v-5M8 19.5v-5.5h8v5.5"/>',
    external: '<path d="M14 4.5h5.5V10"/><path d="M19.5 4.5L11 13"/><path d="M18 14v5.5H4.5V6H10"/>',
    sort: '<path d="M8 4.5v15M4.5 16L8 19.5 11.5 16M16 19.5v-15M12.5 8L16 4.5 19.5 8"/>',
    eye: '<path d="M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
    gauge: '<path d="M4 17a9 9 0 1116 0"/><path d="M12 13l4-3.5"/><circle cx="12" cy="14" r="1.2"/>',
    layers: '<path d="M12 3.5L3 8l9 4.5L21 8z"/><path d="M3 12.5l9 4.5 9-4.5M3 16.5l9 4.5 9-4.5"/>',
    cube: '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M12 12l8-4.5M12 12v9M12 12L4 7.5"/>',
    table: '<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="M3 9.5h18M3 14.5h18M9 9.5v10M15 9.5v10"/>',
    columns: '<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="M9 4.5v15M15 4.5v15"/>',
    code: '<path d="M9 7l-4.5 5L9 17M15 7l4.5 5L15 17"/>',
    flask: '<path d="M9 3.5h6M10.5 3.5v5.2L5.5 17.5a2 2 0 001.7 3h9.6a2 2 0 001.7-3l-5-8.8V3.5"/><path d="M7.4 14.5h9.2"/>',
    chart: '<path d="M4 19.5V4.5M4 19.5h16"/><path d="M8 15V10M12 17V7M16 15v-3"/>',
    list: '<path d="M5 6.5h14M5 12h14M5 17.5h14"/>',
    star: '<path d="M12 4l2.5 5.2 5.5.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.5-.8z"/>',
    power: '<path d="M12 3.5v8"/><path d="M7 6.2a7.5 7.5 0 109.9 0"/>',
    export: '<path d="M12 15.5v-12M7.5 8L12 3.5 16.5 8"/><path d="M4.5 14v5.5h15V14"/>',
    import: '<path d="M12 3.5v12M7.5 11L12 15.5 16.5 11"/><path d="M4.5 17.5v2h15v-2"/>',
    scan: '<path d="M4 8.5V6a2 2 0 012-2h2.5M15.5 4H18a2 2 0 012 2v2.5M20 15.5V18a2 2 0 01-2 2h-2.5M8.5 20H6a2 2 0 01-2-2v-2.5"/><path d="M4 12h16"/>',
    cloud: '<path d="M7 18.5h10.5a3.5 3.5 0 000-7 5 5 0 00-9.6-1.4A3.6 3.6 0 007 18.5z"/>',
    monitor: '<rect x="3" y="4" width="18" height="12" rx="1.5"/><path d="M9 20h6M12 16v4"/>',
    shieldAlert: '<path d="M12 3l7.5 3v5.5c0 4.6-3.1 7.9-7.5 9-4.4-1.1-7.5-4.4-7.5-9V6L12 3z"/><path d="M12 8.5v4M12 15.6v.1"/>',
    wlan: '<path d="M4 9.5a12 12 0 0116 0M7 13a8 8 0 0110 0M10 16.3a4 4 0 014 0"/><circle cx="12" cy="19.5" r=".8"/>',
    firewall: '<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="M3 9.5h18M3 14.5h18M12 4.5v15M7.5 9.5v5M16.5 9.5v5"/>',
    sliders: '<path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h9M17 17h3"/><circle cx="15" cy="7" r="1.8"/><circle cx="9" cy="12" r="1.8"/><circle cx="15" cy="17" r="1.8"/>',
    logout: '<path d="M15 4.5H6.5A1.5 1.5 0 005 6v12a1.5 1.5 0 001.5 1.5H15"/><path d="M18 8.5L21.5 12 18 15.5M10 12h11"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    satellite: '<path d="M12 8.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7z"/><path d="M6.2 5.8l3 3M17.8 18.2l-3-3M17.8 5.8l-3 3M6.2 18.2l3-3"/><path d="M4 9V4h5M20 15v5h-5"/><circle cx="12" cy="12" r="1.2"/>',
    folder: '<path d="M3.5 6.5h5l1.8 2h10.2v10.5h-17z"/>',
    stop2: '<rect x="6" y="6" width="12" height="12" rx="1"/>',
  };

  window.icon = function (name, opts) {
    const d = P[name];
    if (!d) return '';
    opts = opts || {};
    const size = opts.size || 16;
    const cls = 'ic' + (opts.cls ? ' ' + opts.cls : '');
    const sw = opts.stroke || 1.7;
    return `<svg class="${cls}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;
  };
  window.iconNames = Object.keys(P);
})();
