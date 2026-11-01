#!/usr/bin/env python
"""Fix the smoke script: use tar.gz (no `zip` binary on the box) and guard the
empty-release list."""
import io

P = 'tools/php_smoke.py'
s = io.open(P, encoding='utf-8').read()

old = '''    _, mk = ssh(r"""
set -e
d=/tmp/smokev1; rm -rf $d; mkdir -p $d
printf '<?php echo "V1 " . phpversion();' > $d/index.php
printf 'hello' > $d/keep.txt
(cd $d && zip -qr /tmp/smoke-v1.zip .)
d2=/tmp/smokev2; rm -rf $d2; mkdir -p $d2
printf '<?php echo "V2 " . phpversion();' > $d2/index.php
(cd $d2 && zip -qr /tmp/smoke-v2.zip .)
ls -l /tmp/smoke-v1.zip /tmp/smoke-v2.zip
""")'''
new = '''    _, mk = ssh(r"""
set -e
d=/tmp/smokev1; rm -rf $d; mkdir -p $d
printf '<?php echo "V1 " . phpversion();' > $d/index.php
printf 'hello' > $d/keep.txt
tar -czf /tmp/smoke-v1.tar.gz -C $d .
d2=/tmp/smokev2; rm -rf $d2; mkdir -p $d2
printf '<?php echo "V2 " . phpversion();' > $d2/index.php
tar -czf /tmp/smoke-v2.tar.gz -C $d2 .
ls -l /tmp/smoke-v1.tar.gz /tmp/smoke-v2.tar.gz
""")'''
assert s.count(old) == 1
s = s.replace(old, new)

s = s.replace('"path": "/tmp/smoke-v1.zip"', '"path": "/tmp/smoke-v1.tar.gz"')
s = s.replace('"path": "/tmp/smoke-v2.zip"', '"path": "/tmp/smoke-v2.tar.gz"')
s = s.replace('rm -rf /tmp/smokev1 /tmp/smokev2 /tmp/smoke-v1.zip /tmp/smoke-v2.zip',
              'rm -rf /tmp/smokev1 /tmp/smokev2 /tmp/smoke-v1.tar.gz /tmp/smoke-v2.tar.gz')

old2 = '''    rl = api_get("/php/releases", {"slug": slug})
    rel_ids = [r["id"] for r in (rl.get("releases") or [])]
    check("版本历史含两次发布", len(rel_ids) >= 2, "releases=" + ",".join(rel_ids))
    first = next((r["id"] for r in (rl.get("releases") or []) if not r.get("active")), rel_ids[0])
    rb = api_post("/php/rollback", {"slug": slug, "release": first})
    jr = wait_job((rb.get("job") or {}).get("id"))
    check("回滚任务完成", jr.get("state") == "done", str(jr.get("error"))[:200])
    rc, back = ssh("curl -s --max-time 15 http://127.0.0.1:%d/index.php" % port)
    print("     回滚后输出: %s" % back.strip()[:120])
    check("回滚生效（内容回到 v1）", back.strip().startswith("V1") or "V1" in back, back.strip()[:60])
    rc, keep = ssh("curl -s --max-time 15 http://127.0.0.1:%d/keep.txt" % port)
    check("v1 的文件随版本一起回来", keep.strip() == "hello", keep.strip()[:60])'''
new2 = '''    rl = api_get("/php/releases", {"slug": slug})
    rels = rl.get("releases") or []
    rel_ids = [r["id"] for r in rels]
    check("版本历史含两次发布", len(rel_ids) >= 2, "releases=" + ",".join(rel_ids))
    first = next((r["id"] for r in rels if not r.get("active")), None)
    check("找到可回滚的旧版本", bool(first), "releases=" + ",".join(rel_ids))
    if first:
        rb = api_post("/php/rollback", {"slug": slug, "release": first})
        jr = wait_job((rb.get("job") or {}).get("id"))
        check("回滚任务完成", jr.get("state") == "done", str(jr.get("error"))[:200])
        back = ssh("curl -s --max-time 15 http://127.0.0.1:" + str(port) + "/index.php")[1]
        print("     回滚后输出: %s" % back.strip()[:120])
        check("回滚生效（内容回到 v1）", "V1" in back, back.strip()[:60])
        keep = ssh("curl -s --max-time 15 http://127.0.0.1:" + str(port) + "/keep.txt")[1]
        check("v1 的文件随版本一起回来", keep.strip() == "hello", keep.strip()[:60])'''
assert s.count(old2) == 1
s = s.replace(old2, new2)

s = s.replace('''    rc, v1 = ssh("curl -s --max-time 15 http://127.0.0.1:%d/index.php" % port)''',
              '''    rc, v1 = ssh("curl -s --max-time 15 http://127.0.0.1:" + str(port) + "/index.php")''')
s = s.replace('''    rc, v2 = ssh("curl -s --max-time 15 http://127.0.0.1:%d/index.php" % port)''',
              '''    rc, v2 = ssh("curl -s --max-time 15 http://127.0.0.1:" + str(port) + "/index.php")''')

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print("smoke repaired")
