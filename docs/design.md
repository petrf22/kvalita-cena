# Design UI

Jeden zdroj pravdy pro **rozestupy, typografii, barevné tokeny a stavy obrazovky** napříč webem
a mobilem — stejná role, jakou má `docs/reputace.md` pro prahy reputace: hodnoty žijí tady,
ne rozeseté po CSS souborech a Compose obrazovkách jako vlastní konstanty na dvaceti místech.

Dokument **nevymýšlí nový vzhled**. Popisuje škálu, kterou už appka de facto používá, narovnává
její odchylky a pojmenovává to, co dosud nikde zapsané nebylo — proto se to při psaní UI pokaždé
vymýšlelo znovu.

Co sem NEpatří:

- **Ikona a vizuální identita** → `docs/branding.md` (zdroj pravdy `tools/icons/generate.py`).
- **Formátování čísel, měn a dat** → `docs/lokalizace.md`; na webu to řeší `FormatService` nad
  `Intl.*`, ne CSS, a záměrně se nepoužívají `CurrencyPipe`/`DatePipe`/`DecimalPipe`.
- **Texty a jejich překlady** → `docs/lokalizace.md`.

## Proč tenhle dokument vznikl

Do září 2026 neexistoval žádný designový systém: `frontend/src/styles.css` obsahoval jediný
komentář a `mobile/.../ui/theme/Theme.kt` jedinou hodnotu (`primary = #1677FF`). Web i mobil tak
stály na výchozím vzhledu ng-zorro-antd a Material 3, doplněném ad-hoc hodnotami přímo v CSS
a v Compose — 1489 řádků webového CSS obsahovalo mimo jiné **54 výskytů `rgba(0, 0, 0, 0.45)`**
(a 3 výskyty téhož odstínu zapsaného jako `rgb(0 0 0 / 45%)`).

Praktický důsledek: kdokoli psal novou obrazovku — člověk i AI agent — neměl se čeho chytit
a doplnil generický vzhled. Rozestupy, hierarchie typografie a chování prázdné obrazovky se
do zadání funkce nezapíšou, takže se ztratí. Tenhle dokument je to chybějící zadání.

## Škála rozestupů

Jediná povolená řada, shodná pro web i mobil:

| Krok | Web (`--kc-space-*`) | Mobil (`Spacing`) | Typické použití |
|---|---|---|---|
| 1 | 4 px | `Spacing.xs` | mezera mezi popiskem a hodnotou |
| 2 | 8 px | `Spacing.sm` | mezi prvky uvnitř řádku, `gap` ve `flex`/`Row` |
| 3 | 12 px | `Spacing.md` | mezi souvisejícími bloky formuláře |
| 4 | 16 px | `Spacing.lg` | vnitřní odsazení karty, mezera mezi sekcemi |
| 5 | 24 px | `Spacing.xl` | odsazení stránky, mezera mezi nesouvisejícími bloky |
| 6 | 32 px | `Spacing.xxl` | velké oddělení, prázdný stav |

Řada není zvolená od stolu — vychází z toho, co appka už používá, a to na obou platformách
nezávisle stejně (web: `8px` 69×, `12px` 56×, `16px` 53×, `4px` 35×, `24px` 14×; mobil:
`8.dp` 50×, `16.dp` 46×, `4.dp` 29×, `12.dp` 24×, `24.dp` 12×). Zavedení tokenů proto vzhled
nemění, jen pojmenovává.

**Odchylky k narovnání při doteku souboru**: `6px`/`6.dp` (14× / 6×), `18px`/`18.dp` (6× / 6×),
`20.dp` (15×), `2px`/`2.dp`. Nepřepisují se plošně — viz „Migrace" níž.

Výjimka jsou **rozměry, ne rozestupy**: `88px` u náhledu fotky, `40px` u loga, `260px` u mapy.
Ty do škály nepatří a zůstávají u své komponenty.

## Typografie

**Mobil je referenční platforma.** Používá výhradně `MaterialTheme.typography.*` (`bodySmall`
90×, `bodyMedium` 60×, `titleMedium` 29×, `headlineSmall` 16×) a **nemá ani jedno hardcoded
`.sp`**. Tenhle stav se drží: velikost písma v Compose se zapisuje jen jako role z tématu.

**Web je pozadu** — velikosti jsou rozseté v CSS. Stupnice odpovídající rolím z mobilu:

| Token | Velikost | Role na mobilu | Použití |
|---|---|---|---|
| `--kc-font-xs` | 12 px | `labelSmall` | popisky pod poli, hinty, metadata |
| `--kc-font-sm` | 13 px | `bodySmall` | sekundární text, patička |
| `--kc-font-md` | 14 px | `bodyMedium` | běžný text (výchozí hodnota ng-zorro) |
| `--kc-font-lg` | 16 px | `titleSmall` | zvýrazněná hodnota, cena |
| `--kc-font-xl` | 18 px | `titleMedium` | nadpis sekce |

Ad-hoc `font-size` v CSS komponenty se nepíše — buď token, nebo výchozí velikost ng-zorro.
Dnešní `10px` a `11px` jsou pod hranicí čitelnosti a narovnávají se na `--kc-font-xs`; jediná
vědomá výjimka je `11px` u popisku spodního navigačního menu (`app.css`, `.app-menu-label`),
kde jde o standardní vzor mobilní navigace.

Tučnost: `600` pro nadpis a zvýraznění, `400` pro běžný text. Nic mezi tím (dnešní `500`
se narovná na jedno z těch dvou).

## Barevné tokeny

