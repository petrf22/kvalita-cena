"""Profil účtenek z pokladního softwaru WinShop (www.winshop.cz).

WinShop používají menší obchody různých provozovatelů, proto se profil pozná podle tvaru
účtenky (hlavička sloupců, patička „WinShop software"), ne podle názvu obchodu. Řetězec
(`chainSlug`) neurčuje; obchod se hledá podle IČO a adresy z hlavičky jako u obecného
profilu.

Tvar účtenky (Viet Han Market, Ostrava, 2026-10):

    VIHAIMEX                                       <- název (logo)
    Viet Han Market s.r.o. / Chemická 2093/1a / 70900 Ostrava …
    IČO:17732531 DIČ:CZ17732531
    Účtenka č.: 58 Pokladna č.:007
    Datum usk.zdan. pln. 07.10.2026 17:05:42       <- datum je v HLAVIČCE
    %D. Kód zb. Mn. Cena/MJ Cena celk.             <- hlavička sloupců
    12 2410108 1,00 55,00 55,00                    <- sazba DPH, kód, množství, cena/MJ, cena
    FLYING GOOSE Chilli Omáčka Sriracha Hot 100ml  <- název POD řádkem s cenou
    ZAOK 1,00 0,30 0,30 / ZAOKROUHLENÍ             <- zaokrouhlení, není to položka
    DPH Základ / Sazba DPH 21% 58,99 280,91        <- daňový rozpis, konec bloku položek
    Celkem k úhradě 960,00

Proč vlastní rozbor položek (Profile.parse_items): společné skládání (lines.py) čeká název
NAD řádkem s cenou, takže by každému názvu přidělilo cenu následující položky. Kontrolní
součet by to nepoznal, ceny řádků sedí.

Kód zboží je číslo v pokladně obchodu, ne EAN, a na server se neposílá — jinak by spároval
cizí zboží se stejným číslem.
"""

from __future__ import annotations

import re
from decimal import Decimal

import lines as lines_mod
from normalize import AMOUNT, parse_amount
from profiles import generic
from profiles.base import Footer, Profile

CURRENCY = "CZK"
HEADER_LINES = 15
# Tolerance „množství × cena za jednotku = cena řádku".
LINE_TOLERANCE = Decimal("0.01")

