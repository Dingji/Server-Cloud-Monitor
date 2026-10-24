#!/usr/bin/env python3
"""Run safe post-bind_literals repair scripts in order (no bind_literals / no apply_cache)."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STEPS = [
    "i18n_fix_quote_run_damage.py",
    "i18n_fix_string_token_comma.py",
    "i18n_fix_bind_damage.py",
    "i18n_fix_quote_else_l.py",
    "i18n_fix_modal_oktext.py",
    "i18n_fix_listof_merged.py",
    "i18n_fix_stat_tile.py",
    "i18n_fix_formfield_select.py",
    "i18n_fix_misc_syntax.py",
    "i18n_fix_string_token_comma.py",
]


def main() -> None:
    for name in STEPS:
        path = ROOT / name
        if not path.is_file():
            print("skip missing", name)
            continue
        print(">>", name)
        r = subprocess.run([sys.executable, str(path)], cwd=ROOT.parent)
        if r.returncode != 0:
            sys.exit(r.returncode)
    print("done")


if __name__ == "__main__":
    main()
