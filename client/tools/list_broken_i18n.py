import json
from pathlib import Path
zh = json.loads(Path("composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))
broken = [k for k, v in zh.items() if v.strip().startswith(") ?:")]
print(len(broken))
for k in broken:
    print(k, "->", zh[k][:100])
