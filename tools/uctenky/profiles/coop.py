"""Profil účtenky prodejen COOP (spotřební družstva, CZ).

Tvar účtenky (COOP Hořovice, prodejna formátu TIP):

    COOP Hořovice družstvo,                        <- družstvo, ne prodejna
    Palackého nám. 200 / 26840 Hořovice            <- adresa SÍDLA družstva
    IČO: 00031747 DIČ: CZ00031747                  <- stejné pro všechny prodejny družstva
    PJ:034 Prodejna 34 Hrádek TIP                  <- číslo, název a formát prodejny
    Daňový doklad č. … / 16.10.2026 14:02:32       <- datum je v HLAVIČCE
    SD Zboží Množství Kč/MJ Cena Kč                <- OCR čte „%D"
    12 Dušená šunka výběrová 57,08                 <- sazba DPH, název, cena řádku
    váhový kód: 2804987003027                      <- jen u váhového zboží, NEposílá se
    0,302 kg x 189,00 Kč/kg                        <- množství POD názvem (Penny NAD ním)
    Celkem: 595,18                                 <- konec bloku položek
    … daňový rozpis, platba …
    COOP Hořovice,družstvo / Coop Hrádek 034       <- blok platebního terminálu: adresa
    Hrádek 175/II / 33842 Hrádek Nová H / IČO      <- PRODEJNY (jen při platbě kartou)

Proč vlastní rozbor položek (Profile.parse_items): obecné skládání (lines.py) bere řádek
s množstvím jako řádek NAD názvem (Penny), takže by ho přilepilo k NÁSLEDUJÍCÍ položce —
kelímek jogurtu by dostal 189 Kč/kg ze šunky. Kontrolní součet to neodhalí, ceny řádků
sedí. Tady množství patří vždy k položce nad ním a musí platit množství × cena za kus =
cena řádku.

Proč adresa z patičky: hlavička nese sídlo družstva a IČO sdílí všechny jeho prodejny,
takže server by podle hlavičky prodejnu nikdy nevybral (ReceiptMatchingService.suggestStore).
Z bloku terminálu se bere JEN název prodejny, ulice, PSČ a město — údaje prodejce, ne
kupujícího (docs/soukromi.md, „Účtenky"). Řádky s kartou, terminálem a autorizací ne.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal

import lines as lines_mod
from normalize import AMOUNT, is_sensitive, parse_amount
from profiles import generic
from profiles.base import Footer, Profile, ReceiptHeader

COUNTRY = "CZ"
CURRENCY = "CZK"
HEADER_LINES = 12
# Tolerance „množství × cena za kus = cena řádku"; u váhového zboží pokladna zaokrouhluje.
LINE_TOLERANCE = Decimal("0.01")
# Kolik řádků za názvem družstva v bloku terminálu se hledá adresa prodejny.
STORE_BLOCK_LINES = 5
# Formát prodejny z řádku „PJ:" → slug řetězce (db/changelog/2026-08-29/03-retail-chain-seed.yaml).
FORMAT_TO_CHAIN = {
    "TIP": "coop-tip",
    "TUTY": "coop-tuty",
    "TERNO": "coop-terno",
    "DISKONT": "coop-diskont",
    "JEDNOTA": "coop-jednota",
}

_COOP_RE = re.compile(r"\bcoop\b", re.IGNORECASE)
_COOP_COMPANY_RE = re.compile(r"^coop\b.*dru[žz]stvo", re.IGNORECASE)
# „SD Zboží Množství Kč/MJ Cena Kč" — OCR čte první sloupec jako „%D", „5D"…
_COLUMN_HEADER_RE = re.compile(r"^\S{1,2}\s+zbo[žz][ií]\s+mno[žz]stv[ií]", re.IGNORECASE)
_STORE_RE = re.compile(r"^PJ\s*:\s*\d+\s+(?P<store>.+?)(?:\s+(?P<format>[A-Z]{3,}))?\s*$")
_TOTAL_RE = re.compile(rf"^celkem\b\D*(?P<total>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)
_POSTAL_CITY_RE = re.compile(r"^(?P<postal>\d{3}\s?\d{2})\s+(?P<city>\D.*)$")
_BLOCK_END_RE = re.compile(r"^(?:I[ČC]O?|DI[ČC]|termin[aá]l)\b", re.IGNORECASE)

# Sazby DPH v ČR (včetně dřívějších). Bez sazby se řádek bere taky — OCR ji občas ztratí.
_VAT = r"0|10|12|15|21"
ITEM_HEAD_RE = re.compile(rf"^(?:(?P<vat>{_VAT})\s+)?(?P<name>.+?)\s+(?P<total>{AMOUNT})\s*$")
WEIGHT_CODE_RE = re.compile(r"^v[aá]hov[ýy]\s*k[oó]d\s*:?\s*(?P<code>\d{8,14})\s*$", re.IGNORECASE)


def matches(raw_lines: list[str]) -> bool:
    return any(_COOP_RE.search(line) for line in raw_lines[:HEADER_LINES])


def _items_start(raw_lines: list[str]) -> int:
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        if _COLUMN_HEADER_RE.match(line):
            return index + 1
    # Hlavičku sloupců OCR vynechalo — položky začínají prvním řádkem se sazbou a cenou.
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        head = ITEM_HEAD_RE.match(line)
        if head and head["vat"] is not None:
            return index
    return min(len(raw_lines), HEADER_LINES)


def _total_index(raw_lines: list[str]) -> int | None:
    for index in range(_items_start(raw_lines), len(raw_lines)):
        if _TOTAL_RE.match(raw_lines[index]):
            return index
    return None


def _store_block(footer: list[str]) -> tuple[str | None, str | None, str | None, str | None]:
    """(název prodejny, ulice, PSČ, město) z bloku platebního terminálu, jinak samé None."""
    for index, line in enumerate(footer):
        if not _COOP_COMPANY_RE.match(line):
            continue
        block = []
        for candidate in footer[index + 1: index + 1 + STORE_BLOCK_LINES]:
            if _BLOCK_END_RE.match(candidate) or is_sensitive(candidate):
                break
            block.append(candidate)
        for position, candidate in enumerate(block):
            postal = _POSTAL_CITY_RE.match(candidate)
            if postal is None:
                continue
            # block[0] je název prodejny („Coop Hrádek 034"), ulice je řádek těsně nad PSČ.
            store = block[0] if position > 0 else None
            street = block[position - 1] if position > 1 else None
            return store, street, postal["postal"], postal["city"].strip()
    return None, None, None, None


def read_header(raw_lines: list[str]) -> ReceiptHeader:
    header = raw_lines[: _items_start(raw_lines)]
    company = header[0].strip(" ,") if header else None
    store_label, chain_slug = None, None
    for line in header:
        store = _STORE_RE.match(line)
        if store:
            fmt = store["format"]
            chain_slug = FORMAT_TO_CHAIN.get(fmt) if fmt else None
            # Neznámé velké slovo na konci může být součást názvu („… Hrádek II").
            store_label = store["store"] if chain_slug else line.split(None, 1)[-1].strip()
            break

    total = _total_index(raw_lines)
    block_store, street, postal, city = _store_block(raw_lines[total + 1:] if total is not None else [])
    store_label = store_label or block_store
    raw_name = f"{company} – {store_label}" if company and store_label else company
    # Adresa z hlavičky je sídlo družstva, ne prodejna — bez bloku terminálu se nepošle žádná.
    return ReceiptHeader(raw_name=raw_name, chain_slug=chain_slug, country=COUNTRY, street=street,
                         postal_code=postal, city=city, ico=generic.find_ico(header))


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    start = _items_start(raw_lines)
    total = _total_index(raw_lines)
    end = total if total is not None else len(raw_lines)
    printed_total = parse_amount(_TOTAL_RE.match(raw_lines[total])["total"]) if total is not None else None

    purchased_at = None
    for line in [*raw_lines[:start], *raw_lines[end:]]:
        purchased_at = generic.parse_datetime(line)
        if purchased_at:
            break
    return raw_lines[start:end], Footer(purchased_at=purchased_at, printed_total=printed_total,
                                        total_index=total)


@dataclass
class _Item:
    vat: str | None
    name: str
    total: Decimal
    weight_code: str | None = None
    # (množství, základ, jednotková cena, poznámka) — jako lines._weight_basis
    qty: tuple[Decimal, str, Decimal, str | None] | None = None

    def qty_total(self, qty) -> Decimal:
        return (qty[0] * qty[2]).quantize(Decimal("0.01"))


def _qty_from(match: re.Match) -> tuple[Decimal, str, Decimal, str | None]:
    qty, unit = lines_mod.parse_amount_or_number(match["qty"]), parse_amount(match["unit"])
    if match["peruom"].lower() == "ks":
        return qty, lines_mod.PACKAGE, unit, None
    return lines_mod._weight_basis(match["uom"], match["peruom"], qty, unit)  # noqa: SLF001


def _weight_from_code(code: str) -> Decimal | None:
    """Hmotnost zakódovaná ve váhovém EAN (prefix 2x, číslice 8–12 v gramech), jinak None."""
    if len(code) != 13 or not code.startswith("2"):
        return None
    return Decimal(int(code[7:12])) / 1000


def parse_items(body: list[str]) -> lines_mod.ParseResult:
    result = lines_mod.ParseResult()
    current: _Item | None = None
    last_item_no: int | None = None

    def flush() -> None:
        nonlocal current, last_item_no
        if current is None:
            return
        item, current = current, None
        line_no = len(result.lines) + 1
        if item.total < 0:
            result.lines.append(lines_mod.ParsedLine(line_no, lines_mod.DISCOUNT, item.name, line_total=item.total,
                                                     vat_code=item.vat, applies_to_line_no=last_item_no))
            return
        qty, basis, unit, note = item.qty or (Decimal(1), lines_mod.PACKAGE, item.total, None)
        notes = [note] if note else []
        weight = _weight_from_code(item.weight_code) if item.weight_code else None
        if weight is not None and basis == lines_mod.PER_KG and weight != qty:
            notes.append(f"hmotnost ve váhovém kódu {weight} kg nesedí s {qty} kg na účtence")
        # Váhový kód se na server neposílá: není to globální GTIN a liší se podle navážené hmotnosti.
        result.lines.append(lines_mod.ParsedLine(
            line_no, lines_mod.ITEM, item.name, line_total=item.total, quantity=qty, quantity_basis=basis,
            unit_price=unit, vat_code=item.vat, note="; ".join(notes) or None,
        ))
        last_item_no = line_no

    def unknown(raw: str) -> None:
        flush()
        result.lines.append(lines_mod.ParsedLine(len(result.lines) + 1, lines_mod.UNKNOWN, raw))
        result.warnings.append(f"nerozpoznaný tvar řádku: {raw!r}")

    for raw in body:
        qty_line = lines_mod.PRE_QTY_RE.match(raw)
        if qty_line:
            qty = _qty_from(qty_line)
            if current is None or current.qty is not None:
                unknown(raw)
            elif abs(current.qty_total(qty) - current.total) > LINE_TOLERANCE:
                # Množství k položce nad ním nepatří (OCR přečetlo špatně číslo) — radši balení
                # s cenou řádku než jednotková cena, která nesedí.
                result.warnings.append(f"množství {raw!r} nesedí s cenou řádku {current.total} "
                                       f"položky {current.name!r}")
            else:
                current.qty = qty
            continue
        code = WEIGHT_CODE_RE.match(raw)
        if code:
            if current is None or current.weight_code is not None:
                unknown(raw)
            else:
                current.weight_code = code["code"]
            continue
        head = ITEM_HEAD_RE.match(raw)
        if head:
            flush()
            current = _Item(head["vat"], head["name"].strip(), parse_amount(head["total"]))
            continue
        unknown(raw)
    flush()
    return result


PROFILE = Profile(
    name="coop",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
    parse_items=parse_items,
)
