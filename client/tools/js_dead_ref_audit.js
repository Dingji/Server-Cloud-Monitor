// Static audit: find call targets that are never defined in this file.
// That is exactly the class of bug the live relay.js had after its 13:06 redesign
// (previewPolicy / savePolicy / togglePolicySwitch were called but their bodies lost).
const fs = require('fs');
const file = process.argv[2] || 'upstream/live-now/relay.js';
const src = fs.readFileSync(file, 'utf8');

const defined = new Set();
for (const m of src.matchAll(/(?:async\s+)?function\s+([A-Za-z_$][\w$]*)/g)) defined.add(m[1]);
for (const m of src.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g)) defined.add(m[1]);
// destructured names count as defined too
for (const m of src.matchAll(/(?:const|let|var)\s*\{([^}]*)\}/g)) {
  m[1].split(',').forEach((p) => {
    const n = p.split(':').pop().trim();
    if (n) defined.add(n);
  });
}
// function parameters (rough but catches the common local helpers)
for (const m of src.matchAll(/(?:function|=>)\s*(?:[A-Za-z_$][\w$]*)?\s*\(([^)]*)\)/g)) {
  m[1].split(',').forEach((p) => {
    const n = p.trim().split(/[=\s]/)[0].replace(/[{}\[\].]/g, '');
    if (n) defined.add(n);
  });
}

const globals = new Set(['if', 'for', 'while', 'switch', 'catch', 'return', 'typeof', 'new', 'delete',
  'void', 'do', 'else', 'await', 'in', 'of', 'case', 'Promise', 'Array', 'Object', 'String', 'Number',
  'Boolean', 'JSON', 'Math', 'Date', 'Map', 'Set', 'WeakMap', 'RegExp', 'Error', 'Function', 'Symbol',
  'Intl', 'encodeURIComponent', 'decodeURIComponent', 'encodeURI', 'decodeURI', 'parseInt', 'parseFloat',
  'isNaN', 'isFinite', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'fetch', 'URL',
  'URLSearchParams', 'structuredClone', 'require', 'module', 'exports', 'process', 'globalThis']);

const hits = new Map();
for (const m of src.matchAll(/(^|[^.\w$])([a-zA-Z_$][\w$]*)\s*\(/g)) {
  const name = m[2];
  if (globals.has(name) || defined.has(name)) continue;
  const line = src.slice(0, m.index).split('\n').length;
  if (!hits.has(name)) hits.set(name, []);
  hits.get(name).push(line);
}

console.log('file: ' + file + '  (' + src.split('\n').length + ' lines)');
if (hits.size === 0) {
  console.log('no undefined-looking call targets');
} else {
  console.log('call targets with no definition in this file:');
  for (const [k, v] of [...hits.entries()].sort()) console.log('  ' + k + ' @ line ' + v.slice(0, 5).join(', '));
}

for (const f of ['previewPolicy', 'savePolicy', 'togglePolicySwitch', 'readPolicyDraft', 'paintCalendar', 'draftDiffers']) {
  const d = (src.match(new RegExp('function ' + f + '\\s*\\(', 'g')) || []).length;
  const u = (src.match(new RegExp('(?<![.\\w$])' + f + '\\s*\\(', 'g')) || []).length;
  console.log('  ' + f + ': defined=' + d + ' occurrences(incl def)=' + u + (d === 0 && u > 0 ? '   <-- DEAD HANDLER' : ''));
}
