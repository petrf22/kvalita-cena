"""Klasifikace a skládání řádků bloku položek (kroky 3-5 normalizace).

Účtenka tiskne položku buď na jednom řádku, nebo na dvou — název zvlášť a množství
s cenou na následujícím. Tenhle modul obojí sloučí do jednoho záznamu a rozhodne,
co je jednotková cena.

POZOR na nejpravděpodobnější tichou chybu celé funkce (docs/rozvoj.md): u váhového
zboží je jednotková cena 289,00 Kč/kg, NIKDY zaplacených 87,30 Kč za 0,302 kg.

Tištěné poznámky bez údaje o zboží (cena po slevě v závorce, „Věkový limit") se přeskakují
záměrně a JEN podle pevného výčtu tvarů — každý jiný řádek bez ceny se dál hlásí jako
nerozpoznaný, aby se položka nemohla ztratit potichu.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal

from normalize import AMOUNT, UNIT_AMOUNT, parse_amount

ITEM = "ITEM"
DISCOUNT = "DISCOUNT"
UNKNOWN = "UNKNOWN"

# Odpovídá db/entity/QuantityBasis.java — žádný vlastní slovník.
PACKAGE = "PACKAGE"
PER_KG = "PER_KG"
PER_L = "PER_L"

_UOM_TO_BASIS = {"kg": PER_KG, "g": PER_KG, "l": PER_L, "ml": PER_L}
_UOM_TO_FACTOR = {"kg": Decimal(1), "g": Decimal(1000), "l": Decimal(1), "ml": Decimal(1000)}
_BASIS_UOM = {PER_KG: "kg", PER_L: "l"}

_UOM = "kg|g|ml|l"
# Zaplacená částka je zaokrouhlená na haléře, množství × cena za jednotku ne (0,526 kg × 109,90).
_LINE_TOLERANCE = Decimal("0.01")
_VAT_TAIL = r"(?:\s+(?P<vat>[A-Z]))?\s*$"

# 0,302 kg x 289,00 Kč/kg 87,30 A   (název může být na stejném řádku i na předchozím)
WEIGHT_RE = re.compile(
    rf"^(?P<name>.*?)\s*(?P<qty>[0-9]+(?:[.,][0-9]+)?)\s*(?P<uom>{_UOM})\s*x\s*"
    rf"(?P<unit>{UNIT_AMOUNT})\s*(?:Kč|Kc|CZK)?\s*/\s*(?P<peruom>{_UOM})\s+"
    rf"(?P<total>{AMOUNT}){_VAT_TAIL}",
    re.IGNORECASE,
)

# 5 x 2,90 Kč 14,50 A
QTY_RE = re.compile(
    rf"^(?P<name>.*?)\s*(?P<qty>[0-9]+)\s*x\s*(?P<unit>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s+"
    rf"(?P<total>{AMOUNT}){_VAT_TAIL}",
    re.IGNORECASE,
)

# GRAN MORAV.STR.100G 59,90 A   |   Tapas 2+1 zdarma -79,00   |   Balsýr bloček 200g C 110.70
# (Penny tiskne sazbu DPH PŘED částku — písmeno do názvu nepatří)
SIMPLE_RE = re.compile(rf"^(?P<name>.*?)\s+(?:(?P<vatpre>[A-Z])\s+)?(?P<total>{AMOUNT}){_VAT_TAIL}")

# 3.000 ks x 36.90 Kč /ks   — množství na SAMOSTATNÉM řádku NAD názvem (Penny). Bez něj by
# zůstalo jen zaplacených 110,70 za tři kusy místo ceny 36,90 za kus (normalize.py).
# 0.526 kg (E) x 109.90 Kč /kg — u váženého zboží Penny za jednotkou tiskne, jak byla
# hmotnost zadána: (V) z pokladní váhy, (E) jinak (legenda v patičce účtenky).
PRE_QTY_RE = re.compile(
    rf"^(?P<qty>[0-9]+(?:[.,][0-9]+)?)\s*(?P<uom>ks|kg|g|l|ml)(?:\s*\([A-Z]\))?\s*x\s*(?P<unit>{UNIT_AMOUNT})\s*"
    rf"(?:Kč|Kc|CZK)?\s*/\s*(?P<peruom>ks|kg|g|l|ml)\s*$",
    re.IGNORECASE,
)

# (YO SIRUP CERNY BEZ 59,90)   — cena po slevě, kterou Billa tiskne pod řádek „SLEVA -35,00".
# Není to položka; částka se jen porovná s položkou a slevou nad ní.
AFTER_DISCOUNT_RE = re.compile(rf"^\((?P<name>.*?)\s*(?P<total>{AMOUNT})\)$")

# Poznámky pokladny bez údaje o zboží. Věkový limit tiskne Albert pod alkoholem; OCR občas
# ztratí diakritiku.
_INFO_LINE_RE = re.compile(r"^v[eě]kov[yý]\s+limit$", re.IGNORECASE)

# Osamocené velké písmeno na konci řádku s názvem (E, P) je značka váhového zboží,
# ne součást názvu — na účtence stojí v cenovém sloupci.
_WEIGHT_MARKER_RE = re.compile(r"\s+([A-Z])$")


@dataclass
class ParsedLine:
    """Jeden řádek účtenky po sloučení názvu a množství."""

    line_no: int
    kind: str
    raw_label: str
    line_total: Decimal | None = None
    quantity: Decimal | None = None
    quantity_basis: str | None = None
    unit_price: Decimal | None = None
    vat_code: str | None = None
    applies_to_line_no: int | None = None
    note: str | None = None
    # EAN vytištěný u položky — jen s platnou kontrolní číslicí (normalize.is_valid_gtin).
    code: str | None = None


@dataclass
class ParseResult:
    lines: list[ParsedLine] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def parse_amount_or_number(text: str) -> Decimal:
    """Množství: 3.000 / 0,302 / 5 — na rozdíl od částky nemusí mít dvě desetinná místa."""
    return Decimal(text.replace(",", ".")).normalize()


def strip_weight_marker(name: str) -> tuple[str, bool]:
    """Odřízne značku váhového zboží z konce názvu."""
    match = _WEIGHT_MARKER_RE.search(name)
    if match:
        return name[: match.start()].strip(), True
    return name.strip(), False


def _weight_basis(uom: str, peruom: str, qty: Decimal,
                  unit: Decimal) -> tuple[Decimal, str, Decimal, str | None]:
    """Množství, základ a jednotková cena váhového zboží — VŽDY v kg/l.

    Základ PER_KG/PER_L říká serveru, že cena je za kg/l, takže množství i cena se musí
    převést oba: „200 g x 0,25 Kč/g" je 0,2 kg za 250 Kč/kg, ne 0,25 Kč/kg.
    """
    basis = _UOM_TO_BASIS[peruom.lower()]
    note = None
    qty_factor, unit_factor = _UOM_TO_FACTOR[uom.lower()], _UOM_TO_FACTOR[peruom.lower()]
    if qty_factor != 1 or unit_factor != 1:
        qty, unit = qty / qty_factor, unit * unit_factor
        note = f"množství v {uom}, cena za {peruom} — přepočteno na {_BASIS_UOM[basis]}"
    return qty, basis, unit, note


def parse_item_lines(raw_lines: list[str]) -> ParseResult:
    """Blok položek na strukturované řádky. Vstup je už očištěný (normalize.clean_lines)."""
    result = ParseResult()
    pending_name: str | None = None
    # (množství, základ, jednotková cena, poznámka) z řádku NAD názvem
    pending_qty: tuple[Decimal, str, Decimal, str | None] | None = None
    last_item_no: int | None = None
    # Zaplaceno za poslední položku po odečtení jejích slev — pro kontrolu ceny po slevě.
    last_item_net: Decimal | None = None

    def append(line: ParsedLine) -> ParsedLine:
        nonlocal last_item_net
        line.line_no = len(result.lines) + 1
        result.lines.append(line)
        if line.kind == ITEM:
            last_item_net = line.line_total
        elif line.kind == DISCOUNT and line.applies_to_line_no is not None and last_item_net is not None:
            last_item_net += line.line_total
        return line

    def flush_pending() -> None:
        """Název, ke kterému nikdy nedorazila cena, se nesmí ztratit potichu."""
        nonlocal pending_name
        if pending_name is not None:
            append(ParsedLine(0, UNKNOWN, pending_name))
            result.warnings.append(f"nerozpoznaný tvar řádku: {pending_name!r}")
            pending_name = None

    def take_name(matched: str) -> str:
        """Název ze stejného řádku má přednost, jinak se bere z předchozího řádku."""
        nonlocal pending_name
        name = matched.strip()
        if name:
            flush_pending()
            return name
        name, pending_name = pending_name or "", None
        return name

    for raw in raw_lines:
        if _INFO_LINE_RE.match(raw):
            # Rozpracovaný název nad poznámkou zůstává čekat na svou cenu.
            continue

        after_discount = AFTER_DISCOUNT_RE.match(raw)
        if after_discount:
            # Celkový součet nepozná slevu připojenou ke špatné položce — tahle kontrola ano.
            if last_item_net is None or parse_amount(after_discount["total"]) != last_item_net:
                result.warnings.append(f"cena po slevě {raw!r} nesedí s položkou nad ní")
            continue

        pre_qty = PRE_QTY_RE.match(raw)
        if pre_qty:
            qty = parse_amount_or_number(pre_qty["qty"])
            if pre_qty["peruom"].lower() == "ks":
                pending_qty = (qty, PACKAGE, parse_amount(pre_qty["unit"]), None)
            else:
                qty, basis, unit, note = _weight_basis(pre_qty["uom"], pre_qty["peruom"], qty,
                                                       parse_amount(pre_qty["unit"]))
                pending_qty = (qty, basis, unit, note)
            continue

        weight = WEIGHT_RE.match(raw)
        if weight:
            qty, basis, unit, note = _weight_basis(
                weight["uom"], weight["peruom"], parse_amount(weight["qty"]), parse_amount(weight["unit"])
            )
            last_item_no = append(
                ParsedLine(
                    line_no=0,
                    kind=ITEM,
                    raw_label=take_name(weight["name"]),
                    line_total=parse_amount(weight["total"]),
                    quantity=qty,
                    quantity_basis=basis,
                    unit_price=unit,
                    vat_code=weight["vat"],
                    note=note,
                )
            ).line_no
            continue

        quantity = QTY_RE.match(raw)
        if quantity:
            # n x cena: cena je za JEDNO balení, počet kusů na ni nemá vliv.
            last_item_no = append(
                ParsedLine(
                    line_no=0,
                    kind=ITEM,
                    raw_label=take_name(quantity["name"]),
                    line_total=parse_amount(quantity["total"]),
                    quantity=parse_amount(quantity["qty"]),
                    quantity_basis=PACKAGE,
                    unit_price=parse_amount(quantity["unit"]),
                    vat_code=quantity["vat"],
                )
            ).line_no
            continue

        simple = SIMPLE_RE.match(raw)
        if simple:
            total = parse_amount(simple["total"])
            if total < 0:
                # Sleva je vlastní řádek pod položkou, které se týká.
                flush_pending()
                append(
                    ParsedLine(
                        line_no=0,
                        kind=DISCOUNT,
                        raw_label=simple["name"].strip(),
                        line_total=total,
                        applies_to_line_no=last_item_no,
                    )
                )
                continue
            if pending_qty is not None and abs(pending_qty[0] * pending_qty[2] - total) > _LINE_TOLERANCE:
                # Množství × cena za jednotku musí dát zaplacenou částku. Nesoulad znamená chybně
                # přečtenou cenu (OCR „49:90", záměna číslice) — součet účtenky to nepozná,
                # protože ten sčítá jen zaplacené částky.
                result.warnings.append(
                    f"množství × cena za jednotku nesedí s částkou u {simple['name'].strip()!r}: "
                    f"{pending_qty[0]} × {pending_qty[2]} ≠ {total}")
            qty, basis, unit, note = pending_qty or (Decimal(1), PACKAGE, total, None)
            pending_qty = None
            last_item_no = append(
                ParsedLine(
                    line_no=0,
                    kind=ITEM,
                    raw_label=take_name(simple["name"]),
                    line_total=total,
                    quantity=qty,
                    quantity_basis=basis,
                    unit_price=unit,
                    vat_code=simple["vat"] or simple["vatpre"],
                    note=note,
                )
            ).line_no
            continue

        # Řádek bez částky = název, jehož množství přijde na dalším řádku.
        flush_pending()
        pending_name, _ = strip_weight_marker(raw)

    flush_pending()
    return result
