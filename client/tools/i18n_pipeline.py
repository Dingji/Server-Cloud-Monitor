#!/usr/bin/env python3
"""
Extract UI string literals from composeApp commonMain Kotlin, emit i18n/*.json,
optionally machine-translate, and rewrite sources to use L["key"] / tr("key").
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_ROOT = ROOT / "composeApp" / "src" / "commonMain" / "kotlin" / "com" / "servermaintance" / "panel"
I18N_DIR = ROOT / "composeApp" / "src" / "commonMain" / "resources" / "i18n"
DESKTOP_I18N = ROOT / "composeApp" / "src" / "desktopMain" / "resources" / "i18n"

LOCALES = [
    "zh", "en", "de", "it", "fr", "ru", "es", "pt", "ja", "ko", "vi", "lo", "th", "ms", "hi", "tr", "el", "ar",
]

# Skip paths that are not user-facing UI copy
SKIP_SUBSTRINGS = (
    "/core/Api.kt",
    "NoteMarkdownParser",
    "AnsiScreen",
    "TerminalTheme",
)

# Heuristic: UI string if contains CJK, or is mostly letters/spaces (len>=2), not a route/id
ROUTE_RE = re.compile(r"^[a-z][a-z0-9_/-]*$")
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{3,8}$")

STRING_LIT_RE = re.compile(
    r'(?P<q>"""|"|\'\'\'|\'|")(?P<body>(?:\\.|(?!\1).)*?)(?P=q)',
    re.DOTALL,
)

CALL_PATTERNS = [
    # Text("...")
    (re.compile(r'\bText\s*\(\s*"'), "composable"),
    (re.compile(r"\bText\s*\(\s*'"), "composable"),
    (re.compile(r'\bText\s*\(\s*"""'), "composable"),
    (re.compile(r"\btitle\s*=\s*"), "param"),
    (re.compile(r"\blabel\s*=\s*"), "param"),
    (re.compile(r"\bhint\s*=\s*"), "param"),
    (re.compile(r"\bokText\s*=\s*"), "param"),
    (re.compile(r"\bcancelText\s*=\s*"), "param"),
    (re.compile(r"\bplaceholder\s*=\s*"), "param"),
    (re.compile(r"\bcontentDescription\s*=\s*"), "param"),
    (re.compile(r"\bFormField\.Text\([^,]+,\s*"), "form"),
    (re.compile(r"\bEmptyState\([^,]+,\s*"), "widget"),
    (re.compile(r"\bErrorState\([^,]+,\s*"), "widget"),
    (re.compile(r"\bLoadingState\([^,]+,\s*"), "widget"),
    (re.compile(r"\bPageScaffold\([^,]+,\s*"), "widget"),
    (re.compile(r"\bPanelCard\([^,]+,\s*"), "widget"),
    (re.compile(r"\bSectionTitle\([^,]+,\s*"), "widget"),
    (re.compile(r"\bNavEntry\([^,]+,\s*"), "nav"),
    (re.compile(r'AppState\.toast\(\s*"'), "toast"),
    (re.compile(r"Modal\.prompt\(\s*"), "modal"),
    (re.compile(r"\bLevelBadge\(\s*"), "widget"),
    (re.compile(r"\bStatTile\([^,]+,\s*"), "widget"),
    (re.compile(r"\bDangerBtn\([^,]+,\s*"), "widget"),
    (re.compile(r"\bPrimaryBtn\([^,]+,\s*"), "widget"),
    (re.compile(r"\bGhostBtn\([^,]+,\s*"), "widget"),
    (re.compile(r"\bCheckRow\([^,]+,\s*"), "widget"),
    (re.compile(r"\bField\([^,]+,\s*"), "widget"),
    (re.compile(r"\bSelectField\([^,]+,\s*"), "widget"),
    (re.compile(r"\bSearchField\([^,]+,\s*"), "widget"),
    (re.compile(r'listOf\(\s*"'), "list"),
    (re.compile(r'NAV_SECTIONS.*listOf'), "nav_sections"),
]


def slug_key(text: str) -> str:
    h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:10]
    prefix = re.sub(r"[^\w]+", "_", text[:24].strip().lower(), flags=re.UNICODE)
    prefix = re.sub(r"_+", "_", prefix).strip("_") or "s"
    return f"{prefix}_{h}"


