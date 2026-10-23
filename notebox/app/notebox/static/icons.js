/* NoteBox hand-drawn animated SVG icon set (no emoji, no external deps).
   Usage: icon('name', 'extra-class') -> svg string; <span data-ico="name"> is hydrated on load. */
(function () {
  const ICONS = {
    // package / brand
    box:
      '<path class="bx-lid" d="M12 3.2 20.7 7.7 12 12.2 3.3 7.7Z"/>' +
      '<path class="bx-sl" d="M3.3 7.7v8.4L12 20.6v-8.4"/>' +
      '<path class="bx-sr" d="M20.7 7.7v8.4L12 20.6v-8.4"/>' +
      '<path class="bx-seam" d="M12 12.2v8.4"/>',

    // pencil on paper (notes)
    note:
      '<rect class="nt-page" x="3.4" y="3" width="12.8" height="17" rx="2.2"/>' +
      '<path class="nt-rule" pathLength="100" d="M6.8 8.1h6.2M6.8 11.5h6.2M6.8 14.9h3.6"/>' +
      '<path class="nt-pen" pathLength="100" d="M14.4 20.1l.9-3.5 4.6-4.6a1.35 1.35 0 0 1 1.9 1.9l-4.6 4.6-2.8 1.6z"/>',

    // checked square (todos)
    checkSquare:
      '<rect class="cs-fr" x="3.5" y="3.5" width="17" height="17" rx="4.4"/>' +
      '<path class="cs-ck" pathLength="100" d="M7.9 12.2l2.9 2.9 5.4-5.9"/>',

    // circle + check (success states)
    checkCircle:
      '<circle class="cc-fr" cx="12" cy="12" r="8.7"/>' +
      '<path class="cc-ck" pathLength="100" d="M8.1 12.3l2.7 2.7 5.1-5.6"/>',

    // circle + cross (error states)
    crossCircle:
      '<circle class="xc-fr" cx="12" cy="12" r="8.7"/>' +
      '<path class="xc-x" pathLength="100" d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/>',

    // warning triangle
    warn:
      '<path class="wn-tri" pathLength="100" d="M12 4.3l8.7 15.1H3.3z"/>' +
      '<path class="wn-ex" d="M12 9.7v3.9"/>' +
      '<circle class="wn-dot" cx="12" cy="16.9" r="1"/>',

    // skipped (circle + skip forward)
    skip:
      '<circle class="sk-fr" cx="12" cy="12" r="8.7"/>' +
      '<path class="sk-t" d="M9.7 8.4l5.1 3.6-5.1 3.6z"/>' +
      '<path class="sk-b" d="M16.5 8.4v7.2"/>',

    // sparkle (empty-state celebration)
    sparkle:
      '<path class="sp-big" d="M10.6 3.4c.6 3.6 1.6 4.6 5.2 5.2-3.6.6-4.6 1.6-5.2 5.2-.6-3.6-1.6-4.6-5.2-5.2 3.6-.6 4.6-1.6 5.2-5.2z"/>' +
      '<path class="sp-small" d="M17.7 15.1c.3 1.8.8 2.3 2.6 2.6-1.8.3-2.3.8-2.6 2.6-.3-1.8-.8-2.3-2.6-2.6 1.8-.3 2.3-.8 2.6-2.6z"/>',

    // download into tray (import)
    import:
      '<path class="im-arw" d="M12 3.4v7.6M8.7 7.9 12 11.2l3.3-3.3"/>' +
      '<path class="im-tray" d="M4.4 13.4v4a2.6 2.6 0 0 0 2.6 2.6h10a2.6 2.6 0 0 0 2.6-2.6v-4"/>',

    // upload out of tray (export)
    export:
      '<path class="ex-tray" d="M4.4 13.4v4a2.6 2.6 0 0 0 2.6 2.6h10a2.6 2.6 0 0 0 2.6-2.6v-4"/>' +
      '<path class="ex-arw" d="M12 11.6V4M8.7 7.3 12 4l3.3 3.3"/>',

    // envelope that opens on hover (mail)
    mail:
      '<rect class="ml-env" x="3.4" y="5.6" width="17.2" height="13" rx="2.4"/>' +
      '<path class="ml-ltr" d="M8.4 10.4h7.2v5H8.4z"/>' +
      '<path class="ml-flap" pathLength="100" d="M4.4 7.2 12 12.6l7.6-5.4"/>',

    // settings sliders
    sliders:
      '<path class="sl-rail" d="M4 7.6h16M4 16.4h16"/>' +
      '<circle class="sl-k1" cx="9.4" cy="7.6" r="2.5"/>' +
      '<circle class="sl-k2" cx="15" cy="16.4" r="2.5"/>',

    // plus (new)
    plus: '<path class="pl" pathLength="100" d="M12 5.4v13.2M5.4 12h13.2"/>',

    // pushpin (pin)
    pin:
      '<path class="pn-bar" d="M8.7 4.2h6.6"/>' +
      '<path class="pn-body" d="M10.2 4.2v5.2l-2.5 3.7h8.6l-2.5-3.7V4.2"/>' +
      '<path class="pn-needle" d="M12 13.1V20"/>',

    // wastebasket (delete)
    trash:
      '<path class="tb-lid" d="M4.4 7h15.2M9.6 7V5.2A1.6 1.6 0 0 1 11.2 3.6h1.6A1.6 1.6 0 0 1 14.4 5.2V7"/>' +
      '<path class="tb-bin" d="M6.3 7l.9 11.9a2.2 2.2 0 0 0 2.2 2h5.2a2.2 2.2 0 0 0 2.2-2L17.7 7"/>' +
      '<path class="tb-line" d="M10.2 11v6M13.8 11v6"/>',

    // calendar (due date)
    calendar:
      '<rect class="cd-fr" x="3.4" y="5.4" width="17.2" height="15.2" rx="2.6"/>' +
      '<path class="cd-hdr" d="M3.4 10h17.2"/>' +
      '<path class="cd-post" d="M8 3.4v4M16 3.4v4"/>' +
      '<g class="cd-cells">' +
      '<rect x="6.6" y="12.4" width="3" height="3" rx=".8"/>' +
      '<rect x="10.5" y="12.4" width="3" height="3" rx=".8"/>' +
      '<rect x="14.4" y="12.4" width="3" height="3" rx=".8"/>' +
      '<rect x="6.6" y="16.4" width="3" height="3" rx=".8"/>' +
      '<rect x="10.5" y="16.4" width="3" height="3" rx=".8"/>' +
      '<rect x="14.4" y="16.4" width="3" height="3" rx=".8"/>' +
      '</g>',

    // file with down arrow (download file)
    fileDown:
      '<path class="fd-fr" d="M13.6 3.6H6.9A1.9 1.9 0 0 0 5 5.5v13A1.9 1.9 0 0 0 6.9 20.4h10.2a1.9 1.9 0 0 0 1.9-1.9V8.7z"/>' +
      '<path class="fd-fold" d="M13.6 3.6v5.1h5.1"/>' +
      '<path class="fd-arw" d="M12 11.1v5.3M9.7 14.2 12 16.5l2.3-2.3"/>',

    // arrows
    arrowLeft: '<path class="ar-l" d="M19.2 12H5.2M10.6 6.6 5.2 12l5.4 5.4"/>',
    arrowRight: '<path class="ar-r" d="M4.8 12h14M13.4 6.6 18.8 12l-5.4 5.4"/>',

    // plain check
    check: '<path class="ck" pathLength="100" d="M4.9 12.7l4.6 4.6L19.1 6.7"/>',

    // loading spinner
    spinner:
      '<circle class="sp-c" cx="12" cy="12" r="8.7"/>' +
      '<path class="sp-a" pathLength="100" d="M12 3.3a8.7 8.7 0 0 1 8.7 8.7"/>',

    // bell (reminders)
    bell:
      '<path class="bl-dome" d="M12 3.6a5.4 5.4 0 0 0-5.4 5.4c0 3.9-1.5 5.3-1.5 5.3h13.8s-1.5-1.4-1.5-5.3A5.4 5.4 0 0 0 12 3.6z"/>' +
      '<path class="bl-clap" d="M10.3 17.8a1.9 1.9 0 0 0 3.4 0"/>',

    // magnifier (search)
    search:
      '<circle class="sr-c" cx="10.6" cy="10.6" r="6.4"/>' +
      '<path class="sr-h" d="M15.4 15.4 20.4 20.4"/>',

    // person (username)
    user:
      '<circle class="ur-hd" cx="12" cy="8.2" r="3.8"/>' +
      '<path class="ur-bd" d="M4.9 20.3a7.3 7.3 0 0 1 14.2 0"/>',

    // padlock (password)
    lock:
      '<rect class="lk-bx" x="4.6" y="10.3" width="14.8" height="9.8" rx="2.6"/>' +
      '<path class="lk-sh" d="M8.3 10.3V7.9a3.7 3.7 0 0 1 7.4 0v2.4"/>' +
      '<circle class="lk-kn" cx="12" cy="15.2" r="1.35"/>',

    // picture (image insert)
    image:
      '<rect class="im-fr" x="3.4" y="4.8" width="17.2" height="14.4" rx="2.6"/>' +
      '<circle class="im-sun" cx="8.7" cy="9.7" r="1.7"/>' +
      '<path class="im-mt" d="M3.8 17.2 9.1 12.3l3.3 2.9 3.1-2.6 4.7 4.6"/>',

    // video camera (video insert)
    video:
      '<rect class="vd-fr" x="3.2" y="5.6" width="13.2" height="12.8" rx="2.6"/>' +
      '<path class="vd-lens" d="M16.4 10.4 20.8 7.9v8.2l-4.4-2.5z"/>',

    // angle brackets (code block)
    code:
      '<path class="cd-br" d="M8.6 7.6 4.2 12l4.4 4.4M15.4 7.6 19.8 12l-4.4 4.4M13.6 5.6l-3.2 12.8"/>',

    // chain link
    link:
      '<path class="ln-a" d="M10.4 13.6a4.2 4.2 0 0 0 6 0l2.8-2.8a4.24 4.24 0 0 0-6-6l-1.6 1.6"/>' +
      '<path class="ln-b" d="M13.6 10.4a4.2 4.2 0 0 0-6 0l-2.8 2.8a4.24 4.24 0 0 0 6 6l1.6-1.6"/>',

    // share (nodes)
    share:
      '<circle class="sh-n" cx="17.8" cy="5.6" r="2.6"/>' +
      '<circle class="sh-n" cx="6.2" cy="12" r="2.6"/>' +
      '<circle class="sh-n" cx="17.8" cy="18.4" r="2.6"/>' +
      '<path class="sh-l" d="M8.6 10.8 15.4 7M8.6 13.2 15.4 17"/>',
  };

  function icon(name, cls) {
    const body = ICONS[name];
    if (body == null) return "";
    return (
      '<svg class="ico ico-' + name + (cls ? " " + cls : "") +
      '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"' +
      ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + body + "</svg>"
    );
  }

  function hydrate(root) {
    const scope = root || document;
    scope.querySelectorAll("[data-ico]").forEach(function (el) {
      if (!el.querySelector("svg")) {
        el.innerHTML = icon(el.dataset.ico, el.dataset.icoClass || "");
      }
    });
  }

  window.icon = icon;
  window.hydrateIcons = hydrate;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () { hydrate(); });
  } else {
    hydrate();
  }
})();
