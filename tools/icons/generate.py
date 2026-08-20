#!/usr/bin/env python3
"""Generátor ikony appky (rámeček skeneru s pruhy čárového kódu).

Zdroj pravdy je geometrie definovaná níže v `full_parts()`/`compact_parts()` — souřadnice
v `viewBox 0 0 108 108`, shodně s Android adaptive-icon canvasem (108dp), kde bezpečná zóna
je kruh o poloměru 33 kolem středu (54,54). Ze stejné geometrie se odvozuje jak `.svg` (web),
tak `<vector>` XML (Android) — obojí používá stejnou podmnožinu syntaxe `pathData`, díky čemuž
nejde geometrie mezi platformami rozejít.

Co skript udělá:
  1) zapíše zdrojová SVG (`barcode-icon.svg` plná varianta, `barcode-icon-compact.svg` se
     silnějšími tahy — pro favicon 16/32px a Android monochrome vrstvu),
  2) přes `google-chrome --headless --screenshot` je rasterizuje do PNG v potřebných
     velikostech (žádný ImageMagick/Inkscape/cairosvg v prostředí není),
  3) z PNG poskládá `favicon.ico` (Pillow), zapíše zbylé PNG přímo,
  4) vygeneruje Android vector XML (`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`,
     `ic_logo.xml`) a `ic_launcher_round.xml`,
  5) výstupy zapíše rovnou do `frontend/public/` a `mobile/app/src/main/res/`.

Spouští se ručně, není součástí CI: `python3 tools/icons/generate.py`. Detaily a přehled
výstupů viz `docs/branding.md`.
"""
import os
import subprocess
import tempfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ICONS_DIR = os.path.dirname(os.path.abspath(__file__))
FRONTEND_PUBLIC = os.path.join(ROOT, "frontend", "public")
ANDROID_RES = os.path.join(ROOT, "mobile", "app", "src", "main", "res")

BLUE = "#1677FF"
WHITE = "#FFFFFF"
CHROME = "google-chrome"


# --- pathData buildery (SVG i Android <path android:pathData> chápou stejnou syntaxi) -----

def rounded_rect_path(x, y, w, h, r):
    return (
        f"M {x + r:.2f} {y:.2f} "
        f"H {x + w - r:.2f} "
        f"A {r:.2f} {r:.2f} 0 0 1 {x + w:.2f} {y + r:.2f} "
        f"V {y + h - r:.2f} "
        f"A {r:.2f} {r:.2f} 0 0 1 {x + w - r:.2f} {y + h:.2f} "
        f"H {x + r:.2f} "
        f"A {r:.2f} {r:.2f} 0 0 1 {x:.2f} {y + h - r:.2f} "
        f"V {y + r:.2f} "
        f"A {r:.2f} {r:.2f} 0 0 1 {x + r:.2f} {y:.2f} Z"
    )


def polyline_path(points):
    d = f"M {points[0][0]:.2f} {points[0][1]:.2f} "
    d += " ".join(f"L {x:.2f} {y:.2f}" for x, y in points[1:])
    return d


# --- geometrie ikony -------------------------------------------------------------------
# Motiv: čtyři rohy skenovacího rámečku (jako viewfinder kamery) + pruhy čárového kódu
# uprostřed. Každá "part" je (kind, d, extra), kde kind je 'fill' nebo 'stroke', extra nese
# stroke-width u stroke dílů. Bezpečná zóna Android adaptive icon je kruh o poloměru 33 od
# středu (54,54) — veškerá geometrie (včetně bulge kulatých spojů rohů) do ní musí sedět
# s rezervou, protože ji kruhová/čtvercová maska launcheru jinak ořízne.

CENTER = 54


def corner_paths(half, arm):
    cx = cy = CENTER
    corners = [
        [(cx - half, cy - half + arm), (cx - half, cy - half), (cx - half + arm, cy - half)],
        [(cx + half - arm, cy - half), (cx + half, cy - half), (cx + half, cy - half + arm)],
        [(cx - half, cy + half - arm), (cx - half, cy + half), (cx - half + arm, cy + half)],
        [(cx + half - arm, cy + half), (cx + half, cy + half), (cx + half, cy + half - arm)],
    ]
    return [polyline_path(pts) for pts in corners]


