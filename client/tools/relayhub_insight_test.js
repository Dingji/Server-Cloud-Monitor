'use strict';
/* relayhubmon.js 的洞察计算测试：不起服务、不碰线上，喂两份夹具看结论。
     node tools/relayhub_insight_test.js
夹具的字段形状逐条取自真机 /api/summary 与 /api/port-pool（relayhub-app.py 的
build_summary / port_pool_status），要点：
  · devices[].proxies 是**计数**不是数组（曾按数组遍历直接把整卡算崩）
  · lastCloseTime 是 "MM-DD HH:mm:ss"，不带年份不带时区
  · meta.now 是秒，不是毫秒
  · frps_allowports 是 TOML 原行
  · ufw_rules 是规则数组
  · proxies[] 没有 device 字段，设备名要从 "设备.隧道" 里取
*/
const http = require('http');
const fs = require('fs');
const path = require('path');

const POOL = {
  pool: {
    ranges: [{ label: '段1', start: 36000, end: 36999 }, { label: '段2', start: 12838, end: 12838 }],
    exclude: [], alloc_policy: 'sequential', max_per_client: 16, sync_ufw: true,
    protected: [22, 80, 443, 7000, 7500, 8100, 13345],
  },
  effective: [[12838, 12838], [36000, 36999]],
  ranges_text: '12838, 36000-36999',
  port_count: 1001,
  protected: [22, 80, 443, 7000, 7500, 8100, 13345],
  used: [12838],
  usage_top: [{ port: 12838, count: 3 }],
  conflicts: [],
  ufw_rules: [{ num: 5, spec: '12838/tcp' }, { num: 6, spec: '36000:36999/tcp' },
    { num: 14, spec: '12838/tcp', v6: true }, { num: 15, spec: '36000:36999/tcp', v6: true }],
  frps_allowports: 'allowPorts = [{ start = 12838, end = 12838 }, { start = 36000, end = 36999 }]',
  max_ports_per_client_frps: 16,
  public_addr: '203.0.113.10',
  busy: false,
};

const LOCAL_STAMP = (msAgo) => {
  const d = new Date(Date.now() - msAgo);
  const p = (n) => String(n).padStart(2, '0');
  return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
};

/** 健康态：一台设备、一条在线隧道、探测全通。 */
function healthy() {
  return {
    frps_ok: true, frps_error: '',
    devices: [{ name: 'raspberrypi', status: 'online', proxies: 1, online_proxies: 1, cur_conns: 0,
      today_in: 0, today_out: 275000, rate_in: 0, rate_out: 80, err: '', reachable: 1, probed: 1, avg_latency: 0.2 }],
    proxies: [{ name: 'raspberrypi.ssh', status: 'online', curConns: 0, todayTrafficIn: 0, todayTrafficOut: 275000,
      lastStartTime: LOCAL_STAMP(3600000), lastCloseTime: '', err: '' }],
    probes: { 'raspberrypi.ssh': { ok: true, ms: 0.4, hist: [1, 1] } },
    health: { frps_api: true },
    meta: { now: Math.floor(Date.now() / 1000), frps_token: 'SECRETSHOULDNOTBEOF' },
    history: [],
  };
}

/** 故障态：一条 5 分钟前掉线（抖动）、一条 8 小时前掉线（不算抖动）、一台在线但映射为空、一台离线。 */
function broken() {
  const d = healthy();
  d.proxies = [
    { name: 'pi.ssh', status: 'online', curConns: 3, todayTrafficIn: 100, todayTrafficOut: 200, err: '' },
    { name: 'pi.web', status: 'offline', lastCloseTime: LOCAL_STAMP(5 * 60000), err: 'proxy unavailable' },
    { name: 'nas.backup', status: 'offline', lastCloseTime: LOCAL_STAMP(8 * 3600000), err: '' },
  ];
  d.devices = [
    { name: 'pi', status: 'online', online_proxies: 1, cur_conns: 3, today_in: 100, today_out: 200, rate_in: 0, rate_out: 0, err: '' },
    { name: 'kmp', status: 'online', online_proxies: 0, cur_conns: 0, today_in: 0, today_out: 0, rate_in: 0, rate_out: 0, err: '' },
    { name: 'nas', status: 'offline', online_proxies: 0, cur_conns: 0, today_in: 0, today_out: 0, rate_in: 0, rate_out: 0, err: '' },
  ];
  d.probes = { 'pi.ssh': { ok: true, ms: 12 }, 'pi.web': { ok: false, detail: 'timeout' } };
  return d;
}

let fails = 0;
function check(name, cond, got) {
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (cond ? '' : '  -> ' + JSON.stringify(got)));
  if (!cond) fails++;
}

