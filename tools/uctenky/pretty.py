"""Čitelný název řádku účtenky — „ROHLÍK43GR" → „Rohlík 43g" (docs/ai.md, „Účtenky").

Výsledek je VÝHRADNĚ pro oči a pro hledání: server ho uloží vedle syrového označení
(`receipt_line.label_display`), ale mapování označení → zboží se dál učí podle syrového
tisku (`label_raw`). Struktura řádku (množství, cena) se tady nečte a nemění.

Postup je deterministický:

1. ruční přepis celého označení ze slovníku (`slovnik/nazvy.tsv`) — `DICT`;
2. jinak pravidla: rozdělení slepených tokenů („BÍLY330G" → „BÍLY 330G"), jednotky
   kompaktně česky („0,5L" → „0,5l", „43GR" → „43g", „3,9%"), zkratky ze slovníku
   (`slovnik/zkratky.tsv`: „SELS." → „Selské") a velikost písmen — `RULES`.

Co pravidla nedořeší (chybějící háčky po OCR, neznámé zkratky), může dočistit lokální model
(ai_pretty.py) — jeho výstup ale projde deterministickou kontrolou (`ai_pretty.accept`).

Popisky, které už mají malá písmena (TAMDA tiskne „Lenor aviváž 1239ml"), obchod napsal
čitelně sám — pravidla na nich mění jen bílé znaky, velikost písmen nechávají.
"""

from __future__ import annotations

import csv
import re
import unicodedata
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

DICT_DIR = Path(__file__).resolve().parent / "slovnik"
ABBREVIATIONS_FILE = "zkratky.tsv"
OVERRIDES_FILE = "nazvy.tsv"
CATEGORIES_FILE = "kategorie.tsv"

SOURCE_DICT = "DICT"
SOURCE_RULES = "RULES"
SOURCE_AI = "AI"

# Jednotky, jak je tiskne pokladna → kompaktní česká podoba. GR je Albertův „gram".
_UNITS = {"G": "g", "GR": "g", "KG": "kg", "ML": "ml", "L": "l", "CL": "cl", "DL": "dl",
          "KS": "ks", "CM": "cm", "MM": "mm", "M": "m", "%": "%"}
# Vodítko gramáže pro párování — jen jednotky, které umí NetContentCalculator na serveru.
_NET_CONTENT_UOM = {"g": "G", "kg": "KG", "ml": "ML", "l": "L"}

_UNIT_ALT = "KG|GR|G|ML|CL|DL|L|KS|CM|MM|M|%"
_NUMBER = r"\d+(?:[.,]\d+)?"
# Násobné balení „8X50ML", „6X" (počet kusů před názvem).
_MULTI_RE = re.compile(rf"^(\d+)[Xx×](?:({_NUMBER})({_UNIT_ALT})?)?$", re.IGNORECASE)
_QUANTITY_RE = re.compile(rf"^({_NUMBER})({_UNIT_ALT})?$", re.IGNORECASE)
# Token: číslo s případnou jednotkou a násobkem, nebo slovo s volitelnou tečkou zkratky.
# Číslo s jednotkou nesmí pokračovat písmenem ani číslicí („100PD" není 100 + „PD" jako
# jednotka, ani „10" + „0"), jen procento smí mít hned za sebou další údaj („3,9%1L").
_TOKEN_RE = re.compile(
    rf"\d+[Xx×](?:{_NUMBER}(?:{_UNIT_ALT})?)?(?!\w)"
    rf"|{_NUMBER}%"
    rf"|{_NUMBER}(?:{_UNIT_ALT})?(?!\w)"
    r"|[^\W\d_]+\.?"
    r"|\d+"
    r"|[^\s\w]",
    re.IGNORECASE,
)
# Znaky, které pokladna přidává za název a nic nenesou („LAHEV PILS.URQ.0,5L*").
_TRAILING_NOISE = "*#"

# Znaky, které unaccent v PostgreSQL rozloží jinak než Unicode NFD (core.norm_label).
_UNACCENT_EXTRA = str.maketrans({"ß": "ss", "ł": "l", "Ł": "L", "ø": "o", "Ø": "O", "đ": "d", "Đ": "D",
                                 "æ": "ae", "Æ": "AE", "œ": "oe", "Œ": "OE"})