_COLUMN_HEADER_RE = re.compile(r"k[oó]d\s*zb.*cena\s*celk", re.IGNORECASE)
_WINSHOP_RE = re.compile(r"winshop", re.IGNORECASE)
# Sazby DPH v ČR (včetně dřívějších).
_VAT = r"0|10|12|15|21"
ITEM_HEAD_RE = re.compile(
    rf"^(?P<vat>{_VAT})\s+(?P<code>\S+)\s+(?P<qty>-?\d+(?:[.,]\d+)?)\s+(?P<unit>{AMOUNT})\s+"
    rf"(?P<total>{AMOUNT})\s*$"
)
# „ZAOK 1,00 0,30 0,30" a pod ním „ZAOKROUHLENÍ" — do kontrolního součtu patří, položka to není.
_ROUNDING_RE = re.compile(rf"^ZAOK\b.*?(?P<total>{AMOUNT})\s*$", re.IGNORECASE)
_ROUNDING_NAME_RE = re.compile(r"^zaokrouhlen[ií]\s*$", re.IGNORECASE)
# Daňový rozpis pod položkami („DPH Základ", „Sazba DPH 21% …") — konec bloku položek.
_VAT_SUMMARY_RE = re.compile(r"^(?:DPH\b|sazba\s+DPH\b)", re.IGNORECASE)
_TOTAL_RE = re.compile(rf"^celkem\b\D*(?P<total>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)


def matches(raw_lines: list[str]) -> bool:
    return (any(_COLUMN_HEADER_RE.search(line) for line in raw_lines[:HEADER_LINES])
            or any(_WINSHOP_RE.search(line) for line in raw_lines))


def _items_start(raw_lines: list[str]) -> int:
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        if _COLUMN_HEADER_RE.search(line):
            return index + 1
    # Hlavičku sloupců OCR vynechalo — položky začínají prvním řádkem se sazbou a cenou.
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        if ITEM_HEAD_RE.match(line):
            return index
    return generic._header_end(raw_lines)  # noqa: SLF001


def read_header(raw_lines: list[str]):
    # Hlavička je obecná (název, ulice, PSČ s městem, IČO) — jen ji nesmí přetéct do položek.
    return generic.read_header(raw_lines[: _items_start(raw_lines)])


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    start = _items_start(raw_lines)
    total_index, printed_total = None, None
    for index in range(start, len(raw_lines)):
        total = _TOTAL_RE.match(raw_lines[index])
        if total:
            total_index, printed_total = index, parse_amount(total["total"])
            break
    end = total_index if total_index is not None else len(raw_lines)
    body_end = next((index for index in range(start, end) if _VAT_SUMMARY_RE.match(raw_lines[index])), end)

    rounding = sum((parse_amount(match["total"]) for line in raw_lines[start:body_end]
                    if (match := _ROUNDING_RE.match(line))), Decimal(0))
    purchased_at = None
    for line in [*raw_lines[:start], *raw_lines[end:]]:
        purchased_at = generic.parse_datetime(line)
        if purchased_at:
            break
    return raw_lines[start:body_end], Footer(purchased_at=purchased_at, printed_total=printed_total,
                                             total_index=total_index, rounding=rounding)


def parse_items(body: list[str]) -> lines_mod.ParseResult:
    result = lines_mod.ParseResult()
    # (řádek s cenou, části názvu pod ním)
    pending: tuple[re.Match, list[str]] | None = None
    in_rounding = False
    last_item_no: int | None = None

    def flush() -> None:
        nonlocal pending, last_item_no
        if pending is None:
            return
        head, name_parts = pending
        pending = None
        line_no = len(result.lines) + 1
        total = parse_amount(head["total"])
        name = " ".join(name_parts)
        if not name:
            # Bez názvu se položka nesmí ztratit potichu — kód aspoň napoví, o co jde.
            name = head["code"]
            result.warnings.append(f"položka bez názvu: {head.string!r}")
        if total < 0:
            result.lines.append(lines_mod.ParsedLine(line_no, lines_mod.DISCOUNT, name, line_total=total,
                                                     vat_code=head["vat"], applies_to_line_no=last_item_no))
            return
        qty_text, unit = head["qty"], parse_amount(head["unit"])
        qty = lines_mod.parse_amount_or_number(qty_text)
        if qty != qty.to_integral_value():
            # Desetinné množství je nejspíš vážené zboží, jednotku ale účtenka netiskne —
            # bez ní nejde říct, jestli je cena za kg, nebo za l. Radši cena za balení.
            result.warnings.append(f"množství {qty_text} u {name!r} je bez jednotky, bráno jako balení")
            qty, unit = Decimal(1), total
        elif abs(qty * unit - total) > LINE_TOLERANCE:
            result.warnings.append(f"množství × cena za jednotku nesedí s částkou u {name!r}: "
                                   f"{qty} × {unit} ≠ {total}")
            qty, unit = Decimal(1), total
        result.lines.append(lines_mod.ParsedLine(
            line_no, lines_mod.ITEM, name, line_total=total, quantity=qty,
            quantity_basis=lines_mod.PACKAGE, unit_price=unit, vat_code=head["vat"],
        ))
        last_item_no = line_no

    for raw in body:
        if _ROUNDING_RE.match(raw):
            # Zaokrouhlení připočítává split_body (Footer.rounding).
            flush()
            in_rounding = True
            continue
        if in_rounding and _ROUNDING_NAME_RE.match(raw):
            in_rounding = False
            continue
        in_rounding = False
        head = ITEM_HEAD_RE.match(raw)
        if head:
            flush()
            pending = (head, [])
            continue
        if pending is not None:
            # Název pod řádkem s cenou; dlouhý název se může zalomit na další řádek.
            pending[1].append(raw.strip())
            continue
        result.lines.append(lines_mod.ParsedLine(len(result.lines) + 1, lines_mod.UNKNOWN, raw))
        result.warnings.append(f"nerozpoznaný tvar řádku: {raw!r}")
    flush()
    return result


PROFILE = Profile(
    name="winshop",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
    parse_items=parse_items,
)
