#!/usr/bin/env python3
"""Generate Cloud Monitor app icons (Windows .ico + Android-friendly PNG)."""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
WIN_DIR = ROOT / "composeApp" / "package" / "windows"
TRAY_PNG = ROOT / "composeApp" / "src" / "desktopMain" / "resources" / "cloud-monitor-tray.png"

BG = (12, 17, 23, 255)
PANEL = (14, 20, 27, 255)
ACCENT = (67, 112, 240, 255)
ACCENT2 = (122, 155, 255, 255)
OK = (62, 207, 142, 255)


def _lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def draw_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = max(2, size // 10)
    outer = (m, m, size - m - 1, size - m - 1)
    d.rounded_rectangle(outer, radius=max(4, size // 8), fill=BG)
    stripe_h = max(2, size // 14)
    d.rectangle((m, m, size - m - 1, m + stripe_h), fill=ACCENT)

    inner_m = m + max(3, size // 16)
    inner = (inner_m, inner_m + stripe_h, size - inner_m - 1, size - inner_m - 1)
    d.rounded_rectangle(inner, radius=max(3, size // 10), fill=PANEL)

    w = inner[2] - inner[0]
    h = inner[3] - inner[1]
    x0, y0 = inner[0], inner[1]
    rack_h = max(2, h // 5)
    gap = max(2, h // 12)
    stroke = max(1, size // 32)
    for i in range(2):
        top = y0 + h * 0.22 + i * (rack_h + gap)
        box = (x0 + w * 0.12, top, x0 + w * 0.88, top + rack_h)
        d.rounded_rectangle(box, radius=stroke, outline=ACCENT, width=stroke)
        dot_y = (box[1] + box[3]) / 2
        d.ellipse(
            (x0 + w * 0.18 - stroke, dot_y - stroke, x0 + w * 0.18 + stroke, dot_y + stroke),
            fill=ACCENT2,
        )
        d.line((x0 + w * 0.28, dot_y, x0 + w * 0.78, dot_y), fill=ACCENT2, width=stroke)

    pts = []
    for i in range(7):
        t = i / 6
        px = x0 + w * (0.15 + 0.7 * t)
        py = y0 + h * (_lerp(0.72, 0.42, t) + 0.06 * (1 if i % 2 == 0 else -1))
        pts.append((px, py))
    d.line(pts, fill=ACCENT2, width=max(2, size // 24), joint="curve")

    dot_r = max(2, size // 18)
    cx, cy = x0 + w * 0.84, y0 + h * 0.84
    d.ellipse((cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r), fill=OK)
    return img


def main() -> None:
    WIN_DIR.mkdir(parents=True, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [draw_icon(s) for s in sizes]
    ico_path = WIN_DIR / "CloudMonitor.ico"
    images[-1].save(
        ico_path,
        format="ICO",
        sizes=[(s, s) for s in sizes],
        append_images=images[:-1],
    )
    images[-1].save(WIN_DIR / "CloudMonitor-256.png", format="PNG")
    TRAY_PNG.parent.mkdir(parents=True, exist_ok=True)
    draw_icon(32).save(TRAY_PNG, format="PNG")
    print(f"Wrote {ico_path}")
    print(f"Wrote {TRAY_PNG}")


if __name__ == "__main__":
    main()