/**
 * relayhubmon 内部是私有函数，只能按真实代码路径喂：起一个假 relayhub 在
 * 127.0.0.1:8100，让它去 GET /api/summary + /api/port-pool。
 *
 * 服务器只起一次（Node 19+ 的 globalAgent 会复用 keep-alive 套接字，
 * 关掉再开下一轮会把第二次请求打到死连接上，报 ECONNRESET 假故障）；
 * 两相之间靠 require.cache 清空来丢掉模块里的快照缓存。
 */
const state = { summary: healthy(), pool: POOL };
const srv = http.createServer((req, res) => {
  if (state.summary === null) { res.writeHead(500, { 'Content-Type': 'text/plain' }); res.end('relayhub down'); return; }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(req.url.indexOf('port-pool') >= 0 ? state.pool : state.summary));
});

function freshModule() {
  Object.keys(require.cache).forEach((k) => {
    if (k.indexOf('relayhubmon') >= 0 || k.indexOf(path.join('panel-server', 'util')) >= 0) delete require.cache[k];
  });
  return require('../panel-server/relayhubmon');
}

function checkTunnels(d) {
  return (d && d.tunnels) || {};
}

srv.listen(8100, '127.0.0.1', async () => {
  const m = freshModule();
  const d = await m.snapshot();
  check('健康态 ok', d.ok === true, d.error);
  check('端口池容量与百分比', d.pool.count === 1001 && d.pool.used === 1 && d.pool.free === 1000 && d.pool.pct === 0.1, d.pool);
  check('碎片度：两段减去 1 个占用 = 1 段连续、最长 1000', d.pool.fragments === 1 && d.pool.longestFreeRun === 1000 && d.pool.exact === true, d.pool);
  check('最热端口取 usage_top', d.pool.hottest.length === 1 && d.pool.hottest[0].port === 12838, d.pool.hottest);
  check('受保护端口计数', d.pool.protected === 7, d.pool.protected);
  check('漂移：TOML 原行要能解析出两段', d.drift.matches === true && d.drift.missing.length === 0, d.drift);
  check('漂移：ufw_rules 是数组不是数字', d.drift.ufwRules === 4, d.drift.ufwRules);
  check('隧道：在线 1 / 总 1，无报错无抖动', d.tunnels.online === 1 && d.tunnels.total === 1 && d.tunnels.errorCount === 0 && d.tunnels.flapping.length === 0, d.tunnels);
  check('探测：全部可达', d.probes.failedCount === 0 && d.probes.avgMs === 0 && d.probes.covered === 1, d.probes);
  check('告警指标（健康态）', m.metricTunnelDown() === 0 && m.metricPortFree() === 1000 && m.metricPortPct() === 0.1 && m.metricTunnelErrors() === 0,
    [m.metricTunnelDown(), m.metricPortFree(), m.metricPortPct(), m.metricTunnelErrors()]);

  /* 换夹具 + 换模块实例（模块里那份快照缓存要丢掉），重跑一轮 */
  state.summary = broken();
  const m2 = freshModule();
  const d2 = await m2.snapshot();
  const tn = checkTunnels(d2);
  check('故障态：5 分钟前的掉线算抖动、8 小时前的不算', tn.flapping.length === 1 && tn.flapping[0].name === 'pi.web', tn.flapping);
  check('故障态：设备名从隧道名里取（proxies 没有 device 字段）', tn.flapping[0].device === 'pi', tn.flapping[0]);
  check('故障态：隧道报错计数与明细', tn.errorCount === 1 && tn.errors[0].name === 'pi.web', tn.errors);
  check('故障态：离线设备 / 在线但无映射', tn.deviceOffline.join() === 'nas' && tn.emptyTunnels.join() === 'kmp',
    [tn.deviceOffline, tn.emptyTunnels]);
  check('故障态：探测失败明细', d2.probes.failedCount === 1 && d2.probes.failed[0].detail === 'timeout', d2.probes.failed);
  check('告警指标（故障态）跟得上', m2.metricTunnelDown() === 1 && m2.metricTunnelErrors() === 1,
    [m2.metricTunnelDown(), m2.metricTunnelErrors()]);

  /* 中继挂了：宁可不报，也不能拿陈旧快照去触发告警 */
  state.summary = null;
  const m3 = freshModule();
  m3.stopPolling();
  const d3 = await m3.snapshot();
  check('relayhub 不可达时 ok=false 且指标为 null',
    d3.ok === false && m3.metricTunnelDown() === null && m3.metricPortFree() === null,
    [d3.ok, d3.error, m3.metricTunnelDown(), m3.metricPortFree()]);

  srv.close();
  console.log(fails ? '\n失败 ' + fails + ' 项' : '\n全部通过');
  process.exit(fails ? 1 : 0);
});