def bars(widths, gap, height):
    total_w = sum(widths) + gap * (len(widths) - 1)
    x = CENTER - total_w / 2
    y = CENTER - height / 2
    paths = []
    for w in widths:
        paths.append(rounded_rect_path(x, y, w, height, min(1.0, w / 2)))
        x += w + gap
    return paths


def full_parts():
    # rám: half=20 → rohové body ve vzdálenosti 20*√2 ≈ 28,3 od středu, + bulge kulatého
    # spoje (stroke 6 → poloměr 3) ≈ 31,3, s rezervou pod poloměrem bezpečné zóny 33.
    parts = [("stroke", d, {"width": 6}) for d in corner_paths(half=20, arm=10)]
    parts += [("fill", d, {}) for d in bars([3, 2, 5, 2, 4, 2, 3], gap=2, height=26)]
    return parts


def compact_parts():
    # Pro 16 px favikonu a Android monochrome vrstvu je kombinace rohů a pruhů matoucí —
    # v malém měřítku se rohy s pruhy slévají do jedné šmouhy (rohový tah 9 sahá téměř
    # k prvnímu pruhu). Kompaktní varianta proto rohy vynechá a zůstanou jen zvětšené,
    # silnější pruhy — čitelné samy o sobě už od 16 px.
    # tři široké pruhy s výrazně větší mezerou než u plné varianty — víc než 3 tenké
    # pruhy se v 16 px slijí do jedné plochy bez rozeznatelné mezery.
    # bezpečná zóna: nejvzdálenější roh (20,20 od středu) je ve vzdálenosti √(20²+20²) ≈ 28,3
    # od středu, pod poloměrem 33.
    return [("fill", d, {}) for d in bars([6, 12, 6], gap=8, height=40)]


# --- SVG render --------------------------------------------------------------------------

def render_svg(parts, color, bg=None):
    def render(kind, d, extra):
        if kind == "fill":
            return f'<path d="{d}" fill="{color}"/>'
        return (f'<path d="{d}" fill="none" stroke="{color}" '
                f'stroke-width="{extra["width"]:.2f}" stroke-linecap="round" stroke-linejoin="round"/>')

    parts_svg = "\n  ".join(render(*p) for p in parts)
    bg_rect = f'<rect width="108" height="108" fill="{bg}"/>' if bg else ""

    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
  {bg_rect}
  {parts_svg}
</svg>
'''


# --- Android vector XML render ------------------------------------------------------------

def render_android_vector(parts, color, size_dp=108):
    def render(kind, d, extra):
        if kind == "fill":
            return f'    <path android:pathData="{d}" android:fillColor="{color}"/>'
        return (f'    <path android:pathData="{d}" android:strokeColor="{color}" '
                f'android:strokeWidth="{extra["width"]:.2f}" android:strokeLineCap="round" '
                f'android:strokeLineJoin="round"/>')

    parts_xml = "\n".join(render(*p) for p in parts)

    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{parts_xml}
</vector>
'''


# --- rasterizace přes headless Chrome ------------------------------------------------------

def rasterize(svg_content, size, transparent=True):
    with tempfile.TemporaryDirectory() as tmp:
        svg_path = os.path.join(tmp, "icon.svg")
        html_path = os.path.join(tmp, "icon.html")
        png_path = os.path.join(tmp, "icon.png")
        with open(svg_path, "w") as f:
            f.write(svg_content)
        with open(html_path, "w") as f:
            f.write(f'''<!doctype html><html><head><style>
              html,body{{margin:0;padding:0;background:transparent;}}
              img{{display:block;width:{size}px;height:{size}px;}}
            </style></head><body><img src="icon.svg"></body></html>''')
        bg = "00000000" if transparent else "ffffff"
        subprocess.run([
            CHROME, "--headless", "--disable-gpu", f"--window-size={size},{size}",
            f"--default-background-color={bg}", "--screenshot=" + png_path, html_path,
        ], check=True, capture_output=True)
        img = Image.open(png_path).convert("RGBA")
        img = img.crop((0, 0, size, size))
        return img