def is_ui_string(s: str) -> bool:
    if not s or s in ("—", "→", "✓", "/", "\\n"):
        return False
    if len(s) == 1 and not re.search(r"[\u4e00-\u9fff]", s):
        return False
    if ROUTE_RE.match(s) and len(s) < 20:
        return False
    if HEX_COLOR_RE.match(s):
        return False
    if s.startswith("http://") or s.startswith("https://"):
        return False
    if re.fullmatch(r"[0-9.]+", s):
        return False
    if re.search(r"[\u4e00-\u9fff]", s):
        return True
    if re.search(r"[A-Za-z]{2,}", s) and " " in s or len(s) >= 4:
        if "/" in s and " " not in s and len(s) < 30:
            return False
        return True
    return False


def decode_kotlin_string(body: str, quote: str) -> str:
    if quote in ('"""', "'''"):
        return body
    out = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\" and i + 1 < len(body):
            n = body[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\", "$": "$"}
            out.append(mapping.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def should_touch_file(path: Path) -> bool:
    rel = str(path.relative_to(KOTLIN_ROOT))
    if any(x in rel for x in SKIP_SUBSTRINGS):
        return False
    return path.suffix == ".kt"


KEY_LIKE = re.compile(r"^[a-z][a-z0-9_.]+$")


def extract_from_file(path: Path, catalog: dict[str, str]) -> None:
    text = path.read_text(encoding="utf-8")
    for m in STRING_LIT_RE.finditer(text):
        body = m.group("body")
        q = m.group("q")
        decoded = decode_kotlin_string(body, q)
        if KEY_LIKE.match(decoded):
            continue
        if not is_ui_string(decoded):
            continue
        before = text[max(0, m.start() - 120) : m.start()]
        in_ui = any(p.search(before) for p, _ in CALL_PATTERNS)
        has_cjk = bool(re.search(r"[\u4e00-\u9fff]", decoded))
        if "${" in decoded or "$" in decoded and "{" in text[m.start():m.end() + 5]:
            continue
        if not in_ui and not has_cjk:
            continue
        if not in_ui and has_cjk and "L[" in before:
            continue
        if re.search(r'\.(str|int|num|arr|bool|obj|long|key)\s*\(\s*$', before):
            continue
        key = catalog.get(decoded)
        if not key:
            key = slug_key(decoded)
            catalog[decoded] = key


def collect_catalog() -> dict[str, str]:
    """text -> key"""
    catalog: dict[str, str] = {}
    for path in sorted(KOTLIN_ROOT.rglob("*.kt")):
        if should_touch_file(path):
            extract_from_file(path, catalog)
    return catalog


SEEDS_PATH = ROOT / "tools" / "i18n_seeds.json"


def write_zh_json(catalog: dict[str, str]) -> dict[str, str]:
    I18N_DIR.mkdir(parents=True, exist_ok=True)
    by_key = {v: k for k, v in catalog.items()}
    if SEEDS_PATH.is_file():
        seeds = json.loads(SEEDS_PATH.read_text(encoding="utf-8"))
        by_key.update(seeds)
    I18N_DIR.joinpath("zh.json").write_text(
        json.dumps(by_key, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return by_key


def translate_all(by_key: dict[str, str]) -> None:
    try:
        from deep_translator import GoogleTranslator
    except ImportError:
        print("deep-translator not installed; copying zh to other locales", file=sys.stderr)
        for loc in LOCALES:
            if loc == "zh":
                continue
            I18N_DIR.joinpath(f"{loc}.json").write_text(
                json.dumps(by_key, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        return

    zh_items = list(by_key.items())
    batch_size = 40
    for loc in LOCALES:
        if loc == "zh":
            continue
        print(f"Translating -> {loc} ({len(zh_items)} keys)...")
        out: dict[str, str] = {}
        translator = GoogleTranslator(source="zh-CN", target=loc if loc != "ms" else "ms")
        for i in range(0, len(zh_items), batch_size):
            batch = zh_items[i : i + batch_size]
            texts = [t for _, t in batch]
            try:
                translated = translator.translate_batch(texts)
            except Exception as e:
                print(f"  batch failed {e}, falling back per-line", file=sys.stderr)
                translated = []
                for t in texts:
                    try:
                        translated.append(translator.translate(t))
                    except Exception:
                        translated.append(t)
            for (k, _orig), tr in zip(batch, translated):
                out[k] = tr or _orig
        I18N_DIR.joinpath(f"{loc}.json").write_text(
            json.dumps(out, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


def escape_kotlin(s: str) -> str:
    return (
        s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("$", "\\$")
    )


def rewrite_file(path: Path, text_to_key: dict[str, str]) -> bool:
    original = path.read_text(encoding="utf-8")
    changed = False
    needs_l = False
    needs_tr = False

    def repl(m: re.Match) -> str:
        nonlocal changed, needs_l, needs_tr
        body = m.group("body")
        q = m.group("q")
        decoded = decode_kotlin_string(body, q)
        key = text_to_key.get(decoded)
        if not key:
            return m.group(0)
        before = original[max(0, m.start() - 80) : m.start()]
        in_composable = any(
            p.search(before) for p, kind in CALL_PATTERNS if kind in ("composable", "param", "widget", "form", "nav", "list", "nav_sections")
        )
        in_toast = "AppState.toast" in before or ".toast(" in before
        if not in_composable and not in_toast:
            if "NavEntry" not in before and "NAV_SECTIONS" not in before:
                return m.group(0)
        changed = True
        if in_toast:
            needs_tr = True
            return f'tr("{key}")'
        needs_l = True
        # preserve string template params as fmt later - skip strings with ${
        if "${" in decoded:
            return m.group(0)
        return f'L["{key}"]'

    new_text = STRING_LIT_RE.sub(repl, original)
    if not changed:
        return False
    imports = []
    if needs_l and "import com.servermaintance.panel.i18n.L" not in new_text:
        imports.append("import com.servermaintance.panel.i18n.L")
    if needs_tr and "import com.servermaintance.panel.i18n.tr" not in new_text:
        imports.append("import com.servermaintance.panel.i18n.tr")
    if imports:
        pkg = re.search(r"^package .+$", new_text, re.M)
        if pkg:
            insert_at = pkg.end()
            new_text = new_text[:insert_at] + "\n" + "\n".join(imports) + new_text[insert_at:]
    path.write_text(new_text, encoding="utf-8")
    return True


def apply_rewrites(text_to_key: dict[str, str]) -> int:
    n = 0
    for path in sorted(KOTLIN_ROOT.rglob("*.kt")):
        if should_touch_file(path):
            if rewrite_file(path, text_to_key):
                n += 1
    return n


def mirror_desktop_resources() -> None:
    """Desktop JVM sometimes reads from desktopMain/resources — keep in sync."""
    if not I18N_DIR.is_dir():
        return
    DESKTOP_I18N.mkdir(parents=True, exist_ok=True)
    for f in I18N_DIR.glob("*.json"):
        DESKTOP_I18N.joinpath(f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")


def main() -> None:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "extract":
        catalog = collect_catalog()
        by_key = write_zh_json(catalog)
        print(f"Extracted {len(by_key)} keys -> {I18N_DIR / 'zh.json'}")
    elif cmd == "translate":
        by_key = json.loads(I18N_DIR.joinpath("zh.json").read_text(encoding="utf-8"))
        translate_all(by_key)
        mirror_desktop_resources()
    elif cmd == "apply":
        by_key = json.loads(I18N_DIR.joinpath("zh.json").read_text(encoding="utf-8"))
        text_to_key = {v: k for k, v in by_key.items()}
        n = apply_rewrites(text_to_key)
        print(f"Rewrote {n} files")
    elif cmd == "all":
        catalog = collect_catalog()
        by_key = write_zh_json(catalog)
        print(f"Extracted {len(by_key)} keys")
        translate_all(by_key)
        mirror_desktop_resources()
        n = apply_rewrites({v: k for k, v in by_key.items()})
        print(f"Rewrote {n} files")
    else:
        print("Usage: i18n_pipeline.py [extract|translate|apply|all]")
        sys.exit(1)


if __name__ == "__main__":
    main()
