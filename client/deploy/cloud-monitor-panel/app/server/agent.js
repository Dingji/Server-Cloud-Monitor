'use strict';
/*
 * Execution agent: uniform interface for running commands & reading files.
 *  - local mode (production on Linux): child_process + fs (fast, no network hop)
 *  - ssh mode (dev/testing from non-Linux): single multiplexed ssh2 connection
 * Mode "auto": linux => local, otherwise => ssh.
 */
const os = require('os');
const fs = require('fs');
const path = require('path');
const cp = require('child_process');
const config = require('./config');
const { log, logErr } = require('./util');

const REMOTE_PATHS = { '/proc/stat': 1 }; // marker, unused

/* 把字节流切成完整行再回调（兼容 \n / \r\n / \r，apt/dpkg 的进度用 \r 刷新）。
   stdout 与 stderr 各用一个 sink，避免两股流交错时把行拼坏。 */
function makeSink(onLine) {
  let buf = '';
  const SEP = /\r\n|\n|\r/;
  return {
    push(chunk) {
      buf += chunk;
      let m;
      while ((m = SEP.exec(buf))) {
        const line = buf.slice(0, m.index);
        buf = buf.slice(m.index + m[0].length);
        onLine(line);
      }
      /* 单行超长（如 base64 输出）时强制切分，防止无界增长 */
      if (buf.length > 65536) { const l = buf; buf = ''; onLine(l); }
    },
    flush() { if (buf) { const l = buf; buf = ''; onLine(l); } },
  };
}

/* ---------------- local agent ---------------- */
class LocalAgent {
  constructor() { this.mode = 'local'; }
  readFiles(paths) {
    return new Promise((resolve) => {
      const out = {};
      let pending = paths.length;
      if (!pending) return resolve(out);
      for (const p of paths) {
        fs.readFile(p, 'utf8', (err, data) => {
          out[p] = err ? null : data;
          if (--pending === 0) resolve(out);
        });
      }
    });
  }
  writeFile(p, content) {
    return new Promise((resolve, reject) => {
      fs.writeFile(p, content, { mode: 0o600 }, (err) => err ? reject(err) : resolve());
    });
  }
  readFileBuffer(p) {
    return new Promise((resolve, reject) => fs.readFile(p, (err, b) => err ? reject(err) : resolve(b)));
  }
  run(cmd, opts = {}) {
    const timeout = opts.timeout || 15000;
    const maxBuffer = opts.maxBuffer || 16 * 1024 * 1024;
    const env = Object.assign({}, process.env, {
      PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
      LC_ALL: 'C', DEBIAN_FRONTEND: 'noninteractive', NEEDRESTART_MODE: 'a',
    });
    delete env.LD_PRELOAD;
    return new Promise((resolve) => {
      cp.exec(cmd, { shell: '/bin/bash', timeout, maxBuffer, env, killSignal: 'SIGKILL' }, (err, stdout, stderr) => {
        if (err && err.killed) {
          return resolve({ code: -1, stdout: String(stdout || ''), stderr: (String(stderr || '') + `\n[timeout after ${timeout}ms]`).trim(), timedOut: true });
        }
        resolve({ code: err ? (typeof err.code === 'number' ? err.code : 1) : 0, stdout: String(stdout || ''), stderr: String(stderr || ''), timedOut: false });
      });
    });
  }
  /* 流式执行：每产出一行就回调 onLine(line, isStderr)，用于需要实时进度的长命令。
     返回值结构与 run() 一致（code/stdout/stderr/timedOut）。 */
  runStream(cmd, opts = {}) {
    const timeout = opts.timeout || 60000;
    const maxBuffer = opts.maxBuffer || 32 * 1024 * 1024;
    const onLine = typeof opts.onLine === 'function' ? opts.onLine : () => {};
    const env = Object.assign({}, process.env, {
      PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
      LC_ALL: 'C', DEBIAN_FRONTEND: 'noninteractive', NEEDRESTART_MODE: 'a',
      APT_LISTCHANGES_FRONTEND: 'none',
    });
    delete env.LD_PRELOAD;
    return new Promise((resolve) => {
      let proc;
      try { proc = cp.spawn(cmd, { shell: '/bin/bash', env }); }
      catch (e) { return resolve({ code: -1, stdout: '', stderr: String(e.message), timedOut: false }); }
      let stdout = '', stderr = '', timedOut = false, overflow = false;
      const sinkOut = makeSink((l) => { try { onLine(l, false); } catch (_) {} });
      const sinkErr = makeSink((l) => { try { onLine(l, true); } catch (_) {} });
      const timer = setTimeout(() => { timedOut = true; try { proc.kill('SIGKILL'); } catch (_) {} }, timeout);
      proc.stdout.on('data', (d) => {
        const s = String(d);
        if (stdout.length + s.length <= maxBuffer) stdout += s; else overflow = true;
        sinkOut.push(s);
      });
      proc.stderr.on('data', (d) => {
        const s = String(d);
        if (stderr.length + s.length <= maxBuffer) stderr += s;
        sinkErr.push(s);
      });
      proc.on('error', (e) => { clearTimeout(timer); resolve({ code: -1, stdout, stderr: stderr + '\n' + String(e.message), timedOut }); });
      proc.on('close', (code) => {
        clearTimeout(timer);
        sinkOut.flush(); sinkErr.flush();
        if (timedOut) stderr = (stderr + `\n[timeout after ${timeout}ms]`).trim();
        if (overflow) stderr = (stderr + '\n[output truncated: exceeded maxBuffer]').trim();
        resolve({ code: code == null ? -1 : code, stdout, stderr, timedOut });
      });
    });
  }
}

