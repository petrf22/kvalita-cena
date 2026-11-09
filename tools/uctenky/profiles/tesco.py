"""Profil účtenky Tesco (CZ).

Tvar účtenky (hypermarket, 2026-11):

    TESCO
    Tesco Stores CR a.s.
    Vršovická 1527/68b, 100 00 Praha 10        <- adresa SÍDLA společnosti, ne prodejny
    Ič: 45308314 DIč: CZ45308314               <- stejné pro všechny prodejny
    Hypermarket Plzeň Borská Pole              <- formát a název prodejny
    RODO.PRSNI RIZKY            BA             <- značka váženého zboží v cenovém sloupci
    0,874 kg * 259,90 Kč/kg  227,15B           <- „*" místo „x", sazba DPH přilepená k ceně
    CARPACCIO 100 g
          2 *   65,90 Kč     131,80B           <- množství POD názvem jako u ostatních
    MEZISOUČET                1723,92
    CC LEE MIN.CUB.SYR 1       -20,00          <- sleva Clubcard až POD mezisoučtem;
    celková úspora             -65,00             položku určuje jen useknutý název
    CELKEM                    1658,92          <- konec bloku položek

Proč vlastní rozbor položek (Profile.parse_items): slevy Clubcard nestojí pod položkou, které
se týkají, ale v bloku pod mezisoučtem, a k položce je váže jen prvních 17 znaků jejího
názvu. Obecný profil blok položek u mezisoučtu utne, takže by slevy vypadly a kontrolní
součet by nesedl. Položky nad mezisoučtem jen převede na tvar, který zná společné skládání
(lines.py), a nechá je složit tam.

Adresa z hlavičky je sídlo společnosti, a tak se neposílá žádná, stejně jako u COOP bez
bloku terminálu. Prodejnu pak vybere uživatel; název prodejny jde na server v rawName.
"""

from __future__ import annotations

import re
from decimal import Decimal
from difflib import SequenceMatcher

import lines as lines_mod
from normalize import AMOUNT, parse_amount
from profiles import generic
from profiles.base import Footer, Profile, ReceiptHeader

CHAIN_SLUG = "tesco"
COUNTRY = "CZ"
CURRENCY = "CZK"
HEADER_LINES = 8
# Podobnost useknutého názvu ve slevě a začátku názvu položky (difflib ratio) — OCR čte
# tentýž název ve slevě a v položce zvlášť a může se v pár znacích lišit.
DISCOUNT_NAME_SIMILARITY = 0.8

_NAME_RE = re.compile(r"\btesco\b", re.IGNORECASE)
# Řádek prodejny: formát a název („Hypermarket Plzeň Borská Pole", „Expres Vzorov").
_STORE_RE = re.compile(r"^(?:hypermarket|supermarket|expres|extra|obchodn[ií]\s+d[ůu]m|OD)\b",
                       re.IGNORECASE)
