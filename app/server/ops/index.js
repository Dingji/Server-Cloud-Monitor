'use strict';
/*
 * Ops layer entry point. Mounted from the panel's index.js with a single
 * defensive require, so a broken ops module can never stop the panel booting:
 *
 *   try { require('./ops').mount(api, { wrap, audited }); }
 *   catch (e) { logErr('ops modules not mounted:', e.message); }
 */
const S = require('./store');
const notify = require('./notify');
const alerting = require('./alerting');
const uptime = require('./uptime');
const hosts = require('./hosts');
const files = require('./files');
const secops = require('./secops');
const disk = require('./disk');
const remote = require('./remote');
const watchdog = require('./watchdog');
const php = require('./php');

function mount(api, deps) {
  const wrap = (deps && deps.wrap) || ((fn) => async (req, res) => {
    try { await fn(req, res); } catch (e) { res.status(e.status || 500).json({ error: e.message }); }
  });
  const audited = (deps && deps.audited) || ((name, fn) => wrap(async (req, res) => res.json(await fn(req))));

  notify.register(api, wrap, audited);
  alerting.register(api, wrap, audited);
  uptime.register(api, wrap, audited);
  hosts.register(api, wrap, audited);
  files.register(api, wrap, audited);
  secops.register(api, wrap, audited);
  disk.register(api, wrap, audited);
  remote.register(api, wrap, audited);
  watchdog.register(api, wrap, audited);
  php.register(api, wrap);

  api.get('/ops/overview', wrap(async (req, res) => res.json({
    ok: true,
    dataDir: S.root(),
    uptime: uptime.summary(),
    rules: alerting.summary(),
    channels: notify.summary(),
    hosts: hosts.stats(),
    watchdog: watchdog.stats(),
    secops: (() => { const l = secops.latest(); return l ? { at: l.at, score: l.score, grade: l.grade, bad: l.counts.bad, warn: l.counts.warn } : null; })(),
    trash: { items: files.readTrashIndex().items.length, days: files.TRASH_DAYS },
    recentEvents: S.readLines(alerting.EVENTS, 20),
    recentNotify: notify.recent(20),
    uptimeEvents: S.readLines(uptime.EVENTS, 20),
    watchdogEvents: watchdog.events(20),
  })));

  const timers = [];
  if (!(process.env.CM_OPS_DISABLE_SCHEDULERS === '1')) {
    uptime.start(Number(process.env.CM_OPS_PROBE_MS) || 10000);
    alerting.start(Number(process.env.CM_OPS_RULE_MS) || 15000);
    watchdog.start(Number(process.env.CM_OPS_WD_MS) || 30000);
    remote.start();
    disk.start();
    timers.push(setInterval(() => { disk.sampleHistory().catch(() => {}); }, 30 * 60 * 1000));
    if (timers[timers.length - 1].unref) timers[timers.length - 1].unref();
  }

  return {
    shutdown() {
      uptime.stop(); alerting.stop(); watchdog.stop(); remote.stop(); disk.stop();
      for (const t of timers) clearInterval(t);
    },
    notify, alerting, uptime, hosts, files, secops, disk, remote, watchdog,
  };
}

module.exports = Object.assign({ mount }, { notify, alerting, uptime, hosts, files, secops, disk, remote, watchdog, store: S });