/* ---------------- ssh agent (dev fallback) ---------------- */
class SshAgent {
  constructor() {
    this.mode = 'ssh';
    this.conn = null;
    this.connecting = null;
    this.sftp = null;
    this.failures = 0;
  }
  _connect() {
    if (this.conn && this.conn._sock && !this.conn._sock.destroyed) return Promise.resolve(this.conn);
    if (this.connecting) return this.connecting;
    this.connecting = new Promise((resolve, reject) => {
      const { Client } = require('ssh2');
      const c = new Client();
      const s = config.get().ssh;
      const opts = { host: s.host, port: s.port, username: s.user, readyTimeout: 15000, keepaliveInterval: 10000, keepaliveCountMax: 6 };
      let usedKey = false;
      try {
        if (s.privateKeyFile && fs.existsSync(s.privateKeyFile)) { opts.privateKey = fs.readFileSync(s.privateKeyFile); usedKey = true; }
      } catch (_) {}
      if (!usedKey && s.password) opts.password = s.password;
      c.on('ready', () => {
        this.conn = c; this.connecting = null; this.failures = 0;
        /* sftp 通道是异步建立的：记录其就绪 Promise，
           否则 writeFile/readFileBuffer 可能在通道就绪前被调用而误报 "sftp unavailable" */
        this.sftpReady = new Promise((res) => {
          c.sftp((err, sftp) => {
            if (err) { this.sftp = null; this.sftpErr = err; res(null); }
            else { this.sftp = sftp; this.sftpErr = null; res(sftp); }
          });
        });
        this.sftpReady.then(() => resolve(c));
        c.on('close', () => { this.conn = null; this.sftp = null; this.sftpReady = null; });
        c.on('error', () => { this.conn = null; });
      });
      c.on('error', (e) => { this.connecting = null; this.failures++; reject(e); });
      c.connect(opts);
    });
    return this.connecting;
  }
  async readFiles(paths) {
    const out = {};
    for (const p of paths) out[p] = null;
    try { await this._connect(); } catch (_) { return out; }
    /* Read via cat with markers: SFTP cannot read 0-size virtual files like /proc/* */
    const marker = '___CM_EOF_' + Date.now().toString(36) + '___';
    const script = paths.map((p) => `echo '${marker}:${p}'; cat ${JSON.stringify(p)} 2>/dev/null || true; echo`).join('\n');
    const text = (await this._runScript(script)) || '';
    let cur = null;
    for (const line of text.split('\n')) {
      const m = line.match(new RegExp('^' + marker + ':(.*)$'));
      if (m) { cur = m[1]; out[cur] = ''; continue; }
      if (cur != null) out[cur] += line + '\n';
    }
    for (const k of Object.keys(out)) if (out[k] != null) out[k] = out[k].replace(/\n$/, '');
    return out;
  }
  _runScript(script) {
    return new Promise((resolve) => {
      let conn;
      this._connect().then((c) => {
        conn = c;
        conn.exec('bash -s', (err, stream) => {
          if (err) return resolve(null);
          let out = '';
          const timer = setTimeout(() => { try { stream.close(); } catch (_) {} resolve(out); }, 15000);
          stream.on('data', (d) => { out += d; });
          stream.stderr.on('data', () => {});
          stream.on('close', () => { clearTimeout(timer); resolve(out); });
          stream.end(script);
        });
      }).catch(() => resolve(null));
    });
  }
  async writeFile(p, content) {
    await this._connect();
    /* 等 sftp 通道就绪（_connect 现在会等待，但复连路径仍需兜底） */
    if (this.sftpReady) { try { await this.sftpReady; } catch (_) {} }
    return new Promise((resolve, reject) => {
      if (!this.sftp) return reject(new Error('sftp unavailable'));
      try {
        this.sftp.writeFile(p, Buffer.from(String(content), 'utf8'), (err) => err ? reject(err) : resolve());
      } catch (e) { reject(e); }
    });
  }
  async readFileBuffer(p) {
    await this._connect();
    if (this.sftpReady) { try { await this.sftpReady; } catch (_) {} }
    return new Promise((resolve, reject) => {
      if (!this.sftp) return reject(new Error('sftp unavailable'));
      this.sftp.readFile(p, (err, data) => err ? reject(err) : resolve(data));
    });
  }
  run(cmd, opts = {}) {
    const timeout = opts.timeout || 15000;
    return this._connect().then((conn) => new Promise((resolve) => {
      let done = false;
      const finish = (r) => { if (!done) { done = true; resolve(r); } };
      conn.exec(cmd, { pty: false }, (err, stream) => {
        if (err) return finish({ code: -1, stdout: '', stderr: 'ssh exec error: ' + err.message });
        let stdout = '', stderr = '';
        const timer = setTimeout(() => { try { stream.close(); } catch (_) {} finish({ code: -1, stdout, stderr: stderr + `\n[timeout after ${timeout}ms]`, timedOut: true }); }, timeout);
        stream.on('data', (d) => { stdout += d; if (stdout.length > 32 * 1024 * 1024) stream.close(); });
        stream.stderr.on('data', (d) => { stderr += d; });
        stream.on('close', (code) => { clearTimeout(timer); finish({ code: typeof code === 'number' ? code : 0, stdout, stderr, timedOut: false }); });
      });
    })).catch((e) => ({ code: -1, stdout: '', stderr: 'ssh error: ' + e.message }));
  }
  /* 流式执行（ssh 模式）：与 LocalAgent.runStream 语义一致 */
  runStream(cmd, opts = {}) {
    const timeout = opts.timeout || 60000;
    const maxBuffer = opts.maxBuffer || 32 * 1024 * 1024;
    const onLine = typeof opts.onLine === 'function' ? opts.onLine : () => {};
    return this._connect().then((conn) => new Promise((resolve) => {
      let done = false;
      const finish = (r) => { if (!done) { done = true; resolve(r); } };
      conn.exec(cmd, { pty: false }, (err, stream) => {
        if (err) return finish({ code: -1, stdout: '', stderr: 'ssh exec error: ' + err.message, timedOut: false });
        let stdout = '', stderr = '';
        const sinkOut = makeSink((l) => { try { onLine(l, false); } catch (_) {} });
        const sinkErr = makeSink((l) => { try { onLine(l, true); } catch (_) {} });
        const timer = setTimeout(() => {
          try { stream.close(); } catch (_) {}
          finish({ code: -1, stdout, stderr: (stderr + `\n[timeout after ${timeout}ms]`).trim(), timedOut: true });
        }, timeout);
        stream.on('data', (d) => {
          const s = String(d);
          if (stdout.length + s.length <= maxBuffer) stdout += s;
          sinkOut.push(s);
        });
        stream.stderr.on('data', (d) => {
          const s = String(d);
          if (stderr.length + s.length <= maxBuffer) stderr += s;
          sinkErr.push(s);
        });
        stream.on('close', (code) => {
          clearTimeout(timer);
          sinkOut.flush(); sinkErr.flush();
          finish({ code: typeof code === 'number' ? code : 0, stdout, stderr, timedOut: false });
        });
      });
    })).catch((e) => ({ code: -1, stdout: '', stderr: 'ssh error: ' + e.message, timedOut: false }));
  }
}

