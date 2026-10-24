/* Cloud Monitor - ui helpers: tables, charts, gauges */
(function () {
  'use strict';

  window.ui = {
    /* build table: cols [{k, label, cls, render(v,row)}], rows [] */
    table(cols, rows, opts) {
      opts = opts || {};
      const head = cols.map((c) => `<th class="${c.cls || ''}">${fmt.esc(c.label)}</th>`).join('');
      const body = rows.map((r, i) => {
        const tds = cols.map((c) => {
          const v = c.k ? r[c.k] : '';
          const html = c.render ? c.render(v, r, i) : fmt.esc(v);
          return `<td class="${c.cls || ''}">${html}</td>`;
        }).join('');
        return `<tr data-i="${i}">${tds}</tr>`;
      }).join('');
      const wrap = `<div class="table-wrap" ${opts.style ? `style="${opts.style}"` : ''}><table class="tbl"><thead><tr>${head}</tr></thead><tbody>${rows.length ? body : `<tr><td colspan="${cols.length}" class="muted" style="text-align:center;padding:22px">— 无数据 —</td></tr>`}</tbody></table></div>`;
      /* mobile-hint 仅在窄屏显示（见 app.css 的 .mobile-hint） */
      return opts.hint === false ? wrap : wrap + '<div class="mobile-hint">← 左右滑动查看更多</div>';
    },

    /* live chart factory (Chart.js) */
    chart(canvas, cfg) {
      const datasets = cfg.series.map((s) => ({
        label: s.label,
        data: [],
        borderColor: s.color,
        backgroundColor: s.fill ? window.ui._alpha(s.color, 0.12) : 'transparent',
        fill: !!s.fill,
        borderWidth: s.width || 1.6,
        pointRadius: 0,
        tension: 0.3,
      }));
      const c = new Chart(canvas.getContext('2d'), {
        type: 'line',
        data: { labels: [], datasets },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          /* first paint draws itself in; every tick uses update('none') so
             live data never waits on an animation */
          animation: { duration: 480, easing: 'easeOutQuart' },
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: { display: cfg.series.length > 1, labels: { color: '#7f92a8', boxWidth: 10, font: { size: 10.5 } } },
            tooltip: { enabled: cfg.tooltip !== false },
          },
          scales: {
            x: { ticks: { color: '#5d7089', maxTicksLimit: 8, font: { size: 9.5 } }, grid: { color: 'rgba(34,48,65,.5)' } },
            y: {
              beginAtZero: cfg.beginAtZero !== false,
              suggestedMax: cfg.suggestedMax,
              ticks: { color: '#5d7089', font: { size: 9.5 }, callback: cfg.yFormat },
              grid: { color: 'rgba(34,48,65,.5)' },
            },
          },
        },
      });
      const max = cfg.points || 75;
      return {
        chart: c,
        push(labels, values) {
          c.data.labels.push(labels);
          if (c.data.labels.length > max) c.data.labels.shift();
          values.forEach((vals, i) => {
            const arr = c.data.datasets[i].data;
            arr.push(vals);
            if (arr.length > max) arr.shift();
          });
          window.ui._syncChart(c);
          c.update('none');
        },
        /* append many points with a single redraw (used for history replay) */
        pushBatch(labels, valuesArr, animate) {
          const add = Math.max(0, labels.length - max);
          c.data.labels.push(...labels.slice(add));
          valuesArr.forEach((vals, i) => {
            const arr = c.data.datasets[i].data;
            arr.push(...vals.slice(add));
            while (arr.length > max) arr.shift();
          });
          window.ui._syncChart(c);
          c.update(animate ? undefined : 'none');
        },
        resize() { try { c.resize(); } catch (_) {} },
        destroy() { c.destroy(); },
      };
    },

    _alpha(hex, a) {
      const h = hex.replace('#', '');
      const n = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16);
      return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
    },

    /* labels 与各 dataset 必须等长，否则 Chart.js 会在尾部留出空白 */
    _syncChart(c) {
      const n = c.data.labels.length;
      c.data.datasets.forEach((ds) => {
        while (ds.data.length > n) ds.data.shift();
        while (ds.data.length < n) ds.data.unshift(null);
      });
    },

    badge(text, kind) { return `<span class="badge ${kind}">${fmt.esc(text)}</span>`; },

    /* skeleton placeholder block */
    skeleton(rows) {
      rows = rows || 3;
      let s = '<div class="skel big"></div>';
      for (let i = 0; i < rows; i++) s += `<div class="skel line" style="width:${70 + (i * 13) % 30}%"></div>`;
      return s;
    },

    /* smooth number tween on an element.
       - 从"当前显示值"开始补间：不会从 0 或大数字扫过来
       - 首次渲染直接赋值，不做动画
       - 动画中持续记录显示值，新值到来时从当前位置继续
       - minDelta: 变化太小时直接赋值，避免抖动 */
    animateNumber(el, to, format, ms, minDelta) {
      if (!el || !Number.isFinite(to)) return;
      const shown = parseFloat(el.dataset.nv);
      const md = minDelta || 0;
      if (!Number.isFinite(shown)) {           /* 首次渲染：直接赋值 */
        el.textContent = format(to);
        el.dataset.nv = to;
        return;
      }
      if (Math.abs(to - shown) < Math.max(md, 1e-9)) {
        el.textContent = format(to);
        el.dataset.nv = to;
        return;
      }
      const token = String((parseInt(el.dataset.nt || '0', 10) || 0) + 1);
      el.dataset.nt = token;
      const dur = ms || 550;
      const t0 = performance.now();
      const step = (t) => {
        if (el.dataset.nt !== token) return;   /* superseded by a newer value */
        const k = Math.min(1, (t - t0) / dur);
        const e = 1 - Math.pow(1 - k, 3);
        const v = shown + (to - shown) * e;
        el.textContent = format(v);
        el.dataset.nv = k < 1 ? v : to;        /* 记录“正在显示”的值 */
        if (k < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    },

    /* button busy state: spins an inline SVG indicator, content preserved */
    busy(btn, on) {
      if (!btn) return;
      if (on) {
        btn.classList.add('loading');
        btn.disabled = true;
        if (!btn.querySelector('.busy-ic')) {
          const s = document.createElement('span');
          s.className = 'busy-ic';
          s.innerHTML = '<svg class="ic" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 3a9 9 0 109 9"/></svg>';
          btn.appendChild(s);
        }
      } else {
        btn.classList.remove('loading');
        btn.disabled = false;
        const s = btn.querySelector('.busy-ic');
        if (s) s.remove();
      }
    },

    /* tiny sparkline on a canvas; returns push(value) */
    spark(canvas, color, max) {
      const ctx = canvas.getContext('2d');
      const data = [];
      const N = 60;
      const draw = () => {
        const w = canvas.width = canvas.clientWidth * (window.devicePixelRatio || 1);
        const h = canvas.height = canvas.clientHeight * (window.devicePixelRatio || 1);
        ctx.clearRect(0, 0, w, h);
        if (data.length < 2) return;
        const hi = max || Math.max(...data, 1);
        ctx.beginPath();
        data.forEach((v, i) => {
          const x = (i / (N - 1)) * w;
          const y = h - (v / hi) * (h * 0.85) - h * 0.05;
          i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
        });
        ctx.strokeStyle = color; ctx.lineWidth = 1.4 * (window.devicePixelRatio || 1); ctx.stroke();
        ctx.lineTo(w, h); ctx.lineTo(0, h); ctx.closePath();
        const grad = ctx.createLinearGradient(0, 0, 0, h);
        const rgb = color.replace('#', '');
        const n = parseInt(rgb, 16);
        grad.addColorStop(0, `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},.22)`);
        grad.addColorStop(1, 'rgba(0,0,0,0)');
        ctx.fillStyle = grad; ctx.fill();
      };
      draw();
      return {
        push(v) { data.push(v); if (data.length > N) data.shift(); draw(); },
        pushBatch(values) { for (const v of values) data.push(v); while (data.length > N) data.shift(); if (values.length) draw(); },
        redraw() { draw(); },
        destroy() { try { ctx.clearRect(0, 0, canvas.width, canvas.height); } catch (_) {} },
      };
    },
    svcBadge(active, sub) {
      if (active === 'active') return ui.badge(sub || 'active', 'ok');
      if (active === 'failed') return ui.badge('failed', 'bad');
      if (active === 'activating' || active === 'deactivating') return ui.badge(active, 'warn');
      return ui.badge(active || '-', 'gray');
    },
    bar(pct, cls) {
      return `<div class="bar ${cls || fmt.pctColor(pct)}"><div style="width:${Math.min(100, pct || 0)}%"></div></div>`;
    },
    yesno(v, okText, badText) {
      return v ? ui.badge(okText || '是', 'ok') : ui.badge(badText || '否', 'bad');
    },
  };

  Chart.defaults.color = '#7f92a8';
  Chart.defaults.font.family = '-apple-system, "Segoe UI", "PingFang SC", sans-serif';
  Chart.defaults.font.size = 10.5;
})();
