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
# Kolik řádků překryvu se musí shodovat celé, když je řádek na okraji snímku useknutý.
MIN_CUT_OVERLAP = 2


def merge_segments(texts: list[str]) -> list[str]:
    """Očištěné řádky všech částí za sebou, bez řádků zopakovaných v překryvu.

    Mobil ukládá díl účtenky, až když se posune o ~60 % výšky, takže konec jednoho dílu
    je začátek dalšího. Bez odstranění překryvu by se položky na hranici započítaly
    dvakrát a kontrolní součet by nesedl.
    """
    merged: list[str] = []
    for text in texts:
        current = normalize.clean_lines(text)
        overlap, replace_last = _overlap(merged, current)
        if replace_last:
            # Spodní okraj předchozího dílu řádek usekl, nový díl ho má celý.
            merged.pop()
            overlap -= 1
        merged.extend(current[overlap:])
    return merged


def _overlap(merged: list[str], current: list[str]) -> tuple[int, bool]:
    """Kolik řádků ze začátku `current` už je v `merged` a zda nahradit poslední řádek `merged`.

    Okraj snímku umí řádek na hranici useknout („SLEVA -35,00" se v dalším dílu přečte jen
    jako „SLEVA") a přesná shoda celého okna by pak překryv nenašla vůbec — položky na hranici
    by se započítaly dvakrát (ověřeno na Bille). Useknutý proto smí být jen řádek na samém
    okraji: první řádek nového dílu (horní okraj snímku) nebo poslední řádek předchozího
    (spodní okraj). Zbytek okna se musí shodovat jako dřív a takových řádků musí být aspoň
    MIN_CUT_OVERLAP, ať se useknutý řádek nespáruje s náhodnou shodou jednoho řádku.
    """
    for size in range(min(len(merged), len(current), MAX_OVERLAP), 0, -1):
        pairs = list(zip(merged[-size:], current[:size]))
        same = [_same_line(a, b) for a, b in pairs]
        if all(same):
            return size, False
        if sum(same) < MIN_CUT_OVERLAP:
            continue
        first_cut = not same[0] and _cut_line(pairs[0][1], pairs[0][0])
        last_cut = not same[-1] and _cut_line(pairs[-1][0], pairs[-1][1])
        inner_same = all(same[1:-1])
        if inner_same and (same[0] or first_cut) and (same[-1] or last_cut):
            return size, last_cut
    return 0, False


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


def _cut_line(fragment: str, full: str) -> bool:
    """`fragment` je řádek `full` useknutý okrajem snímku — chybí mu část částek, nic víc.

    Částky, které ve fragmentu zůstaly, musí v plném řádku být (jiná cena = jiný řádek, stejně
    jako u _same_line) a aspoň jedna musí chybět, jinak by to byl obyčejný nesedící řádek.
    Text bez částek se porovná stejně tolerantně jako dva čtení téhož řádku.
    """
    fragment_amounts = normalize.AMOUNT_RE.findall(fragment)
    full_amounts = normalize.AMOUNT_RE.findall(full)
    remaining = list(full_amounts)
    for amount in fragment_amounts:
        if amount not in remaining:
            return False
        remaining.remove(amount)
    if not remaining:
        return False
    fragment_text = normalize.AMOUNT_RE.sub("", fragment).strip()
    full_text = normalize.AMOUNT_RE.sub("", full).strip()
    if not fragment_text:
        return False
    return SequenceMatcher(None, fragment_text, full_text).ratio() >= LINE_SIMILARITY


def _profile_for(raw_lines: list[str], profile_name: str | None):
    profile = profiles.by_name(profile_name) if profile_name else profiles.detect(raw_lines)
    if profile is None:
        raise ValueError("neznámý profil: " + str(profile_name))
    return profile


def server_text(texts: list[str] | str, profile_name: str | None = None) -> str | None:
    """Syrový text pro server (rawText) — hlavička a položky po řádek s celkovou částkou.

    Patička (číslo karty, autorizační kód, věrnostní body) se zahazuje stejně jako
    v dokumentu; bez řádku s celkovou částkou se nedá oddělit, a tak se text neposílá
    vůbec. Řádky s maskovaným číslem karty vypadnou i nad patičkou.
    """
    raw_lines = merge_segments([texts] if isinstance(texts, str) else texts)
    _, footer = _profile_for(raw_lines, profile_name).split_body(raw_lines)
    if footer.total_index is None:
        return None
    kept = raw_lines[: footer.total_index + 1]
    return "\n".join(line for line in kept if not normalize.is_sensitive(line))


def build_document(texts: list[str] | str, profile_name: str | None = None) -> dict:
    """OCR text (jedna nebo víc částí) na dokument s řádky a kontrolou součtu."""
    raw_lines = merge_segments([texts] if isinstance(texts, str) else texts)
    profile = _profile_for(raw_lines, profile_name)

    header = profile.read_header(raw_lines)
    body, footer = profile.split_body(raw_lines)
    parsed = (profile.parse_items or lines_mod.parse_item_lines)(body)

    computed = sum((line.line_total for line in parsed.lines if line.line_total is not None), footer.rounding)
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
        "code": line.code,
    }
    return {key: value for key, value in payload.items() if value is not None}
