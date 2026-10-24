'use strict';
/*
 * Central path resolution.
 *
 * Everything the app writes to goes through here so the panel can live
 * anywhere: set CM_DATA_DIR (or dataDir in config.json) and the whole tree
 * follows. Defaults next to the code when not running under a package dir.
 */
const fs = require('fs');
const path = require('path');
const { log } = require('./util');

const APP_ROOT = path.resolve(__dirname, '..');

function firstWritable(candidates) {
  for (const dir of candidates) {
    if (!dir) continue;
    try {
      fs.mkdirSync(dir, { recursive: true });
      fs.accessSync(dir, fs.constants.W_OK);
      return dir;
    } catch (_) { /* try next */ }
  }
  return null;
}

/* Resolve (and create) the data root. */
function dataRoot() {
  if (process.env.CM_DATA_DIR) return process.env.CM_DATA_DIR;
  if (global.__CM_PATHS && global.__CM_PATHS.dataDir) return global.__CM_PATHS.dataDir;
  const candidates = [
    '/var/lib/cloud-panel',
    '/opt/cloud-panel',
    path.join(APP_ROOT, 'data'),
  ];
  return firstWritable(candidates) || path.join(APP_ROOT, 'data');
}

const D = dataRoot();

/* Directories. All created lazily on first use. */
function dir(name) { return path.join(D, name); }

const paths = {
  appRoot: APP_ROOT,
  dataDir: D,
  /** state/config lives next to the code unless relocated */
  configDir: process.env.CM_CONFIG_DIR || APP_ROOT,
  logDir: dir('logs'),
  backupDir: dir('backups'),
  tmpDir: dir('tmp'),
  uploadDir: path.join(dir('backups'), 'db', 'uploads'),
  dbBackupDir: path.join(dir('backups'), 'db'),
  aptBackupDir: path.join(dir('backups'), 'apt'),
  pkSearchDir: dir('tmp'),
  ensure() {
    for (const d of [paths.dataDir, paths.logDir, paths.backupDir, paths.tmpDir, paths.dbBackupDir, paths.uploadDir]) {
      try { fs.mkdirSync(d, { recursive: true }); } catch (e) { log('path create failed:', d, e.message); }
    }
    return paths;
  },
};

/* Backwards-compatible aliases used by modules that expect upper-case names */
paths.LOG_DIR = paths.logDir;
paths.BACKUP_DIR = paths.backupDir;
paths.DB_BACKUP_DIR = paths.dbBackupDir;
paths.APT_BACKUP_DIR = paths.aptBackupDir;
paths.UPLOAD_DIR = paths.uploadDir;

module.exports = paths;