_TOTAL_RE = re.compile(rf"^celkem\b\D*(?P<total>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)
_SUBTOTAL_RE = re.compile(rf"^mezisou[čc]et\b\D*(?P<total>{AMOUNT})?\s*$", re.IGNORECASE)
_SAVINGS_RE = re.compile(rf"^celkov[aá]\s+[uú]spora\b\D*(?P<total>{AMOUNT})\s*$", re.IGNORECASE)
# Sleva pod mezisoučtem: „CC <prvních 17 znaků názvu položky> -20,00". CC = Clubcard.
_DISCOUNT_RE = re.compile(rf"^(?P<label>(?:(?P<club>CC)\s+)?(?P<ref>.+?))\s+(?P<total>-{AMOUNT})\s*$")
# Značky v cenovém sloupci řádku s názvem váženého zboží. Význam Tesco neuvádí; neznámá
# značka se neodřízne a řádek se ohlásí jako nerozpoznaný, aby se nic neztratilo potichu.
_WEIGHT_MARKERS = r"BA|SC"
_MARKER_LINE_RE = re.compile(rf"^(?:{_WEIGHT_MARKERS})$")
_MARKER_TAIL_RE = re.compile(rf"\s+(?:{_WEIGHT_MARKERS})$")
# „0,874 kg * 259,90" a „2 * 65,90" — hvězdička jako znak násobení, jen za množstvím na
# začátku řádku. Jinde (maskované číslo karty) se nemění.
_STAR_QTY_RE = re.compile(r"^(?P<qty>\d+(?:[.,]\d+)?(?:\s*(?:kg|g|l|ml))?)\s*\*\s*", re.IGNORECASE)
# „227,15B" — sazba DPH přilepená k ceně; společné skládání čeká mezeru.
_GLUED_VAT_RE = re.compile(r"(\d[.,]\d{2})([A-Z])$")


def matches(raw_lines: list[str]) -> bool:
    return any(_NAME_RE.search(line) for line in raw_lines[:5])


def _items_start(raw_lines: list[str]) -> int:
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        if _STORE_RE.match(line):
            return index + 1
    return generic._header_end(raw_lines)  # noqa: SLF001


def read_header(raw_lines: list[str]) -> ReceiptHeader:
    header = raw_lines[: _items_start(raw_lines)]
    store = next((line for line in header if _STORE_RE.match(line)), None)
    raw_name = f"Tesco {store}" if store else "Tesco"
    return ReceiptHeader(raw_name=raw_name, chain_slug=CHAIN_SLUG, country=COUNTRY,
                         ico=generic.find_ico(header))


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    """Blok položek až po CELKEM — včetně mezisoučtu a slev Clubcard pod ním."""
    start = _items_start(raw_lines)
    end, printed_total = len(raw_lines), None
    for index in range(start, len(raw_lines)):
        total = _TOTAL_RE.match(raw_lines[index])
        if total:
            end, printed_total = index, parse_amount(total["total"])
            break

    purchased_at = None
    for line in raw_lines[end:]:
        purchased_at = generic.parse_datetime(line)
        if purchased_at:
            break
    if purchased_at is None:
        purchased_at = generic.parse_split_datetime(raw_lines[end:])
    return raw_lines[start:end], Footer(purchased_at=purchased_at, printed_total=printed_total,
                                        total_index=end if printed_total is not None else None)


def _to_common_shape(line: str) -> str | None:
    """Řádek položky v tvaru společného skládání; None u samostatné značky váženého zboží."""
    if _MARKER_LINE_RE.match(line):
        return None
    line = _MARKER_TAIL_RE.sub("", line)
    line = _STAR_QTY_RE.sub(lambda m: f"{m['qty']} x ", line)
    return _GLUED_VAT_RE.sub(r"\1 \2", line)


def _discount_target(ref: str, items: list[lines_mod.ParsedLine]) -> lines_mod.ParsedLine | None:
    """Položka, jejíž začátek názvu odpovídá useknutému názvu ve slevě; při nejasnosti None."""
    scored = [(SequenceMatcher(None, ref, item.raw_label[:len(ref)]).ratio(), item) for item in items]
    best = max((score for score, _ in scored), default=0)
    if best < DISCOUNT_NAME_SIMILARITY:
        return None
    winners = [item for score, item in scored if score == best]
    # Tatáž položka na dvou řádcích (dva nákupy stejného zboží) — sleva patří kterékoli z nich.
    return winners[0] if len({item.raw_label for item in winners}) == 1 else None


def parse_items(body: list[str]) -> lines_mod.ParseResult:
    subtotal_index = next((index for index, line in enumerate(body) if _SUBTOTAL_RE.match(line)), len(body))
    shaped = [shape for line in body[:subtotal_index] if (shape := _to_common_shape(line)) is not None]
    result = lines_mod.parse_item_lines(shaped)
    items = [line for line in result.lines if line.kind == lines_mod.ITEM]

    if subtotal_index < len(body):
        subtotal = _SUBTOTAL_RE.match(body[subtotal_index])["total"]
        computed = sum((line.line_total for line in result.lines if line.line_total is not None), Decimal(0))
        if subtotal and parse_amount(subtotal) != computed:
            result.warnings.append(f"součet položek {computed} neodpovídá mezisoučtu {parse_amount(subtotal)}")

    discounts = Decimal(0)
    for raw in body[subtotal_index + 1:]:
        savings = _SAVINGS_RE.match(raw)
        if savings:
            # Souhrn slev, ne další sleva — jen kontrola, že se žádná neztratila.
            # Znaménko u úspory OCR občas ztratí, porovnává se velikost.
            if abs(parse_amount(savings["total"])) != abs(discounts):
                result.warnings.append(f"celková úspora {savings['total']} neodpovídá součtu slev {discounts}")
            continue
        discount = _DISCOUNT_RE.match(raw)
        if discount is None:
            result.lines.append(lines_mod.ParsedLine(len(result.lines) + 1, lines_mod.UNKNOWN, raw))
            result.warnings.append(f"nerozpoznaný tvar řádku: {raw!r}")
            continue
        total = parse_amount(discount["total"])
        discounts += total
        target = _discount_target(discount["ref"], items)
        if target is None:
            result.warnings.append(f"sleva {raw!r} nenašla položku, které se týká")
        result.lines.append(lines_mod.ParsedLine(
            len(result.lines) + 1, lines_mod.DISCOUNT, discount["label"], line_total=total,
            applies_to_line_no=target.line_no if target else None,
        ))
    return result


PROFILE = Profile(
    name="tesco",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
    parse_items=parse_items,
)
