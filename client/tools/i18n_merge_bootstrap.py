#!/usr/bin/env python3
"""Merge tools/i18n_bootstrap_<tag>.json into locale file and mirror desktop."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
DESKTOP = ROOT / "composeApp/src/desktopMain/resources/i18n"


def main() -> None:
    tag = sys.argv[1] if len(sys.argv) > 1 else "en"
    boot = ROOT / "tools" / f"i18n_bootstrap_{tag}.json"
    if not boot.is_file():
        print("no bootstrap", boot)
        return
    boot_data = json.loads(boot.read_text(encoding="utf-8"))
    data = json.loads((I18N / f"{tag}.json").read_text(encoding="utf-8"))
    data.update(boot_data)
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    (I18N / f"{tag}.json").write_text(text, encoding="utf-8")
    DESKTOP.mkdir(parents=True, exist_ok=True)
    (DESKTOP / f"{tag}.json").write_text(text, encoding="utf-8")
    print(f"merged {len(boot_data)} keys into {tag}.json")


if __name__ == "__main__":
    main()