def save_ico(svg_content, sizes, out_path):
    # Pillow's ICO writer generuje menší varianty zmenšením ZE společné base rasterizace
    # (nesmí být menší než největší požadovaná velikost) -- proto rasterizujeme jen jednou,
    # v největší velikosti, a zbytek necháme dopočítat.
    base = rasterize(svg_content, max(sizes), transparent=True)
    base.save(out_path, format="ICO", sizes=[(s, s) for s in sizes])


def save_png(svg_content, size, out_path, transparent=True):
    img = rasterize(svg_content, size, transparent=transparent)
    if not transparent:
        img = img.convert("RGB")
    img.save(out_path)


def main():
    full = full_parts()
    compact = compact_parts()

    svg_full_blue = render_svg(full, BLUE)
    svg_compact_blue = render_svg(compact, BLUE)
    svg_full_white_on_blue = render_svg(full, WHITE, bg=BLUE)

    # 1) zdrojová SVG do tools/icons/
    with open(os.path.join(ICONS_DIR, "barcode-icon.svg"), "w") as f:
        f.write(svg_full_blue)
    with open(os.path.join(ICONS_DIR, "barcode-icon-compact.svg"), "w") as f:
        f.write(svg_compact_blue)
    print("Zapsáno: barcode-icon.svg, barcode-icon-compact.svg")

    # 2) web (frontend/public/)
    os.makedirs(os.path.join(FRONTEND_PUBLIC, "icons"), exist_ok=True)
    with open(os.path.join(FRONTEND_PUBLIC, "favicon.svg"), "w") as f:
        f.write(svg_compact_blue)
    save_ico(svg_compact_blue, [16, 32, 48], os.path.join(FRONTEND_PUBLIC, "favicon.ico"))
    save_png(svg_full_white_on_blue, 180, os.path.join(FRONTEND_PUBLIC, "apple-touch-icon.png"),
              transparent=False)
    save_png(svg_full_white_on_blue, 192, os.path.join(FRONTEND_PUBLIC, "icons", "icon-192.png"),
              transparent=False)
    save_png(svg_full_white_on_blue, 512, os.path.join(FRONTEND_PUBLIC, "icons", "icon-512.png"),
              transparent=False)
    # maskable: stejný motiv, ale zmenšený na 70 %, aby přežil kruhové/čtvercové oříznutí OS
    maskable_full = render_svg(full, WHITE, bg=BLUE).replace(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">',
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
        '<g transform="translate(54 54) scale(0.7) translate(-54 -54)">'
    ).replace('</svg>', '</g></svg>')
    save_png(maskable_full, 512, os.path.join(FRONTEND_PUBLIC, "icons", "icon-512-maskable.png"),
              transparent=False)
    print("Zapsáno: favicon.svg, favicon.ico, apple-touch-icon.png, icons/icon-*.png")

    # 3) Play Store artefakt (mimo Android res/, jde do releasu ručně)
    save_png(svg_full_white_on_blue, 512, os.path.join(ICONS_DIR, "play-store-icon-512.png"),
              transparent=False)
    print("Zapsáno: tools/icons/play-store-icon-512.png")

    # 4) Android vector XML
    drawable = os.path.join(ANDROID_RES, "drawable")
    with open(os.path.join(drawable, "ic_launcher_foreground.xml"), "w") as f:
        f.write(render_android_vector(full, WHITE))
    with open(os.path.join(drawable, "ic_launcher_monochrome.xml"), "w") as f:
        f.write(render_android_vector(compact, WHITE))
    with open(os.path.join(drawable, "ic_logo.xml"), "w") as f:
        f.write(render_android_vector(full, BLUE))
    print("Zapsáno: drawable/ic_launcher_foreground.xml, ic_launcher_monochrome.xml, ic_logo.xml")

    print("Hotovo.")


if __name__ == "__main__":
    main()
