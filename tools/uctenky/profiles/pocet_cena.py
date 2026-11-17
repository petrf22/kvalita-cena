"""Profil účtenek se sloupci „Polozka DPH Pocet Cena/ks Celkem" a částkami bez haléřů.

Tiskne je pokladna farmářské prodejny (ověřeno 2026-11 na účtence z Plzně se třemi váženými
položkami masa). Jako WinShop to není řetězec: profil se pozná podle hlavičky sloupců,
řetězec (`chainSlug`) neurčuje a obchod se hledá podle IČO a adresy z hlavičky.

Tvar účtenky (anonymizováno):

    OBCHOD VZOR s.r.o.                          <- název
    Vzorova Farma
    Nádražní 1 Vzorov 11111                      <- ulice, město a PSČ na JEDNOM řádku, PSČ na konci
    IČO: 12345678
    Misto: Prodej
    Polozka DPH Pocet Cena/ks Celkem             <- hlavička sloupců, pod ní položky
    Párky KUŘECÍ                                 <- název NAD řádkem s cenou
    12% 0,65x 395 256,75                         <- sazba DPH, množství, cena za jednotku, cena
    CELKEM 619
    KARTOU 619
    ZAOKROUHLENO(sazba 12%) 0,15                 <- zaokrouhlení POD „Celkem" (Penny ho má nad ním)
    VRACENO 0
    sazba DPH Celkem                             <- daňový rozpis
    000001 16:03:58 17.10.2026                   <- číslo dokladu, ČAS PŘED DATEM

Proč vlastní rozbor položek (Profile.parse_items): pokladna tiskne částky bez koncových nul
(`395`, `181,2`, `619`), a obecná `normalize.AMOUNT` chce přesně dvě desetinná místa. Z řádku
`12% 0,65x 395 256,75` by obecný profil vzal `395 256,75` jako jednu částku s mezerou
v tisících. Volnější částku proto bere jen tenhle profil a jen na řádku ukotveném sazbou
DPH a „x" — globálně by chytila čísla pokladen, gramáže i časy.

Desetinné množství nemá jednotku (sloupec se jmenuje „Cena/ks"), u vážené uzeniny a masa
je to ale kg. Bere se proto jako cena za kg s poznámkou na řádku — cena za balení by
u váženého zboží nešla porovnat mezi nákupy. (WinShop, kde se prodává i tekuté zboží,
bere desetinné množství jako balení.)
"""

from __future__ import annotations

import dataclasses
import re
from decimal import Decimal

import lines as lines_mod
from normalize import parse_amount
from profiles import generic
from profiles.base import Footer, Profile, ReceiptHeader

CURRENCY = "CZK"
HEADER_LINES = 15
# Tolerance „množství × cena za jednotku = cena řádku".
LINE_TOLERANCE = Decimal("0.01")
WEIGHT_NOTE = "jednotka na účtence chybí, bráno jako kg"