def unaccent(text: str) -> str:
    decomposed = unicodedata.normalize("NFD", text.translate(_UNACCENT_EXTRA))
    return "".join(char for char in decomposed if not unicodedata.combining(char))


def norm_label(label: str) -> str:
    """Totéž co `core.norm_label` v databázi — klíč pro ruční přepisy (nazvy.tsv).

    `btrim(core.norm_text(regexp_replace(t, '[^[:alnum:]]+', ' ', 'g')))`, kde norm_text je
    `lower(unaccent(...))` se sloučenými mezerami.
    """
    spaced = re.sub(r"[\W_]+", " ", label)
    return " ".join(unaccent(spaced).lower().split())


def _key(token: str) -> str:
    """Klíč zkratky: bez diakritiky a velikosti písmen, tečka na konci se zachová."""
    return unaccent(token).lower()


@dataclass
class Dictionary:
    """Ruční slovník provozovatele (slovnik/*.tsv, verzovaný v gitu).

    Klíče jsou bez diakritiky a malými písmeny — OCR diakritiku na účtence čte nespolehlivě
    („BORÜVKY", „RÍZKY"), takže zkratka zapsaná s háčkem i bez něj musí znamenat totéž.
    Prázdný řetězec v `chain` = platí pro všechny řetězce; řetězcová položka má přednost.
    """

    abbreviations: dict[tuple[str, str], str] = field(default_factory=dict)
    overrides: dict[tuple[str, str], str] = field(default_factory=dict)
    # Slovo nebo sousloví (klíč jako norm_label) -> slug kategorie appky (ai_classify.py).
    categories: dict[tuple[str, str], str] = field(default_factory=dict)

    def abbreviation(self, token: str, chain: str | None) -> str | None:
        key = _key(token)
        return self.abbreviations.get((key, chain or "")) or self.abbreviations.get((key, ""))

    def override(self, raw_label: str, chain: str | None) -> str | None:
        key = norm_label(raw_label)
        return self.overrides.get((key, chain or "")) or self.overrides.get((key, ""))

    def category(self, label: str, chain: str | None) -> str | None:
        """Slug kategorie podle slovníku — klíč musí v označení stát jako celá slova.

        Řetězcová položka má přednost, mezi stejně specifickými vyhraje delší klíč
        („uni twister" přebije „twister").
        """
        words = norm_label(label).split()
        best: tuple[bool, int, str] | None = None
        for (key, key_chain), slug in self.categories.items():
            if key_chain and key_chain != (chain or ""):
                continue
            key_words = key.split()
            size = len(key_words)
            if not any(words[i:i + size] == key_words for i in range(len(words) - size + 1)):
                continue
            rank = (bool(key_chain), size, slug)
            if best is None or rank[:2] > best[:2]:
                best = rank
        return best[2] if best else None

    def add_category(self, keyword: str, slug: str, chain: str | None = None) -> None:
        self.categories[(norm_label(keyword), chain or "")] = slug

    def add_abbreviation(self, token: str, expansion: str, chain: str | None = None) -> None:
        self.abbreviations[(_key(token), chain or "")] = expansion

    def add_override(self, raw_label: str, pretty: str, chain: str | None = None) -> None:
        self.overrides[(norm_label(raw_label), chain or "")] = pretty


def _read_tsv(path: Path) -> list[list[str]]:
    if not path.exists():
        return []
    rows = []
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.reader(handle, delimiter="\t"):
            if not row or row[0].startswith("#") or not row[0].strip():
                continue
            rows.append([cell.strip() for cell in row])
    return rows


def load_dictionary(directory: Path = DICT_DIR) -> Dictionary:
    dictionary = Dictionary()
    for row in _read_tsv(directory / ABBREVIATIONS_FILE):
        if len(row) >= 2:
            dictionary.add_abbreviation(row[0], row[1], row[2] if len(row) > 2 else None)
    for row in _read_tsv(directory / OVERRIDES_FILE):
        if len(row) >= 2:
            dictionary.overrides[(norm_label(row[0]), row[2] if len(row) > 2 else "")] = row[1]
    for row in _read_tsv(directory / CATEGORIES_FILE):
        if len(row) >= 2 and norm_label(row[0]) and row[1]:
            dictionary.add_category(row[0], row[1], row[2] if len(row) > 2 else None)
    return dictionary


