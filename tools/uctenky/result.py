"""Dokument z parse.py -> výsledek pro server (WorkerReceiptResult na backendu).

Slevy se připojí k položce, které se týkají (discountAmount), klubová sleva se pozná podle
textu řádku. Nic dalšího se neodhaduje — co parser nepoznal, jde k uživateli jako řádek
bez ceny, ne jako výmysl.
"""

from __future__ import annotations

import re
from decimal import Decimal

import lines as lines_mod

_BASIS_TO_UNIT = {lines_mod.PER_KG: "KG", lines_mod.PER_L: "L", lines_mod.PACKAGE: "PCS"}
_CLUB_RE = re.compile(r"klub|club|karta|card|lidl\s*plus|moje\s*billa|albert\s*plus|penny\s*karta",
                      re.IGNORECASE)


def status_of(document: dict) -> str:
    """PROCESSED / NOT_A_RECEIPT (žádná částka) / UNREADABLE (částky, ale žádná položka)."""
    if not document["hasAmounts"]:
        return "NOT_A_RECEIPT"
    if not any(line["kind"] == lines_mod.ITEM for line in document["lines"]):
        return "UNREADABLE"
    return "PROCESSED"


def to_payload(document: dict, raw_text: str | None, model: str) -> dict:
    status = status_of(document)
    payload = {
        "status": status,
        "model": model,
        "rawText": raw_text,
        "extraction": document,
        "lines": [],
    }
    if status != "PROCESSED":
        return payload

    merchant = document["merchant"]
    # Tvar "[ulice, ]PSČ Město" zpátky rozkládají klienti při předvyplnění nového obchodu
    # (mobile ui/receipt/ReceiptStorePrefill.kt, frontend features/receipts/receipt-store-prefill.ts)
    # — změna formátu musí jít ruku v ruce s nimi.
    address = ", ".join(part for part in (
        merchant.get("street"),
        " ".join(p for p in (merchant.get("postalCode"), merchant.get("city")) if p),
    ) if part)
    payload.update({
        "storeName": merchant.get("rawName"),
        "storeAddress": address or None,
        "storeIco": merchant.get("ico"),
        "purchasedAt": document["purchasedAt"],
        "totalAmount": document["printedTotal"],
        "currency": document["currency"],
    })

    by_no: dict[int, dict] = {}
    for line in document["lines"]:
        if line["kind"] == lines_mod.DISCOUNT:
            target = by_no.get(line.get("appliesToLineNo"))
            if target is None:
                continue
            discount = abs(Decimal(line["lineTotal"]))
            target["discountAmount"] = f"{Decimal(target.get('discountAmount') or 0) + discount:.2f}"
            if _CLUB_RE.search(line.get("rawLabel") or ""):
                target["priceKind"] = "CLUB_CARD"
            continue
        label = (line.get("rawLabel") or "").strip()
        if not label:
            continue
        out = {
            "label": label,
            "quantity": line.get("quantity"),
            "quantityUnit": _BASIS_TO_UNIT.get(line.get("quantityBasis"), "PCS"),
            "unitPrice": line.get("unitPrice"),
            "lineTotal": line.get("lineTotal"),
            "discountAmount": None,
            "priceKind": "REGULAR",
        }
        by_no[line["lineNo"]] = out
        payload["lines"].append(out)
    return payload
