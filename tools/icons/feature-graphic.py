#!/usr/bin/env python3
"""Generátor Play Store feature graphic (1024×500) — docs/vydani.md, "Ikona pro listing".

Záměrně samostatný skript, ne rozšíření `generate.py` — `docs/branding.md` popisuje jen
ikonu appky (geometrie ve `full_parts()`/`compact_parts()`), feature graphic je marketingový
banner s textem navíc, který do stejného "zdroje pravdy pro vizuální identitu" nepatří. Sdílí
ale stejnou geometrii/barvy přes import z `generate.py`, aby se banner a ikona nikdy vizuálně
nerozešly.

Spouští se ručně, není součástí CI: `python3 tools/icons/feature-graphic.py`.
"""
import os
import subprocess
import tempfile

from generate import BLUE, WHITE, CHROME, full_parts, render_svg

ICONS_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_PATH = os.path.join(ICONS_DIR, "play-store-feature-graphic.png")

WIDTH, HEIGHT = 1024, 500


def main():
    icon_svg = render_svg(full_parts(), WHITE)

    html = f'''<!doctype html><html><head><style>
      html, body {{ margin: 0; padding: 0; }}
      body {{
        width: {WIDTH}px; height: {HEIGHT}px;
        background: {BLUE};
        display: flex; align-items: center; justify-content: center;
        gap: 48px;
        font-family: 'DejaVu Sans', Arial, sans-serif;
      }}
      .icon {{ width: 200px; height: 200px; flex-shrink: 0; }}
      .name {{
        color: {WHITE};
        font-size: 74px;
        font-weight: 700;
        white-space: nowrap;
        letter-spacing: -1px;
      }}
    </style></head>
    <body>
      <div class="icon">{icon_svg}</div>
      <div class="name">Kvalita a cena</div>
    </body></html>'''

    with tempfile.TemporaryDirectory() as tmp:
        html_path = os.path.join(tmp, "banner.html")
        png_path = os.path.join(tmp, "banner.png")
        with open(html_path, "w") as f:
            f.write(html)
        subprocess.run([
            CHROME, "--headless", "--disable-gpu", f"--window-size={WIDTH},{HEIGHT}",
            "--default-background-color=ffffff", "--screenshot=" + png_path, html_path,
        ], check=True, capture_output=True)
        os.replace(png_path, OUT_PATH)

    print(f"Zapsáno: {os.path.relpath(OUT_PATH)}")


if __name__ == "__main__":
    main()