def append_row(path: Path, row: list[str]) -> None:
    """Připíše řádek do TSV slovníku (curate.py) — soubor zůstává čitelný a diffovatelný."""
    path.parent.mkdir(parents=True, exist_ok=True)
    new_file = not path.exists()
    with path.open("a", encoding="utf-8", newline="") as handle:
        if new_file:
            handle.write("# Viz tools/uctenky/README.md, „Čitelné názvy\".\n")
        handle.write("\t".join(cell.replace("\t", " ") for cell in row).rstrip("\t") + "\n")


@dataclass(frozen=True)
class Pretty:
    text: str
    source: str
    # Slova, která pravidla ani slovník nevyřešila — jen u nich má smysl ptát se modelu.
    unresolved: tuple[str, ...] = ()
    # Vodítko gramáže pro párování na serveru: ("340", "G"). Nikdy se nestane hodnotou zboží.
    net_content: tuple[str, str] | None = None


def _format_number(number: str) -> str:
    return number.replace(".", ",")


def _quantity_token(token: str) -> tuple[str, tuple[str, str] | None] | None:
    """Číselný token v kompaktní české podobě a případné vodítko gramáže."""
    multi = _MULTI_RE.match(token)
    if multi:
        count, number, unit = multi.groups()
        text = f"{count}x"
        if number:
            text += _format_number(number) + (_UNITS[unit.upper()] if unit else "")
        # Násobné balení „8x50ml" je 8 kusů po 50 ml a cena na účtence je za celé balení —
        # vodítkem je proto celková gramáž 400 ml, ne gramáž jednoho kusu.
        return text, _net_content_hint(Decimal(count) * Decimal(number.replace(",", ".")),
                                       _UNITS[unit.upper()]) if number and unit else None
    quantity = _QUANTITY_RE.match(token)
    if not quantity:
        return None
    number, unit = quantity.groups()
    if not unit:
        return number, None
    pretty_unit = _UNITS[unit.upper()]
    try:
        hint = _net_content_hint(Decimal(number.replace(",", ".")), pretty_unit)
    except InvalidOperation:
        hint = None
    return _format_number(number) + pretty_unit, hint


def _net_content_hint(value: Decimal, pretty_unit: str) -> tuple[str, str] | None:
    """Vodítko gramáže ("400", "ML") — jen v jednotkách, které umí NetContentCalculator."""
    if pretty_unit not in _NET_CONTENT_UOM:
        return None
    return f"{value.normalize():f}", _NET_CONTENT_UOM[pretty_unit]


def _is_shouting(label: str) -> bool:
    """Tisk velkými písmeny — připouští ojedinělé malé písmeno, které přečetlo OCR („č.B")."""
    letters = [char for char in label if char.isalpha()]
    lower = sum(1 for char in letters if char.islower())
    return bool(letters) and lower * 5 < len(letters)


def _capitalize(text: str) -> str:
    return text[:1].upper() + text[1:] if text else text


def tokenize(label: str) -> list[str]:
    return _TOKEN_RE.findall(label.strip().rstrip(_TRAILING_NOISE).strip())


def pretty_label(raw: str, chain: str | None, dictionary: Dictionary) -> Pretty:
    override = dictionary.override(raw, chain)
    tokens = tokenize(raw)
    hint = None
    for token in tokens:
        quantity = _quantity_token(token)
        if quantity and quantity[1]:
            hint = quantity[1]
    if override:
        return Pretty(override, SOURCE_DICT, (), hint)

    if not _is_shouting(raw):
        # Obchod název napsal čitelně sám — pravidla by ho jen kazila (velikost písmen
        # značek, „59PD"). Sjednotí se jen mezery.
        return Pretty(" ".join(raw.split()), SOURCE_RULES, (), hint)

    words: list[str] = []
    unresolved: list[str] = []
    for token in tokens:
        quantity = _quantity_token(token)
        if quantity:
            words.append(quantity[0])
            continue
        if not token[0].isalpha():
            # Interpunkce mimo zkratku — pomlčka a závorky zůstanou, čárka/tečka zmizí.
            if token in "-()&+/'@":
                words.append(token)
            continue
        expansion = dictionary.abbreviation(token, chain)
        if expansion is not None:
            if expansion:
                words.append(expansion)
            continue
        words.append(token.lower())
        unresolved.append(token)
    text = _capitalize(" ".join(words))
    if not text:
        text = raw.strip()
    return Pretty(text, SOURCE_RULES, tuple(unresolved), hint)
