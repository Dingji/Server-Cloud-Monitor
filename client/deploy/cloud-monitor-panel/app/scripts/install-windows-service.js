'use strict';
const path = require('path');
const { Service } = require('node-windows');

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : dflt;
}

const prefix = path.resolve(arg('prefix', 'D:\\CloudMonitor'));
const dataDir = arg('data', process.env.ProgramData + '\\CloudPanel');
const port = arg('port', '9000');
const bind = arg('bind', '127.0.0.1');
const password = arg('password', '');
const name = arg('name', 'CloudMonitor');

const script = path.join(prefix, 'server', 'index.js');
const env = [
  { name: 'CM_DATA_DIR', value: dataDir },
  { name: 'CM_PORT', value: String(port) },
  { name: 'CM_BIND', value: bind },
];
if (password) env.push({ name: 'CM_PASSWORD', value: password });

const svc = new Service({
  name,
  description: 'Cloud Monitor panel (Node.js)',
  script,
  workingDirectory: prefix,
  env,
  grow: 0.25,
  maxRestarts: 5,
});

svc.on('install', () => {
  console.log('Service installed; starting...');
  svc.start();
});
svc.on('alreadyinstalled', () => {
  console.log('Service already installed; starting...');
  svc.start();
});
svc.on('start', () => console.log(name + ' started'));
svc.on('error', (e) => {
  console.error(e);
  process.exit(1);
});

console.log('Installing service', name, '->', script);
svc.install();