Definované v `frontend/src/styles.css` v `:root`. Soubor je v `angular.json` uvedený **za**
`ng-zorro-antd.min.css`, takže tokeny platí globálně a v případě potřeby ng-zorro přebijí —
platí i pro inline `styles:` uvnitř komponent (`shared/product-thumb.ts`, `quality-badge.ts`,
`publication-status.ts`), kde část stylů appky žije mimo `.css` soubory.

| Token | Hodnota | Význam |
|---|---|---|
| `--kc-brand` | `#1677FF` | značková modrá (`docs/branding.md`) |
| `--kc-text` | `rgba(0, 0, 0, 0.88)` | základní text |
| `--kc-text-secondary` | `rgba(0, 0, 0, 0.65)` | sekundární text |
| `--kc-text-muted` | `rgba(0, 0, 0, 0.45)` | popisky, hinty, metadata |
| `--kc-border` | `#d9d9d9` | okraj vstupního pole |
| `--kc-border-split` | `#f0f0f0` | dělicí linka, okraj karty |
| `--kc-bg-subtle` | `#fafafa` | podklad odlišené plochy |
| `--kc-danger` | `#ff4d4f` | chyba, destruktivní akce |

Odstín textu se **vždy** zapisuje tokenem, nikdy číselně — `rgba(0, 0, 0, 0.45)` a
`rgb(0 0 0 / 45%)` jsou dnes v repu obojí a znamenají totéž. Barva chyby je `#ff4d4f`; dnešní
jediný výskyt `#f5222d` se narovná.

Na mobilu je zdroj barev `MaterialTheme.colorScheme`, ne vlastní konstanty. Jediná dnešní
výjimka je `StarRating.kt` (`Color(0xFFFAAD14)`) — je vědomá: žlutá hvězdička není role
z Material schématu a v tmavém režimu má vypadat stejně. Webový protějšek token nemá,
protože hvězdičky vykresluje `nz-rate` a upozornění `nzType="warning"` — obojí si barvu
řeší samo uvnitř ng-zorro.

**`#1890ff` z předkompilovaného CSS ng-zorro** je známá nekonzistence popsaná v
`docs/branding.md` — tenhle dokument ji neřeší ani nepřebíjí.

## Dotykové cíle a body zlomu

- **Minimální dotykový cíl je 44 px** (dnes už dodržené u `min-height: 44px`; Material udává 48,
  ng-zorro výchozí tlačítko má 32 — u ovládacích prvků na mobilním rozlišení se cíl zvětšuje
  odsazením, ne zmenšováním písma).
- **Jediný bod zlomu je `max-width: 600px`** — hranice mezi mobilním a širokým rozvržením.
  Dnešní `max-width: 767px` (`search-page.css:85` a inline styly v `shared/product-thumb.ts`,
  kde je vedle toho i `768px`) je odchylka a při doteku se sjednotí.
  Obsah stránky je omezený na `1080px` (`app.css`), což zůstává.

## Čtyři stavy každé obrazovky

Nová obrazovka není hotová, dokud neobslouží **všechny čtyři**. Tohle je kategorie, která
z funkčního zadání systematicky vypadává, a přesně na ní se pozná odbyté UI:

1. **Načítání** — mobil to už umí (`CircularProgressIndicator` v 15 z 19 obrazovek), web přes
   `nz-spin`. Nikdy prázdná bílá plocha bez signálu.
2. **Prázdný stav** — text, který říká, *proč* je prázdno a *co s tím*, ne jen „žádná data".
   Dnes se řeší ad-hoc na každé obrazovce jinak.
3. **Chyba** — text z kontraktu chyb (`docs/lokalizace.md`, `extensions.code`/`params`), ne
   syrová zpráva ze serveru, a **možnost akci zopakovat**.
4. **Offline** — u téhle appky věcný požadavek, ne formalita: ceny se zapisují v obchodě, kde
   signál často není, a datový model s tím počítá (`observed_at` ≠ `created_at`,
   `docs/datovy-model.md`). Obrazovka musí říct, že se zápis odešle později, ne tvářit se
   jako chyba.

Pozor na past popsanou v kořenovém `CLAUDE.md`: **prošlý access token není chyba** — request
doběhne jako anonymní s HTTP 200. Obrazovka, která z anonymní odpovědi udělá prázdný stav,
tím zamlčí, že stačilo obnovit token.

## Tmavý režim

**Vědomá asymetrie: mobil ho má, web ne.**

Mobil běží na `darkColorScheme` (`Theme.kt`) a řídí se systémovým nastavením — každá změna UI
na mobilu se ověřuje v obou režimech.

Web tmavý režim nemá a v této fázi ho nedostane: `angular.json` linkuje předkompilované
`ng-zorro-antd.min.css`, což je světlá varianta, a přepnutí na tmavou znamená výměnu celého
CSS balíku plus revizi všech komponent — samostatné rozhodnutí, ne vedlejší efekt zavedení
tokenů. Tokeny výš jsou ale pojmenované rolí (`--kc-text-muted`), ne hodnotou
(`--kc-black-45`), takže doplnění tmavého režimu bude znamenat přepis `:root`, ne zásah do
dvaceti CSS souborů.

## Migrace existujícího kódu

Hodnoty se **nepřepisují plošně**. 1489 řádků CSS naráz je diff, který nikdo neprojde, a riziko
vizuální regrese je vyšší než užitek.

Platí pravidlo doteku: **kdo mění soubor, narovná v něm hodnoty na tokeny.** Nový kód tokeny
používá od začátku. Agenti `ui-web` a `ui-mobil` (`.claude/agents/`) mají tohle pravidlo
v zadání.