# Částka bez povinných haléřů: 619 | 181,2 | 256,75. Jen uvnitř tohoto profilu, viz docstring.
_AMT = r"-?\d+(?:[.,]\d{1,2})?"
_COLUMN_HEADER_RE = re.compile(r"polo[zž]ka\s+DPH\s+po[cč]et\s+cena\s*/\s*ks\s+celkem", re.IGNORECASE)
# Sazby DPH v ČR (včetně dřívějších).
_VAT = r"0|10|12|15|21"
ITEM_HEAD_RE = re.compile(
    rf"^(?P<vat>{_VAT})\s*%?\s+(?P<qty>\d+(?:[.,]\d+)?)\s*x\s+(?P<unit>{_AMT})\s+(?P<total>{_AMT})\s*$",
    re.IGNORECASE,
)
_TOTAL_RE = re.compile(rf"^celkem\s+(?P<total>{_AMT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)
_ROUNDING_RE = re.compile(rf"^zaokrouhlen\w*\b.*?\s(?P<amount>{_AMT})\s*$", re.IGNORECASE)
# Daňový rozpis pod „Celkem" — tam končí hledání zaokrouhlení.
_VAT_SUMMARY_RE = re.compile(r"^sazba\b", re.IGNORECASE)
# „Nádražní 1 Vzorov 11111" — ulice s číslem domu, město a PSČ na konci jednoho řádku.
_STREET_CITY_POSTAL_RE = re.compile(
    r"^(?P<street>\D.*?\d+[a-zA-Z]?(?:/\d+[a-zA-Z]?)?)\s+(?P<city>\D+?)\s+(?P<postal>\d{3}\s?\d{2})$"
)


def matches(raw_lines: list[str]) -> bool:
    return any(_COLUMN_HEADER_RE.search(line) for line in raw_lines[:HEADER_LINES])


def _items_start(raw_lines: list[str]) -> int:
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        if _COLUMN_HEADER_RE.search(line):
            return index + 1
    return generic._header_end(raw_lines)  # noqa: SLF001


def read_header(raw_lines: list[str]) -> ReceiptHeader:
    header_lines = raw_lines[: _items_start(raw_lines)]
    header = generic.read_header(header_lines)
    if header.postal_code is not None:
        return header
    for line in header_lines:
        if generic._is_company_line(line):  # noqa: SLF001
            continue
        match = _STREET_CITY_POSTAL_RE.match(line)
        if match:
            return dataclasses.replace(header, street=match["street"].strip(), city=match["city"].strip(),
                                       postal_code=match["postal"].replace(" ", ""))
    return header


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    start = _items_start(raw_lines)
    total_index, printed_total = None, None
    for index in range(start, len(raw_lines)):
        total = _TOTAL_RE.match(raw_lines[index])
        if total:
            total_index, printed_total = index, parse_amount(total["total"])
            break
    end = total_index if total_index is not None else len(raw_lines)

    # Zaokrouhlení tiskne pokladna pod „Celkem" (do daňového rozpisu); kdyby ho dala nad něj,
    # nesmí skončit jako název položky. Do kontrolního součtu patří v obou případech.
    rounding_lines = list(raw_lines[start:end])
    if total_index is not None:
        summary = next((index for index in range(total_index + 1, len(raw_lines))
                        if _VAT_SUMMARY_RE.match(raw_lines[index])), len(raw_lines))
        rounding_lines += raw_lines[total_index + 1:summary]
    rounding = sum((parse_amount(match["amount"]) for line in rounding_lines
                    if (match := _ROUNDING_RE.match(line))), Decimal(0))
    body = [line for line in raw_lines[start:end] if not _ROUNDING_RE.match(line)]

    purchased_at = None
    for line in [*raw_lines[end:], *raw_lines[:start]]:
        purchased_at = generic.parse_datetime(line)
        if purchased_at:
            break
    if purchased_at is None:
        purchased_at = generic.parse_split_datetime(raw_lines[end:]) or generic.parse_split_datetime(raw_lines[:start])
    return body, Footer(purchased_at=purchased_at, printed_total=printed_total,
                        total_index=total_index, rounding=rounding)


def parse_items(body: list[str]) -> lines_mod.ParseResult:
    result = lines_mod.ParseResult()
    name_parts: list[str] = []
    last_item_no: int | None = None

    for raw in body:
        head = ITEM_HEAD_RE.match(raw)
        if not head:
            # Název NAD řádkem s cenou; dlouhý název se může zalomit na víc řádků.
            name_parts.append(raw.strip())
            continue
        name = " ".join(name_parts)
        name_parts = []
        if not name:
            # Bez názvu se položka nesmí ztratit potichu — aspoň syrový řádek napoví, o co jde.
            name = raw
            result.warnings.append(f"položka bez názvu: {raw!r}")
        line_no = len(result.lines) + 1
        total = parse_amount(head["total"])
        if total < 0:
            result.lines.append(lines_mod.ParsedLine(line_no, lines_mod.DISCOUNT, name, line_total=total,
                                                     vat_code=head["vat"], applies_to_line_no=last_item_no))
            continue
        qty = lines_mod.parse_amount_or_number(head["qty"])
        unit = parse_amount(head["unit"])
        weighed = qty != qty.to_integral_value()
        basis, note = (lines_mod.PER_KG, WEIGHT_NOTE) if weighed else (lines_mod.PACKAGE, None)
        if abs(qty * unit - total) > LINE_TOLERANCE:
            # Chybně přečtená cena nebo množství — kontrolní součet to nepozná, sčítá jen ceny řádků.
            result.warnings.append(f"množství × cena za jednotku nesedí s částkou u {name!r}: "
                                   f"{qty} × {unit} ≠ {total}")
            qty, basis, unit, note = Decimal(1), lines_mod.PACKAGE, total, None
        result.lines.append(lines_mod.ParsedLine(
            line_no, lines_mod.ITEM, name, line_total=total, quantity=qty, quantity_basis=basis,
            unit_price=unit, vat_code=head["vat"], note=note,
        ))
        last_item_no = line_no

    if name_parts:
        # Text, ke kterému nedorazil řádek s cenou — nesmí zmizet potichu.
        leftover = " ".join(name_parts)
        result.lines.append(lines_mod.ParsedLine(len(result.lines) + 1, lines_mod.UNKNOWN, leftover))
        result.warnings.append(f"nerozpoznaný tvar řádku: {leftover!r}")
    return result


PROFILE = Profile(
    name="pocet-cena",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
    parse_items=parse_items,
)
