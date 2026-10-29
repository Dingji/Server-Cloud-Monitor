'use strict';
/*
 * Long-running task runner for the PHP module (installs, deploys, backups).
 *
 * Same protocol as the panel's package jobs so the UI can show a progress bar
 * with a live log and re-attach after a page refresh:
 *   POST /php/job       -> { id }
 *   GET  /php/job?id=   -> { active, id, pct, phase, detail, log[], elapsed, error, output }
 *   GET  /php/jobs      -> recent job summaries
 */
const fs = require('fs');
const C = require('./common');
const ex = require('../exec');

const S = C.S;
const INDEX = 'php-jobs.json';
const MAX_KEEP = 60;
const LOG_CAP = 4000;

const state = new Map(); // id -> job

function logDir(id) { return C.BASE() + '/jobs'; }
function logPath(id) { return logDir(id) + '/' + id + '.log'; }

function loadIndex() { return S.readJson(INDEX, []); }
function saveIndex(list) { S.writeJson(INDEX, list.slice(-MAX_KEEP)); }

function summary(j) {
  return {
    id: j.id, kind: j.kind, site: j.site, title: j.title,
    state: j.state, pct: j.pct, phase: j.phase, detail: j.detail,
    startedAt: j.startedAt, endedAt: j.endedAt, error: j.error || null,
  };
}

