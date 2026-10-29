const fs=require("fs");
const ROOT="D:/Project/ms/ServerMaintance/deploy/cloud-monitor-panel/app/server";
let t=fs.readFileSync(ROOT+"/actions-win.js","utf8");
if(!t.includes("resolvePanelPath")){
  t=t.replace("const path = require('path');","const path = require('path');\nconst os = require('os');");
  const frOld="async function fileRead({ path: p }) {\n  const agent = getAgent();\n  const data = await agent.readFileBuffer(p);\n  const text = data.toString('utf8');\n  if (text.includes('\\u0000')) bad('二进制文件不可预览');\n  return { ok: true, path: p, content: text.slice(0, 2 * 1024 * 1024), truncated: text.length > 2 * 1024 * 1024 };\n}";
  const frNew=`function resolvePanelPath(p) {
  const raw = String(p || '').trim();
  if (raw === '/etc/hostname') return { kind: 'hostname' };
  if (raw === '/etc/' || raw === '/etc') {
    return { kind: 'path', path: path.join(process.env.SystemRoot || 'C:\\\\Windows', 'System32', 'drivers', 'etc') };
  }
  if (raw.startsWith('/')) {
    return { kind: 'path', path: path.join('C:\\\\', raw.replace(/^\\//, '').replace(/\\//g, path.sep)) };
  }
  return { kind: 'path', path: raw };
}

async function fileRead({ path: p }) {
  const target = resolvePanelPath(p);
  if (target.kind === 'hostname') {
    const content = os.hostname() + '\\n';
    return { content, size: Buffer.byteLength(content, 'utf8'), mode: '644', owner: 'SYSTEM', mtime: new Date().toISOString() };
  }
  const agent = getAgent();
  const data = await agent.readFileBuffer(target.path);
  const text = data.toString('utf8');
  if (text.includes('\\u0000')) bad('二进制文件不可预览');
  const st = fs.statSync(target.path);
  return { content: text.slice(0, 2 * 1024 * 1024), size: st.size, mode: (st.mode || 0).toString(8).slice(-4), owner: 'SYSTEM', mtime: st.mtime.toISOString() };
}`;
  if(!t.includes(frOld)) throw new Error("fileRead");
  t=t.replace(frOld,frNew);
  const flOld=`async function fileListing({ dir }) {\n  const r = await getAgent().run(\`dir /a "\${String(dir).replace(/"/g, '')}"\`, { timeout: 20000 });\n  return { ok: true, dir, raw: r.stdout, items: [] };\n}`;
  const flNew=`async function fileListing({ dir }) {
  const target = resolvePanelPath(dir || '/etc/');
  const listPath = target.kind === 'path' ? target.path : path.join(process.env.SystemRoot || 'C:\\\\Windows', 'System32', 'drivers', 'etc');
  let names = [];
  try { names = fs.readdirSync(listPath); } catch (e) { bad('目录不存在或不可读：' + listPath, 404); }
  const entries = names.map((name) => {
    const full = path.join(listPath, name);
    let isDir = false, isLink = false;
    try { const st = fs.lstatSync(full); isDir = st.isDirectory(); isLink = st.isSymbolicLink(); } catch (_) {}
    return { mode: isDir ? 'd' : '-', name, isDir, isLink, target: null };
  });
  return { dir: String(dir || '/etc/'), entries };
}`;
  if(!t.includes(flOld)) throw new Error("fileListing");
  t=t.replace(flOld,flNew);
  fs.writeFileSync(ROOT+"/actions-win.js",t,"utf8");
}
const relayWin="'use strict';\nif (process.platform === 'win32') {\n  module.exports = require('./relayops-win');\n  return;\n}\n";
let rt=fs.readFileSync(ROOT+"/relayops.js","utf8");
if(!rt.includes("relayops-win")){
  rt=relayWin+rt.replace(/^'use strict';\n/,"");
  fs.writeFileSync(ROOT+"/relayops.js",rt,"utf8");
}
fs.writeFileSync(ROOT+"/relayops-win.js",`'use strict';
function bad(msg){const e=new Error(msg);e.status=400;throw e;}
function ok(o,x){return Object.assign({ok:true,output:o==null?'':String(o)},x||{});}
async function rateLimit(){return{present:false,zones:[],applied:[],byHour:[],statusCounts:{},errorCount:0,jails:[],conf:'MISSING'};}
async function auditSearch(){return{items:[],total:0,keys:[]};}
async function healthAlerts(){return{available:false,reason:'Relay ops are not deployed on this Windows host',checks:[],summary:{total:0,failing:0,ok:0},failingNames:[],log:[],backup:null,verify:null,dbguard:null,diskguard:null,smtp:{to:[],host:'',port:0,ssl:false,user:'',from:''},timers:[],units:[],sources:{}};}
async function relayBackups(){return{dir:'',items:[]};}
async function alertTestMail(){return ok('N/A on Windows');}
async function relayBackupRun(){return ok('N/A on Windows');}
async function relayBackupDelete(){bad('N/A on Windows');}
function relayBackupPath(){return null;}
module.exports={rateLimit,auditSearch,healthAlerts,relayBackups,alertTestMail,relayBackupRun,relayBackupDelete,relayBackupPath,AUDIT_KEYS:[],RELAY_BACKUP_DIR:''};
`,"utf8");
console.log("actions+relayops ok");