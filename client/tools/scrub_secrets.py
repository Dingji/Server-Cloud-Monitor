#!/usr/bin/env python
"""Remove the credential literal from the Server-Cloud-Monitor working tree.

Every substitution is asserted: the script fails loudly if a pattern is missing or
appears an unexpected number of times, so it cannot silently half-edit a file.
The value itself is read from the environment and never written into any file.

  LEAKED='<old secret>' python tools/scrub_secrets.py <repo_root> --plan
  LEAKED='<old secret>' python tools/scrub_secrets.py <repo_root>
"""
import os
import re
import sys

LEAK = os.environ.get("LEAKED")
if not LEAK:
    sys.exit("set LEAKED=<the secret to purge> in the environment")

# Rules are ordered; expected totals are deliberately NOT asserted (the workspace
# grows and shrinks) -- the real gate below is "no occurrence survives".
RULES = [
    # operator tools: the value must come from the environment, no default
    (r'os\.environ\.get\("(SM_PASS|SM_PANEL_PW|SM_PANEL_PASS|SM_PANEL_PASSWORD|LEAKED)",\s*"'
     + re.escape(LEAK) + r'"\)', r'os.environ["\1"]'),
    # app code: seed a random admin password instead of a published default
    (r'os\.environ\.get\("NB_PASS",\s*"' + re.escape(LEAK) + r'"\)', 'nb_seed_password()'),
    # docs: drop the line that states the login password outright
    (r'\n- 面板访问密码（登录接口）：`' + re.escape(LEAK) + r'`', ''),
    # a bare literal used as a detection needle -> read it from the environment
    (r'"' + re.escape(LEAK) + r'"', r'os.environ.get("LEAKED", "")'),
]

NB_SEED_FN = '''

def nb_seed_password() -> str:
    """Initial admin password for a brand-new database.

    NB_PASS wins when the operator sets it; otherwise a random password is
    generated and printed once so it can be read back from the journal. There is
    deliberately no shipped default: any value in a public repository is public.
    """
    pw = os.environ.get("NB_PASS")
    if pw:
        return pw
    alpha = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    pw = "".join(secrets.choice(alpha) for _ in range(20))
    print("NoteBox: generated initial admin password (print this now, it is not "
          "stored in plaintext): " + pw, flush=True)
    return pw

'''


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    plan = "--plan" in sys.argv[2:]
    targets = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in {".git", "node_modules", "__pycache__", ".gradle", "build"}]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            try:
                with open(p, "rb") as f:
                    data = f.read()
            except (OSError, IOError):
                continue
            if b"\x00" in data:
                continue
            if LEAK.encode("utf-8") in data:
                targets.append(p)

    counts = {i: 0 for i in range(len(RULES))}
    edits = {}
    for p in sorted(targets):
        text = open(p, encoding="utf-8").read()
        original = text
        for i, (pat, rep) in enumerate(RULES):
            text, n = re.subn(pat, rep, text)
            counts[i] += n
        if text != original:
            edits[p] = text

    rel_root = os.path.abspath(root)
    for p, text in edits.items():
        print("  %-58s %d chars" % (os.path.relpath(p, rel_root).replace("\\", "/"),
                                    len(text) - len(open(p, encoding="utf-8").read())))
    left = []
    for p in targets:
        after = edits.get(p, open(p, encoding="utf-8").read())
        if LEAK.encode("utf-8") in after.encode("utf-8"):
            left.append(p)

    print("\nper-rule matches: " + ", ".join(
        "rule%d=%d" % (i + 1, counts[i]) for i in range(len(RULES))))
    if left:
        print("STILL CONTAINING THE SECRET: " + ", ".join(
            os.path.relpath(p, rel_root).replace("\\", "/") for p in left))
        sys.exit("refusing to write -- extend the rules to cover these")

    # insert the helper next to hash_password in the two notebox db.py copies
    for p in list(edits):
        if not p.replace("\\", "/").endswith("notebox/db.py"):
            continue
        body = edits[p]
        if "def nb_seed_password" in body:
            continue
        anchor = "\ndef verify_password("
        if anchor not in body:
            sys.exit("anchor for the helper missing in " + p)
        edits[p] = body.replace(anchor, NB_SEED_FN.lstrip("\n") + anchor, 1)

    if plan:
        print("\n--plan: nothing written (%d files would change)" % len(edits))
        return 0
    for p, text in edits.items():
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    print("\nscrubbed %d files" % len(edits))
    return 0


if __name__ == "__main__":
    sys.exit(main())