/* ---------------- Windows local agent ---------------- */
class WinLocalAgent {
  constructor() { this.mode = 'local'; }
  readFiles(paths) {
    return new Promise((resolve) => {
      const out = {};
      let pending = paths.length;
      if (!pending) return resolve(out);
      for (const p of paths) {
        fs.readFile(p, 'utf8', (err, data) => {
          out[p] = err ? null : data;
          if (--pending === 0) resolve(out);
        });
      }
    });
  }
  writeFile(p, content) {
    return new Promise((resolve, reject) => {
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFile(p, content, { mode: 0o600 }, (err) => err ? reject(err) : resolve());
    });
  }
  readFileBuffer(p) {
    return new Promise((resolve, reject) => fs.readFile(p, (err, b) => err ? reject(err) : resolve(b)));
  }
  run(cmd, opts = {}) {
    const timeout = opts.timeout || 15000;
    const maxBuffer = opts.maxBuffer || 16 * 1024 * 1024;
    const usePs = opts.powershell || opts.ps || /^(\$|Get-|Set-|Write-|Import-|foreach|ForEach-Object)/m.test(String(cmd).trim());
    if (usePs) {
      const { runPowerShell } = require('./hostplatform');
      return runPowerShell(cmd, { timeout, maxBuffer });
    }
    return new Promise((resolve) => {
      cp.exec(cmd, { shell: true, timeout, maxBuffer, windowsHide: true }, (err, stdout, stderr) => {
        if (err && err.killed) {
          return resolve({ code: -1, stdout: String(stdout || ''), stderr: (String(stderr || '') + `\n[timeout after ${timeout}ms]`).trim(), timedOut: true });
        }
        resolve({ code: err && typeof err.code === 'number' ? err.code : (err ? 1 : 0), stdout: String(stdout || ''), stderr: String(stderr || ''), timedOut: false });
      });
    });
  }
  runStream(cmd, opts = {}) {
    const onLine = typeof opts.onLine === 'function' ? opts.onLine : () => {};
    return this.run(cmd, opts).then((r) => {
      for (const line of (r.stdout + '\n' + r.stderr).split(/\r?\n/)) {
        if (line) { try { onLine(line, false); } catch (_) {} }
      }
      return r;
    });
  }
}

let agent = null;
function getAgent() {
  if (agent) return agent;
  const want = process.env.CM_MODE || 'auto';
  const plat = os.platform();
  const useLocal = want === 'local' || (want === 'auto' && (plat === 'linux' || plat === 'win32'));
  if (useLocal && plat === 'win32') agent = new WinLocalAgent();
  else agent = useLocal ? new LocalAgent() : new SshAgent();
  log(`agent mode: ${agent.mode}${plat === 'win32' ? ' (windows)' : ''}`);
  return agent;
}

module.exports = { getAgent, LocalAgent, SshAgent, WinLocalAgent };
