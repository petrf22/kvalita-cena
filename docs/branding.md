# Branding

Jeden zdroj pravdy pro vizuální identitu appky — odkud ikona pochází, jak se přegeneruje a kam
všude v repu vede.

## Motiv: rámeček skeneru s pruhy čárového kódu

Symbol **skenovacího rámečku** (čtyři rohy jako u viewfinderu kamery) s **pruhy čárového
kódu** uprostřed — appka je primárně o skenování cenovek, ne abstraktní vážení. Jednobarevně
v `#1677FF` (stejná modrá, kterou appka jinde používá —
`frontend/src/app/shared/location-map.css`, `frontend/src/app/features/product-detail/
price-chart.css`, `mobile/.../values/colors.xml`, `mobile/.../ui/theme/Theme.kt`).
Předkompilovaná CSS ng-zorro-antd používá `#1890ff` — ta nekonzistence existovala už před
ikonou a tenhle dokument ji neřeší.

Dvě velikostní varianty stejné kresby:

- **plná** — čtyři rohy rámečku + sedm pruhů čárového kódu; pro ≥ 48 px (Android launcher,
  apple-touch-icon, PWA ikony, Play Store listing).
- **kompaktní** — jen tři široké pruhy bez rámečku; pro 16/32 px favicon a Android monochrome
  vrstvu (Android 13+ themed icons). Kombinace rohů a pruhů se v malém měřítku slévá do jedné
  šmouhy (ověřeno vizuálně při 16/32 px), proto kompaktní varianta rohy vůbec nekreslí.

Obě varianty jsou nakreslené ve `viewBox 0 0 108 108`, shodně s canvasem Android adaptive
icon (108dp) — veškerá geometrie (včetně bulge kulatých spojů rohů) leží uvnitř bezpečné zóny
(kruh o poloměru 33 od středu 54,54), takže ji nezakrojí žádný tvar launcheru (kruh, čtverec
se zaoblenými rohy, kapka).

## Zdroj pravdy: `tools/icons/generate.py`

Ikona **není** ručně kreslený SVG soubor, který se pak různě konvertuje — zdroj pravdy je
geometrie (souřadnice, tahy) definovaná v `full_parts()`/`compact_parts()` uvnitř
`tools/icons/generate.py`. Ze stejných funkcí se odvozuje jak SVG (`<path d="...">`), tak
Android vector XML (`<path android:pathData="...">`) — obě syntaxe `pathData`/`d` jsou
kompatibilní, takže nehrozí, že se web a mobil vizuálně rozejdou.

Skript se spouští ručně, není součástí CI:

```bash
python3 tools/icons/generate.py
```

Vyžaduje jen `google-chrome` (headless rasterizace SVG → PNG, `--screenshot`) a Pillow
(skládání `favicon.ico`, ukládání PNG) — v prostředí není ImageMagick, Inkscape ani
`cairosvg`, takže se pipeline záměrně obešla bez nich.

### Co skript zapisuje

| Soubor | Varianta | Použití |
|---|---|---|
| `tools/icons/barcode-icon.svg` | plná, modrá | referenční zdroj |
| `tools/icons/barcode-icon-compact.svg` | kompaktní, modrá | referenční zdroj |
| `frontend/public/favicon.svg` | kompaktní, modrá, průhledné pozadí | moderní prohlížeče |
| `frontend/public/favicon.ico` | kompaktní, 16/32/48 | starší prohlížeče, tab |
| `frontend/public/apple-touch-icon.png` | plná, bílá na modré, 180×180 | iOS "Přidat na plochu" |
| `frontend/public/icons/icon-192.png`, `icon-512.png` | plná, bílá na modré | PWA manifest |
| `frontend/public/icons/icon-512-maskable.png` | plná, zmenšená na 70 % | PWA maskable |
| `frontend/public/manifest.webmanifest` | — | odkazuje na ikony výš, `theme_color #1677FF` |
| `mobile/.../drawable/ic_launcher_foreground.xml` | plná, bílá | Android adaptive icon, popředí |
| `mobile/.../drawable/ic_launcher_monochrome.xml` | kompaktní, bílá | Android 13+ themed icons |
| `mobile/.../drawable/ic_logo.xml` | plná, modrá | logo v UI (`ui/about/AboutScreen.kt`) |
| `tools/icons/play-store-icon-512.png` | plná, bílá na modré | Play Store listing (`docs/vydani.md`) |

`ic_launcher_background.xml` a `values/colors.xml` (`#1677FF`) se negenerují a zůstávají beze
změny — jen barevné pozadí, se kterým se popředí skládá.

**Web PWA manifest je jen ikony a barvy** (`display: standalone`, `theme_color`,
`background_color`) — appka nemá a nezakládá service worker ani offline cache; `@angular/pwa`
se nepřidává.

### Kde se ikona ještě ukazuje v UI

- Web: `frontend/src/app/app.html` — `.app-title` v hlavičce (`favicon.svg`, 24 px), na
  mobilním rozlišení (`@media max-width: 600px`) skryté stejně jako dřív celý název appky.
- Mobil: `mobile/.../ui/about/AboutScreen.kt` — `ic_logo` nad nadpisem stránky O aplikaci.

## Když se kresba bude dolaďovat

Uprav parametry v `full_parts()`/`compact_parts()` (`tools/icons/generate.py`) a spusť skript
znovu — přepíše všechny soubory z tabulky výš. Necommituj ručně upravené PNG/ICO/XML bez
odpovídající změny geometrie ve skriptu, jinak se zdroj pravdy a výstup rozejdou.
