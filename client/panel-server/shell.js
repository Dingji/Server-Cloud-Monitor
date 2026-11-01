'use strict';
/*
 * Web terminal: interactive shell sessions.
 * Primary: ssh2 pty to the configured host (key auth, falls back to password).
 * Fallback: local `script -qefc bash` (real pty, no resize), then plain bash.
 * Sessions survive brief client disconnects (grace period + ring buffer).
 */
const { EventEmitter } = require('events');
const fs = require('fs');
const cp = require('child_process');
const config = require('./config');
const { log, logErr } = require('./util');

const RING_MAX = 256 * 1024;
const GRACE_MS = 120 * 1000;

class RingBuffer {
  constructor(max) { this.max = max; this.buf = ''; }
  push(s) { this.buf += s; if (this.buf.length > this.max) this.buf = this.buf.slice(this.buf.length - this.max); }
  drain() { const s = this.buf; this.buf = ''; return s; }
}

class ShellSession extends EventEmitter {
  constructor(id) {
    super();
    this.id = id;
    this.ring = new RingBuffer(RING_MAX);
    this.cols = 120; this.rows = 32;
    this.alive = false;
    this.killed = false;
    this.kind = null;
    this.sshTried = false;
    this._graceTimer = null;
    this._reconnectTimer = null;
    this.start();
  }

  _emitData(d) {
    const s = typeof d === 'string' ? d : d.toString('utf8');
    if (!s) return;
    this.ring.push(s);
    this.emit('data', s);
  }

  _setStatus(state, detail) { this.emit('status', { state, detail: detail || '' }); }

  async start() {
    if (this.killed) return;
    /* 1) ssh pty */
    const ok = await this._startSsh();
    if (ok || this.killed) return;
    /* 2) local script pty */
    if (await this._startLocalPty() || this.killed) return;
    /* 3) plain bash pipes */
    this._startLocalPlain();
  }

  _sshOptions() {
    const s = config.get().ssh;
    const opts = { host: s.host, port: s.port, username: s.user, readyTimeout: 10000, keepaliveInterval: 10000, keepaliveCountMax: 6 };
    let usedKey = false;
    try { if (s.privateKeyFile && fs.existsSync(s.privateKeyFile)) { opts.privateKey = fs.readFileSync(s.privateKeyFile); usedKey = true; } } catch (_) {}
    if (!usedKey && s.password) opts.password = s.password;
    return opts;
  }

  _startSsh() {
    return new Promise((resolve) => {
      if (this.killed) return resolve(false);
      let { Client } = require('ssh2');
      const c = new Client();
      let settled = false;
      const fail = (msg) => {
        if (settled) return; settled = true;
        try { c.end(); } catch (_) {}
        this.sshTried = true;
        logErr(`shell(${this.id}): ssh pty 失败: ${msg}`);
        resolve(false);
      };
      const timer = setTimeout(() => fail('timeout'), 12000);
      c.on('error', (e) => { clearTimeout(timer); fail(e.message); });
      c.on('ready', () => {
        c.shell({ term: 'xterm-256color', cols: this.cols, rows: this.rows }, (err, stream) => {
          clearTimeout(timer);
          if (err) return fail(err.message);
          if (settled) { try { stream.end(); } catch (_) {} return; }
          settled = true;
          this.kind = 'ssh';
          this.alive = true;
          this.conn = c; this.stream = stream;
          this._setStatus('connected', 'SSH PTY (' + config.get().ssh.host + ')');
          stream.on('data', (d) => this._emitData(d));
          stream.stderr.on('data', (d) => this._emitData(d));
          stream.on('close', () => this._onDown('channel closed'));
          c.on('close', () => this._onDown('ssh connection closed'));
          c.on('error', () => this._onDown('ssh error'));
          resolve(true);
        });
      });
      c.connect(this._sshOptions());
    });
  }

