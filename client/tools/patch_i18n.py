import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRS = [
    ROOT / "composeApp/src/commonMain/resources/i18n",
    ROOT / "composeApp/src/desktopMain/resources/i18n",
]
LOCALES = ["zh","en","de","it","fr","ru","es","pt","ja","ko","vi","lo","th","ms","hi","tr","el","ar"]

DISK_IO = {
    "zh": "{0} 读 · {1} 写",
    "en": "{0} read · {1} write",
    "de": "{0} Lesen · {1} Schreiben",
    "it": "{0} lettura · {1} scrittura",
    "fr": "{0} lecture · {1} écriture",
    "ru": "{0} чтение · {1} запись",
    "es": "{0} lectura · {1} escritura",
    "pt": "{0} leitura · {1} gravação",
    "ja": "{0} 読取 · {1} 書込",
    "ko": "{0} 읽기 · {1} 쓰기",
    "vi": "{0} đọc · {1} ghi",
    "lo": "{0} ອ່ານ · {1} ຂຽນ",
    "th": "{0} อ่าน · {1} เขียน",
    "ms": "{0} baca · {1} tulis",
    "hi": "{0} पढ़ना · {1} लिखना",
    "tr": "{0} okuma · {1} yazma",
    "el": "{0} ανάγνωση · {1} εγγραφή",
    "ar": "{0} قراءة · {1} كتابة",
}

NEW = {
    "settings.panel_url.hint": {
        "zh": "http://主机[:端口][/路径]",
        "en": "http://host[:port][/path]",
        "de": "http://host[:port][/pfad]",
        "it": "http://host[:porta][/percorso]",
        "fr": "http://hôte[:port][/chemin]",
        "ru": "http://хост[:порт][/путь]",
        "es": "http://host[:puerto][/ruta]",
        "pt": "http://host[:porta][/caminho]",
        "ja": "http://ホスト[:ポート][/パス]",
        "ko": "http://호스트[:포트][/경로]",
        "vi": "http://host[:cổng][/đường-dẫn]",
        "lo": "http://host[:port][/path]",
        "th": "http://โฮสต์[:พอร์ต][/เส้นทาง]",
        "ms": "http://hos[:port][/laluan]",
        "hi": "http://होस्ट[:पोर्ट][/पथ]",
        "tr": "http://sunucu[:port][/yol]",
        "el": "http://κ host[:θύρα][/διαδρομή]",
        "ar": "http://المضيف[:المنفذ][/المسار]",
    },
    "ops.uptime.url_label": {
        "zh": "URL（HTTP/HTTPS）",
        "en": "URL (HTTP/HTTPS)",
        "de": "URL (HTTP/HTTPS)",
        "it": "URL (HTTP/HTTPS)",
        "fr": "URL (HTTP/HTTPS)",
        "ru": "URL (HTTP/HTTPS)",
        "es": "URL (HTTP/HTTPS)",
        "pt": "URL (HTTP/HTTPS)",
        "ja": "URL（HTTP/HTTPS）",
        "ko": "URL(HTTP/HTTPS)",
        "vi": "URL (HTTP/HTTPS)",
        "lo": "URL (HTTP/HTTPS)",
        "th": "URL (HTTP/HTTPS)",
        "ms": "URL (HTTP/HTTPS)",
        "hi": "URL (HTTP/HTTPS)",
        "tr": "URL (HTTP/HTTPS)",
        "el": "URL (HTTP/HTTPS)",
        "ar": "URL (HTTP/HTTPS)",
    },
}

for d in DIRS:
    for loc in LOCALES:
        p = d / f"{loc}.json"
        if not p.exists():
            continue
        data = json.loads(p.read_text(encoding="utf-8"))
        if "0_0_读_fmt_bps_b7eb78a0b5" in data:
            data["0_0_读_fmt_bps_b7eb78a0b5"] = DISK_IO.get(loc, DISK_IO["en"])
        for k, per_loc in NEW.items():
            data[k] = per_loc.get(loc, per_loc["en"])
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("patched", p)
