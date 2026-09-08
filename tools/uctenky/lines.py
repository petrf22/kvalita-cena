"""Klasifikace a skládání řádků bloku položek (kroky 3-5 normalizace).

Účtenka tiskne položku buď na jednom řádku, nebo na dvou — název zvlášť a množství
s cenou na následujícím. Tenhle modul obojí sloučí do jednoho záznamu a rozhodne,
co je jednotková cena.

POZOR na nejpravděpodobnější tichou chybu celé funkce (docs/rozvoj.md): u váhového
zboží je jednotková cena 289,00 Kč/kg, NIKDY zaplacených 87,30 Kč za 0,302 kg.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal

from normalize import AMOUNT, parse_amount

ITEM = "ITEM"
DISCOUNT = "DISCOUNT"
UNKNOWN = "UNKNOWN"

# Odpovídá db/entity/QuantityBasis.java — žádný vlastní slovník.
PACKAGE = "PACKAGE"
PER_KG = "PER_KG"
PER_L = "PER_L"

_UOM_TO_BASIS = {"kg": PER_KG, "g": PER_KG, "l": PER_L, "ml": PER_L}
_UOM_TO_FACTOR = {"kg": Decimal(1), "g": Decimal(1000), "l": Decimal(1), "ml": Decimal(1000)}

_UOM = "kg|g|ml|l"
_VAT_TAIL = r"(?:\s+(?P<vat>[A-Z]))?\s*$"

# 0,302 kg x 289,00 Kč/kg 87,30 A   (název může být na stejném řádku i na předchozím)
WEIGHT_RE = re.compile(
    rf"^(?P<name>.*?)\s*(?P<qty>[0-9]+(?:[.,][0-9]+)?)\s*(?P<uom>{_UOM})\s*x\s*"
    rf"(?P<unit>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*/\s*(?P<peruom>{_UOM})\s+"
    rf"(?P<total>{AMOUNT}){_VAT_TAIL}",
    re.IGNORECASE,
)

# 5 x 2,90 Kč 14,50 A
QTY_RE = re.compile(
    rf"^(?P<name>.*?)\s*(?P<qty>[0-9]+)\s*x\s*(?P<unit>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s+"
    rf"(?P<total>{AMOUNT}){_VAT_TAIL}",
    re.IGNORECASE,
)

# GRAN MORAV.STR.100G 59,90 A   |   Tapas 2+1 zdarma -79,00
SIMPLE_RE = re.compile(rf"^(?P<name>.*?)\s+(?P<total>{AMOUNT}){_VAT_TAIL}")

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


@dataclass
class ParseResult:
    lines: list[ParsedLine] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def strip_weight_marker(name: str) -> tuple[str, bool]:
    """Odřízne značku váhového zboží z konce názvu."""
    match = _WEIGHT_MARKER_RE.search(name)
    if match:
        return name[: match.start()].strip(), True
    return name.strip(), False


def _weight_basis(uom: str, peruom: str, qty: Decimal) -> tuple[Decimal, str, str | None]:
    """Množství a základ ceny u váhového zboží; převádí g/ml na kg/l."""
    basis = _UOM_TO_BASIS[peruom.lower()]
    note = None
    if uom.lower() != peruom.lower():
        qty = qty / _UOM_TO_FACTOR[uom.lower()] * _UOM_TO_FACTOR[peruom.lower()]
        note = f"množství v {uom}, cena za {peruom} — přepočteno"
    return qty, basis, note


def parse_item_lines(raw_lines: list[str]) -> ParseResult:
    """Blok položek na strukturované řádky. Vstup je už očištěný (normalize.clean_lines)."""
    result = ParseResult()
    pending_name: str | None = None
    last_item_no: int | None = None

    def append(line: ParsedLine) -> ParsedLine:
        line.line_no = len(result.lines) + 1
        result.lines.append(line)
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
        weight = WEIGHT_RE.match(raw)
        if weight:
            qty, basis, note = _weight_basis(
                weight["uom"], weight["peruom"], parse_amount(weight["qty"])
            )
            last_item_no = append(
                ParsedLine(
                    line_no=0,
                    kind=ITEM,
                    raw_label=take_name(weight["name"]),
                    line_total=parse_amount(weight["total"]),
                    quantity=qty,
                    quantity_basis=basis,
                    unit_price=parse_amount(weight["unit"]),
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
            last_item_no = append(
                ParsedLine(
                    line_no=0,
                    kind=ITEM,
                    raw_label=take_name(simple["name"]),
                    line_total=total,
                    quantity=Decimal(1),
                    quantity_basis=PACKAGE,
                    unit_price=total,
                    vat_code=simple["vat"],
                )
            ).line_no
            continue

        # Řádek bez částky = název, jehož množství přijde na dalším řádku.
        flush_pending()
        pending_name, _ = strip_weight_marker(raw)

    flush_pending()
    return result
