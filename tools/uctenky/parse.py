"""Normalizace OCR textu účtenky na strukturovaný dokument (krok 6).

Čistě deterministická — žádný jazykový model (proč, viz normalize.py). Vstupem je text
jedné nebo víc částí účtenky (dlouhá účtenka se fotí po kusech s překryvem), výstupem
dokument, ze kterého worker složí výsledek pro server.
"""

from __future__ import annotations

from decimal import Decimal
from difflib import SequenceMatcher

import lines as lines_mod
import normalize
import profiles
from profiles import generic

# Tolerance kontrolního součtu. Na reálných účtenkách sedí na haléř; nenulová je jen
# proto, aby jeden zaokrouhlovací řádek neshodil celý import.
TOTAL_TOLERANCE = Decimal("0.02")
# Kolik řádků se smí opakovat na hranici dvou částí (překryv ~40 % snímku).
MAX_OVERLAP = 40
# Podobnost dvou čtení téhož řádku v překryvu (difflib ratio), viz _same_line.
LINE_SIMILARITY = 0.8


def merge_segments(texts: list[str]) -> list[str]:
    """Očištěné řádky všech částí za sebou, bez řádků zopakovaných v překryvu.

    Mobil ukládá díl účtenky, až když se posune o ~60 % výšky, takže konec jednoho dílu
    je začátek dalšího. Bez odstranění překryvu by se položky na hranici započítaly
    dvakrát a kontrolní součet by nesedl.
    """
    merged: list[str] = []
    for text in texts:
        current = normalize.clean_lines(text)
        overlap = 0
        for size in range(min(len(merged), len(current), MAX_OVERLAP), 0, -1):
            if all(_same_line(a, b) for a, b in zip(merged[-size:], current[:size])):
                overlap = size
                break
        merged.extend(current[overlap:])
    return merged


def _same_line(a: str, b: str) -> bool:
    """Tentýž řádek přečtený OCR podruhé — v překryvu se čte z jiného snímku a liší se v pár
    znacích („ŘÍCANY" × „R1CANY"). Přesná shoda by překryv nenašla a položky na hranici by se
    započítaly dvakrát (ověřeno na Bille: 1526,70 místo 779,30). Částky ale musí sedět přesně
    — dva různé řádky se stejným názvem a jinou cenou spojit nesmí."""
    if a == b:
        return True
    if normalize.AMOUNT_RE.findall(a) != normalize.AMOUNT_RE.findall(b):
        return False
    return SequenceMatcher(None, a, b).ratio() >= LINE_SIMILARITY


def build_document(texts: list[str] | str, profile_name: str | None = None) -> dict:
    """OCR text (jedna nebo víc částí) na dokument s řádky a kontrolou součtu."""
    raw_lines = merge_segments([texts] if isinstance(texts, str) else texts)
    profile = profiles.by_name(profile_name) if profile_name else profiles.detect(raw_lines)
    if profile is None:
        raise ValueError("neznámý profil: " + str(profile_name))

    header = profile.read_header(raw_lines)
    body, footer = profile.split_body(raw_lines)
    parsed = lines_mod.parse_item_lines(body)

    computed = sum((line.line_total for line in parsed.lines if line.line_total is not None), Decimal(0))
    warnings = list(parsed.warnings)
    if footer.printed_total is None:
        warnings.append("na účtence se nenašel řádek s celkovou částkou")
        matches = False
    else:
        matches = abs(computed - footer.printed_total) <= TOTAL_TOLERANCE
        if not matches:
            warnings.append(f"součet položek {computed} neodpovídá vytištěné částce {footer.printed_total}")
    if footer.purchased_at is None:
        warnings.append("na účtence se nenašlo datum nákupu")

    merchant = {
        "rawName": header.raw_name,
        "chainSlug": header.chain_slug,
        "country": header.country,
        "street": header.street,
        "postalCode": header.postal_code,
        "city": header.city,
        "ico": header.ico or generic.find_ico(raw_lines),
    }
    return {
        "profile": profile.name,
        "merchant": {key: value for key, value in merchant.items() if value is not None},
        "purchasedAt": footer.purchased_at.isoformat() if footer.purchased_at else None,
        "currency": profile.currency,
        "printedTotal": normalize.format_amount(footer.printed_total) if footer.printed_total else None,
        "computedTotal": normalize.format_amount(computed),
        "totalMatches": matches,
        "hasAmounts": any(normalize.AMOUNT_RE.search(line) for line in raw_lines),
        "lines": [_line_to_json(line) for line in parsed.lines],
        "warnings": warnings,
    }


def _line_to_json(line: lines_mod.ParsedLine) -> dict:
    payload = {
        "lineNo": line.line_no,
        "kind": line.kind,
        "rawLabel": line.raw_label,
        "quantity": None if line.quantity is None else f"{line.quantity:f}",
        "quantityBasis": line.quantity_basis,
        "unitPrice": None if line.unit_price is None else normalize.format_amount(line.unit_price),
        "lineTotal": None if line.line_total is None else normalize.format_amount(line.line_total),
        "vatCode": line.vat_code,
        "appliesToLineNo": line.applies_to_line_no,
        "note": line.note,
    }
    return {key: value for key, value in payload.items() if value is not None}
