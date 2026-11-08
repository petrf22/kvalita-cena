"""Profil účtenky TAMDA FOODS (CZ, IČO 27160840).

Tvar účtenky:

    TAMDA FOODS s.r.o.
    Libušská 319,142 00 Praha 4                    <- ulice, PSČ a město na JEDNOM řádku
    email: … / tel.: …
    IČO: 27160840 DIČ: CZ27160840                  <- OCR čte i „JČO"
    Daňový doklad č. …
    27.10.2026 17:17:33                            <- datum je v HLAVIČCE, ne v patičce
    SD Zboží Množství Kč/MJ Cena Kč
    4 12 Nakl. okurky 680g 7 - 9 cm Znojmia 359,20 <- pořadí, sazba DPH, název, cena řádku
    8594001740659 8 ks x 44,90 Kč/ks               <- EAN, u víc kusů množství × cena za kus
    Celkem: 9 951,20                               <- konec bloku položek
    Zaokrouhleni / K zaplaceni / daňový rozpis      <- zahazuje se

OCR dává cenu řádku jednou na řádek s názvem, jindy na řádek s EAN — obojí je platné.

Proč vlastní rozbor položek (Profile.parse_items): dlouhá účtenka se fotí po dílech a dvě
čtení téhož překryvu se tu liší víc, než parse.merge_segments snese (desetinná tečka
× čárka, cena jednou na prvním a jednou na druhém řádku). Překryv by se nepoznal a položky
by se započítaly víckrát — na reálné účtence 38 516,50 místo 9 951,20. Položky jsou ale
číslované, takže duplicita se pozná podle pořadového čísla. Z víc čtení téže položky se
vezme to, které obstojí v kontrolách: EAN s platnou kontrolní číslicí, množství × cena za
kus = cena řádku. Číslo, které po spojení chybí, se hlásí jako varování.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal

import lines as lines_mod
from normalize import AMOUNT, UNIT_AMOUNT, is_valid_gtin, parse_amount
from profiles import generic
from profiles.base import Footer, Profile, ReceiptHeader

ICO = "27160840"
# Název firmy, jak ho účtenka tiskne. Profil ho dosadí místo OCR čtení prvního řádku
# („IMDA FOODS s.r.o."), protože z storeNameRaw se předvyplňuje formulář nového obchodu
# a ARES se tam záměrně nevolá (docs/stav-implementace.md, „Nový obchod z hlavičky").
COMPANY_NAME = "TAMDA FOODS s.r.o."
COUNTRY = "CZ"
CURRENCY = "CZK"
HEADER_LINES = 10
# Tolerance „množství × cena za kus = cena řádku"; u váhového zboží pokladna zaokrouhluje.
LINE_TOLERANCE = Decimal("0.01")

_NAME_RE = re.compile(r"tamda", re.IGNORECASE)
_ADDRESS_RE = re.compile(r"^(?P<street>.+?),\s*(?P<postal>\d{3}\s?\d{2})\s+(?P<city>\D.*)$")
_TOTAL_RE = re.compile(rf"^celkem\b\D*(?P<total>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)

# Cena na řádku s NÁZVEM je BEZ oddělovače tisíců: s ním by „…tablety do myčky 8 679,80"
# dalo 8 679,80. Řádek nad tisíc korun se pozná zpětně podle množství × ceny za kus
# (_Reading.resolve). Na řádku s EAN žádný název není, tam tisíce nevadí.
_LINE_AMOUNT = r"-?[0-9Oo]+[.,][0-9Oo]{2}"
# Sazby DPH v ČR (včetně dřívějších) — omezení drží mimo i názvy začínající číslem.
_VAT = r"0|10|12|15|21"
_UOM = "ks|kg|g|l|ml"

ITEM_HEAD_RE = re.compile(
    rf"^(?P<no>\d{{1,3}})\s+(?P<vat>{_VAT})\s+(?P<name>.+?)(?:\s+(?P<total>{_LINE_AMOUNT}))?\s*$"
)
CODE_RE = re.compile(
    rf"^(?P<code>\d{{8,14}})"
    rf"(?:\s+(?P<qty>\d+(?:[.,]\d+)?)\s*(?P<uom>{_UOM})\s*x\s*(?P<unit>{UNIT_AMOUNT})\s*"
    rf"(?:Kč|Kc|CZK)?\s*/\s*(?P<peruom>{_UOM}))?"
    rf"(?:\s+(?P<total>{AMOUNT}))?\s*$",
    re.IGNORECASE,
)
_TRAILING_THOUSANDS_RE = re.compile(r"\s(?P<thousands>\d{1,3})$")


def matches(raw_lines: list[str]) -> bool:
    """Název OCR rozhodí („IMDA FOODS"), IČO a e-mail na tamda.eu ale drží."""
    return any(ICO in line or _NAME_RE.search(line) for line in raw_lines[:HEADER_LINES])


def _items_start(raw_lines: list[str]) -> int:
    for index, line in enumerate(raw_lines):
        if ITEM_HEAD_RE.match(line):
            return index
    return min(len(raw_lines), HEADER_LINES)


def read_header(raw_lines: list[str]) -> ReceiptHeader:
    header = raw_lines[: _items_start(raw_lines)]
    street, postal, city = None, None, None
    for line in header:
        address = _ADDRESS_RE.match(line)
        if address:
            street, postal, city = address["street"].strip(), address["postal"], address["city"].strip()
            break
    if any(ICO in line for line in header):
        ico, raw_name = ICO, COMPANY_NAME
    else:
        ico, raw_name = generic.find_ico(raw_lines), generic.store_name(header)
    return ReceiptHeader(raw_name=raw_name, country=COUNTRY, street=street,
                         postal_code=postal, city=city, ico=ico)


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    start = _items_start(raw_lines)
    end, printed_total = len(raw_lines), None
    for index in range(start, len(raw_lines)):
        total = _TOTAL_RE.match(raw_lines[index])
        if total:
            end, printed_total = index, parse_amount(total["total"])
            break

    purchased_at = None
    for line in [*raw_lines[:start], *raw_lines[end:]]:
        purchased_at = generic.parse_datetime(line)
        if purchased_at:
            break
    return raw_lines[start:end], Footer(purchased_at=purchased_at, printed_total=printed_total,
                                        total_index=end if printed_total is not None else None)


@dataclass
class _Reading:
    """Jedno přečtení číslované položky (v překryvu dílů jich je víc)."""

    no: int
    vat: str
    name: str
    head_total: Decimal | None
    code: str | None = None
    code_total: Decimal | None = None
    # (množství, základ, jednotková cena, poznámka) — jako lines._weight_basis
    qty: tuple[Decimal, str, Decimal, str | None] | None = None

    def qty_total(self, qty=None) -> Decimal | None:
        qty = qty or self.qty
        return None if qty is None else (qty[0] * qty[2]).quantize(Decimal("0.01"))

    def thousands_total(self) -> Decimal | None:
        """Cena řádku nad tisíc: „… 1 299,00" se přečte jako název „… 1" a cena 299,00."""
        match = _TRAILING_THOUSANDS_RE.search(self.name)
        if self.head_total is None or match is None or self.head_total < 0:
            return None
        return int(match["thousands"]) * 1000 + self.head_total

    def accepts(self, qty, total: Decimal | None) -> bool:
        """Patří řádek s EAN k téhle položce? Na hranici dílů se jinak přilepí ke špatné."""
        if self.code is not None:
            return False
        if qty is None:
            return True
        expected = self.qty_total(qty)
        known = [t for t in (self.head_total, self.thousands_total(), total) if t is not None]
        return not known or any(abs(t - expected) <= LINE_TOLERANCE for t in known)

    def resolve(self) -> tuple[str, Decimal | None]:
        """Název a cena řádku po kontrole proti množství × ceně za kus."""
        expected = self.qty_total()
        thousands = self.thousands_total()
        if expected is not None and thousands is not None and abs(thousands - expected) <= LINE_TOLERANCE:
            return _TRAILING_THOUSANDS_RE.sub("", self.name).strip(), thousands
        if self.head_total is not None:
            return self.name, self.head_total
        return self.name, self.code_total

    def score(self) -> tuple:
        _, total = self.resolve()
        expected = self.qty_total()
        consistent = expected is None or (total is not None and abs(total - expected) <= LINE_TOLERANCE)
        return (total is not None, self.code is not None and is_valid_gtin(self.code), consistent,
                self.code is not None, self.qty is not None)


def _qty_from(match: re.Match) -> tuple[Decimal, str, Decimal, str | None] | None:
    if match["qty"] is None:
        return None
    qty, unit = lines_mod.parse_amount_or_number(match["qty"]), parse_amount(match["unit"])
    if match["peruom"].lower() == "ks":
        return qty, lines_mod.PACKAGE, unit, None
    return lines_mod._weight_basis(match["uom"], match["peruom"], qty, unit)  # noqa: SLF001


def parse_items(body: list[str]) -> lines_mod.ParseResult:
    result = lines_mod.ParseResult()
    readings: dict[int, list[_Reading]] = {}
    current: _Reading | None = None

    for raw in body:
        head = ITEM_HEAD_RE.match(raw)
        if head:
            current = _Reading(int(head["no"]), head["vat"], head["name"].strip(),
                               parse_amount(head["total"]) if head["total"] else None)
            readings.setdefault(current.no, []).append(current)
            continue
        code = CODE_RE.match(raw)
        if code:
            qty = _qty_from(code)
            total = parse_amount(code["total"]) if code["total"] else None
            # Řádek s EAN bez své položky je začátek dalšího dílu (položka zůstala v předchozím
            # díle) — tam už je přečtená celá, tady se zahodí.
            if current is not None and current.accepts(qty, total):
                current.code, current.code_total, current.qty = code["code"], total, qty
            continue
        current = None
        result.lines.append(lines_mod.ParsedLine(len(result.lines) + 1, lines_mod.UNKNOWN, raw))
        result.warnings.append(f"nerozpoznaný tvar řádku: {raw!r}")

    last_item_no: int | None = None
    for no in sorted(readings):
        best = max(readings[no], key=_Reading.score)  # při shodě vyhrává první čtení
        name, total = best.resolve()
        line_no = len(result.lines) + 1
        note = None
        valid_code = best.code if best.code and is_valid_gtin(best.code) else None
        if best.code and valid_code is None:
            note = f"EAN {best.code} neprošel kontrolní číslicí — neposílá se"
        if total is None:
            result.lines.append(lines_mod.ParsedLine(line_no, lines_mod.UNKNOWN, name, code=valid_code, note=note))
            result.warnings.append(f"položka č. {no}: nenašla se cena řádku")
            continue
        if total < 0:
            result.lines.append(lines_mod.ParsedLine(line_no, lines_mod.DISCOUNT, name, line_total=total,
                                                     vat_code=best.vat, applies_to_line_no=last_item_no))
            continue
        qty, basis, unit, qty_note = best.qty or (Decimal(1), lines_mod.PACKAGE, total, None)
        result.lines.append(lines_mod.ParsedLine(
            line_no, lines_mod.ITEM, name, line_total=total, quantity=qty, quantity_basis=basis,
            unit_price=unit, vat_code=best.vat, note="; ".join(n for n in (qty_note, note) if n) or None,
            code=valid_code,
        ))
        last_item_no = line_no

    if readings:
        missing = sorted(set(range(1, max(readings) + 1)) - set(readings))
        if missing:
            result.warnings.append("chybí položka č. " + ", ".join(map(str, missing))
                                   + " — nedofocený díl účtenky, nebo OCR spletlo pořadové číslo")
    return result


PROFILE = Profile(
    name="tamda",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
    parse_items=parse_items,
)