  _startLocalPty() {
    return new Promise((resolve) => {
      if (this.killed) return resolve(false);
      const child = cp.spawn('script', ['-qefc', 'bash --login', '/dev/null'], { stdio: ['pipe', 'pipe', 'pipe'], env: process.env });
      let ok = false;
      child.on('error', (e) => { logErr(`shell(${this.id}): script pty 不可用: ${e.message}`); resolve(false); });
      child.stdout.on('data', (d) => { ok = true; this._emitData(d); });
      child.stderr.on('data', (d) => this._emitData(d));
      child.on('exit', () => this._onDown('local pty exited'));
      setTimeout(() => {
        if (ok) {
          this.kind = 'local-pty'; this.alive = true; this.child = child;
          this._setStatus('connected', '本地 PTY (script)');
          resolve(true);
        } else if (!this.alive) {
          try { child.kill('SIGKILL'); } catch (_) {}
          resolve(false);
        }
      }, 700);
    });
  }

  _startLocalPlain() {
    const child = cp.spawn('bash', ['--login', '-i'], { stdio: ['pipe', 'pipe', 'pipe'], env: process.env });
    child.on('error', (e) => { this._emitData(`\r\n\x1b[31m无法启动 shell: ${e.message}\x1b[0m\r\n`); this._setStatus('closed', e.message); });
    child.stdout.on('data', (d) => this._emitData(d));
    child.stderr.on('data', (d) => this._emitData(d));
    child.on('exit', () => this._onDown('shell exited'));
    this.kind = 'local-plain'; this.alive = true; this.child = child;
    this._setStatus('connected', '本地 Shell (无 PTY，TUI 应用可能显示异常)');
  }

  _onDown(reason) {
    if (!this.alive && this._reconnectTimer) return;
    this.alive = false;
    this.stream = null; this.conn = null;
    if (this.killed) return;
    this._setStatus('reconnecting', reason + '，3 秒后重连…');
    clearTimeout(this._reconnectTimer);
    this._reconnectTimer = setTimeout(() => { this.sshTried = false; this.start(); }, 3000);
  }

  write(data) {
    try {
      if (this.kind === 'ssh' && this.stream) this.stream.write(data);
      else if (this.child && this.child.stdin && !this.child.stdin.destroyed) this.child.stdin.write(data);
    } catch (e) { logErr('shell write error:', e.message); }
  }

  resize(cols, rows) {
    cols = Math.max(20, Math.min(500, parseInt(cols, 10) || this.cols));
    rows = Math.max(5, Math.min(200, parseInt(rows, 10) || this.rows));
    this.cols = cols; this.rows = rows;
    if (this.kind === 'ssh' && this.stream) { try { this.stream.setWindow(rows, cols, 0, 0); } catch (_) {} }
  }

  attach() {
    const tail = this.ring.drain();
    return tail;
  }

  detach() {
    clearTimeout(this._graceTimer);
    this._graceTimer = setTimeout(() => this.kill(), GRACE_MS);
  }

  kill() {
    this.killed = true;
    clearTimeout(this._graceTimer); clearTimeout(this._reconnectTimer);
    try { if (this.stream) this.stream.end(); } catch (_) {}
    try { if (this.conn) this.conn.end(); } catch (_) {}
    try { if (this.child) this.child.kill('SIGKILL'); } catch (_) {}
    this.alive = false;
    this.emit('killed');
  }
}

/* ============ session manager ============ */
const sessions = new Map(); // clientId -> ShellSession
const MAX_SESSIONS = 8;

function getSession(clientId) {
  if (!/^[a-zA-Z0-9_-]{8,64}$/.test(String(clientId || ''))) return null;
  let s = sessions.get(clientId);
  if (s) { clearTimeout(s._graceTimer); s._graceTimer = null; return s; }
  if (sessions.size >= MAX_SESSIONS) {
    /* evict oldest idle */
    const first = sessions.keys().next().value;
    const old = sessions.get(first);
    if (old) old.kill();
    sessions.delete(first);
  }
  s = new ShellSession(clientId);
  s.on('killed', () => sessions.delete(clientId));
  sessions.set(clientId, s);
  return s;
}

function killAll() { for (const s of sessions.values()) s.kill(); sessions.clear(); }

module.exports = { getSession, killAll };
