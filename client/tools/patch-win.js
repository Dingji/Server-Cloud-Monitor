const fs=require("fs");
const ROOT="D:/Project/ms/ServerMaintance/deploy/cloud-monitor-panel/app/server";
function patch(file, fn){ const p=ROOT+"/"+file; let t=fs.readFileSync(p,"utf8"); t=fn(t); fs.writeFileSync(p,t,"utf8"); }
patch("collectors-win.js", t => {
  const a = "    desc: s.desc || s.unit,\n    enabled: /Automatic/i.test(s.enabled) ? 'enabled' : 'disabled',\n  }));\n  const out = {\n    ts: now,\n    total: list.length,\n    active: list.filter((x) => x.active === 'active').length,\n    failed: list.filter((x) => /failed/i.test(x.active)).length,\n    list,\n  };";
  const b = "    description: s.desc || s.unit,\n    enabled: /Automatic/i.test(s.enabled) ? 'enabled' : 'disabled',\n  }));\n  const out = {\n    ts: now,\n    list,\n    counts: {\n      running: list.filter((x) => x.active === 'active').length,\n      failed: list.filter((x) => /failed/i.test(x.active)).length,\n      total: list.length,\n    },\n  };";
  if (!t.includes(a)) throw new Error("collectors");
  return t.replace(a,b);
});
patch("pkgmgr-win.js", t => {
  const a = "async function summary() {\n  const env = await distro.detect();\n  return {\n    pm: env.pm || 'none',\n    family: env.family,\n    name: env.name,\n    upgradable: 0,\n    installed: 0,\n    mirrors: [],\n    supported: !!env.pm,\n  };\n}";
  const b = "async function summary() {\n  const env = await distro.detect();\n  return {\n    installed_total: 0,\n    installed_active: 0,\n    removed_config: 0,\n    upgradable: 0,\n    held: 0,\n    hold_list: [],\n    index_updated: null,\n    apt_cache_size: null,\n    disk: { line: '', avail: '', total: '', used_pct: '' },\n    pm: env.pm || 'none',\n    family: env.family,\n    name: env.name,\n    mirrors: [],\n    supported: !!env.pm,\n  };\n}";
  if (!t.includes(a)) throw new Error("pkgmgr");
  return t.replace(a,b);
});
console.log("ok");