function start(def) {
  const id = S.genId('phpjob');
  const job = {
    id: id, kind: def.kind || 'task', site: def.site || null, title: def.title || def.kind || '任务',
    state: 'running', pct: 0, phase: 'queued', detail: '',
    startedAt: Date.now(), endedAt: null, error: null, lines: [], tailTimer: null,
  };
  C.mkdirp(logDir(id));
  try { fs.writeFileSync(logPath(id), '', 'utf8'); } catch (_) {}
  state.set(id, job);
  const idx = loadIndex().filter((x) => x.id !== id);
  idx.push(summary(job));
  saveIndex(idx);

  const persist = () => {
    const l = loadIndex().map((x) => (x.id === id ? summary(job) : x));
    saveIndex(l);
  };

  job.set = (pct, phase, detail) => {
    job.pct = Math.max(0, Math.min(100, Math.round(Number(pct) || 0)));
    if (phase) job.phase = String(phase);
    if (detail !== undefined) job.detail = String(detail).slice(0, 300);
    persist();
  };
  job.log = (line, isErr) => {
    const text = String(line == null ? '' : line).replace(/\r$/, '');
    if (!text.trim() && text.indexOf('\n') < 0) return;
    for (const part of text.split('\n')) {
      if (!part) continue;
      job.lines.push(part);
      if (job.lines.length > LOG_CAP) job.lines.splice(0, job.lines.length - LOG_CAP);
      try { fs.appendFileSync(logPath(id), part + '\n', 'utf8'); } catch (_) {}
    }
  };
  /**
   * Run a long command with its output redirected to a file, then tail that file
   * on a timer. The panel's agent only offers a blocking run(), so polling the
   * file is what gives the UI a live log for a 5-minute `apt` or `composer`.
   */
  job.drain = async (cmd, opts) => {
    const o = opts || {};
    const file = '/tmp/panel-php-drain-' + id + '.out';
    const started = Date.now();
    let seen = 0;
    const timer = setInterval(() => {
      let raw = '';
      try { raw = fs.readFileSync(file, 'utf8'); } catch (_) { return; }
      if (raw.length <= seen) return;
      const chunk = raw.slice(seen);
      seen = raw.length;
      job.log(chunk);
      if (o.pctFrom !== undefined && o.pctTo !== undefined) {
        const frac = Math.min(0.98, (Date.now() - started) / Math.max(1000, o.estimateMs || 120000));
        job.set(o.pctFrom + (o.pctTo - o.pctFrom) * frac, o.phase || job.phase);
      }
      const apt = /\(?(?:Reading database|Unpacking|Setting up|Fetched|Get:\s+\d+)/.exec(chunk);
      if (apt) job.detail = apt[0].replace(/[()]/g, '');
    }, 700);
    if (timer.unref) timer.unref();
    try {
      /* `( ... )` so a caller's own redirect still wins: appending
         `> log 2>&1` to `cmd > out` would otherwise retarget out. */
      const r = await ex.run('set -o pipefail; ( ' + cmd + ' ) > ' + S.shq(file) + ' 2>&1', o);
      let tail = '';
      try { tail = fs.readFileSync(file, 'utf8').slice(seen); } catch (_) {}
      if (tail) job.log(tail);
      if (r.timedOut) { const e = new Error('命令超时（' + (o.timeout || 20000) + 'ms）'); e.output = tail; throw e; }
      if (r.code !== 0) {
        const e = new Error(o.failMsg || ('命令失败 (exit ' + r.code + ')'));
        e.output = String(r.stdout || '') + String(r.stderr || '') + tail;
        e.code = r.code;
        throw e;
      }
      return r;
    } finally {
      clearInterval(timer);
      try { fs.rmSync(file, { force: true }); } catch (_) {}
    }
  };
  job.done = (output) => {
    job.state = 'done'; job.pct = 100; job.phase = 'done'; job.endedAt = Date.now();
    job.output = String(output || '').slice(0, 4000);
    persist(); C.logEvent({ kind: job.kind, site: job.site, job: id, result: 'done' });
  };
  job.fail = (err) => {
    job.state = 'failed'; job.phase = 'failed'; job.endedAt = Date.now();
    job.error = String((err && err.message) || err || '未知错误').slice(0, 600);
    job.log('[错误] ' + job.error, true);
    if (err && err.output) job.log(String(err.output).split('\n').slice(-60).join('\n'), true);
    persist(); C.logEvent({ kind: job.kind, site: job.site, job: id, result: 'failed', error: job.error });
  };

  // run detached: the HTTP response already returned the id
  Promise.resolve()
    .then(() => def.run(job))
    .then((out) => { if (job.state === 'running') job.done(out); })
    .catch((e) => { job.fail(e); });

  return job;
}

function view(j, tailLines) {
  const base = {
    active: !!j && j.state === 'running',
    id: j ? j.id : null,
    kind: j && j.kind, site: j && j.site, title: j && j.title,
    pct: j ? j.pct : 0, phase: j ? j.phase : 'idle', detail: j ? j.detail : '',
    state: j ? j.state : 'idle',
    startedAt: j ? j.startedAt : null, endedAt: j ? j.endedAt : null,
    elapsed: j ? (j.endedAt || Date.now()) - j.startedAt : 0,
    error: j ? j.error : null, output: j ? j.output : null,
    logPath: j ? logPath(j.id) : null,
  };
  if (!j) return Object.assign(base, { log: [] });
  let lines = j.lines;
  if (j.state !== 'running' && (!lines || !lines.length)) {
    try { lines = fs.readFileSync(logPath(j.id), 'utf8').split('\n').filter(Boolean); } catch (_) { lines = []; }
  }
  base.log = (lines || []).slice(-(tailLines || 400));
  return base;
}

function latest(site) {
  const idx = loadIndex();
  for (let i = idx.length - 1; i >= 0; i--) {
    const rec = idx[i];
    if (site && rec.site !== site) continue;
    const live = state.get(rec.id);
    if (live) return live;
    if (rec.state !== 'running') {
      return Object.assign({ lines: [] }, rec, {
        id: rec.id, state: rec.state, output: null,
        log: (() => { try { return fs.readFileSync(logPath(rec.id), 'utf8').split('\n').filter(Boolean); } catch (_) { return []; } })(),
      });
    }
  }
  return null;
}

/** Drop finished jobs from memory once they age out; logs stay on disk. */
function prune() {
  const cutoff = Date.now() - 24 * 3600 * 1000;
  for (const [id, j] of state) {
    if (j.state !== 'running' && (j.endedAt || j.startedAt) < cutoff) state.delete(id);
  }
  try {
    const dir = logDir('x');
    for (const f of fs.readdirSync(dir)) {
      const full = dir + '/' + f;
      try { if (fs.statSync(full).mtimeMs < cutoff) fs.rmSync(full, { force: true }); } catch (_) {}
    }
  } catch (_) {}
}

function byId(id) {
  if (!id) return null;
  const live = state.get(String(id));
  if (live) return live;
  const rec = loadIndex().find((x) => x.id === String(id));
  if (!rec) return null;
  let lines = [];
  try { lines = fs.readFileSync(logPath(rec.id), 'utf8').split('\n').filter(Boolean).slice(-LOG_CAP); } catch (_) {}
  return Object.assign({ lines: lines, pct: rec.pct || 0 }, rec);
}

module.exports = { start, view, latest, byId, summary, logPath, INDEX, prune };
