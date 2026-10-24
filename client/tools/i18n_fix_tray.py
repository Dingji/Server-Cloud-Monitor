#!/usr/bin/env python3
"""Tray menu labels: const Chinese -> tr() so the menu follows the active locale
(the popup is rebuilt on every open, so the change takes effect immediately)."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"
LOCALES = ["zh", "en", "de", "it", "fr", "ru", "es", "pt", "ja", "ko", "vi", "lo", "th", "ms", "hi", "tr", "el", "ar"]
TRAY = ROOT / "composeApp/src/desktopMain/kotlin/com/servermaintance/panel/window/WindowsTrayHost.kt"

VALUES = {"tray.show_main": "显示主窗口", "tray.quit": "退出"}

TR = {
    "en": {"tray.show_main": "Show main window", "tray.quit": "Quit"},
    "de": {"tray.show_main": "Hauptfenster anzeigen", "tray.quit": "Beenden"},
    "it": {"tray.show_main": "Mostra finestra principale", "tray.quit": "Esci"},
    "fr": {"tray.show_main": "Afficher la fenêtre principale", "tray.quit": "Quitter"},
    "ru": {"tray.show_main": "Показать главное окно", "tray.quit": "Выход"},
    "es": {"tray.show_main": "Mostrar ventana principal", "tray.quit": "Salir"},
    "pt": {"tray.show_main": "Mostrar janela principal", "tray.quit": "Sair"},
    "ja": {"tray.show_main": "メインウィンドウを表示", "tray.quit": "終了"},
    "ko": {"tray.show_main": "메인 창 표시", "tray.quit": "종료"},
    "vi": {"tray.show_main": "Hiển thị cửa sổ chính", "tray.quit": "Thoát"},
    "lo": {"tray.show_main": "ສະແດງຫນ້າຕ່າງຫລັກ", "tray.quit": "ອອກ"},
    "th": {"tray.show_main": "แสดงหน้าต่างหลัก", "tray.quit": "ออก"},
    "ms": {"tray.show_main": "Tetingkap utama", "tray.quit": "Keluar"},
    "hi": {"tray.show_main": "मुख्य विंडो दिखाएँ", "tray.quit": "बाहर निकलें"},
    "tr": {"tray.show_main": "Ana pencereyi göster", "tray.quit": "Çıkış"},
    "el": {"tray.show_main": "Εμφάνιση κύριου παραθύρου", "tray.quit": "Έξοδος"},
    "ar": {"tray.show_main": "إظهار النافذة الرئيسية", "tray.quit": "خروج"},
}


def main() -> None:
    zh_path = I18N / "zh.json"
    zh = json.loads(zh_path.read_text(encoding="utf-8"))
    for key, val in VALUES.items():
        zh.setdefault(key, val)
    zh_path.write_text(json.dumps(zh, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for tag in LOCALES:
        p = I18N / f"{tag}.json"
        data = json.loads(p.read_text(encoding="utf-8"))
        for key, val in VALUES.items():
            data.setdefault(key, TR.get(tag, {}).get(key, val))
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    t = TRAY.read_text(encoding="utf-8")
    t = t.replace(
        'private const val TRAY_MENU_SHOW = "\\u663E\\u793A\\u4E3B\\u7A97\\u53E3"\n',
        "",
    )
    t = t.replace('private const val TRAY_MENU_QUIT = "\\u9000\\u51FA"\n', "")
    t = t.replace("JMenuItem(TRAY_MENU_SHOW,", 'JMenuItem(tr("tray.show_main"),')
    t = t.replace("JMenuItem(TRAY_MENU_QUIT,", 'JMenuItem(tr("tray.quit"),')
    if "import com.servermaintance.panel.i18n.tr" not in t:
        t = t.replace(
            "import javax.swing.JPopupMenu",
            "import com.servermaintance.panel.i18n.tr\nimport javax.swing.JPopupMenu",
        )
    TRAY.write_text(t, encoding="utf-8")
    print("tray updated; remaining const refs:", t.count("TRAY_MENU_"))

    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    print("locales synced")


if __name__ == "__main__":
    main()
