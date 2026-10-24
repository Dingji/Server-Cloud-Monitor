import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DIRS = [ROOT / "composeApp/src/commonMain/resources/i18n", ROOT / "composeApp/src/desktopMain/resources/i18n"]
LOCALES = ["zh","en","de","it","fr","ru","es","pt","ja","ko","vi","lo","th","ms","hi","tr","el","ar"]
FIX = {
    "config_set_n_f64b85737c": {
        "zh": "CONFIG SET {0}",
        "en": "CONFIG SET {0}",
    },
    "common.http_code": {
        "zh": "HTTP {0}",
        "en": "HTTP {0}",
    },
}
for d in DIRS:
    for loc in LOCALES:
        p = d / f"{loc}.json"
        data = json.loads(p.read_text(encoding="utf-8"))
        for k, per in FIX.items():
            data[k] = per.get(loc, per.get("en", per["zh"]))
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("done")
