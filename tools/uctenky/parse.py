#!/usr/bin/env python3
"""Normalizace OCR textu účtenky do formátu receipt-v1 (krok 6 a CLI).

    python3 tools/uctenky/parse.py --dir uctenky --report

Ke každému <jmeno>.ocr.txt vznikne <jmeno>.receipt.json. Normalizace je čistě
deterministická — žádný jazykový model, viz normalize.py.
"""

from __future__ import annotations

import argparse
import json
import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import lines as lines_mod  # noqa: E402
import normalize  # noqa: E402
import profiles  # noqa: E402

SCHEMA_VERSION = 1
# Tolerance kontrolního součtu. Na reálných účtenkách sedí na haléř; nenulová je jen
# proto, aby jeden zaokrouhlovací řádek neshodil celý import.
TOTAL_TOLERANCE = Decimal("0.02")


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


def build_document(text: str, ocr: dict | None = None, profile_name: str | None = None) -> dict:
    """Syrový OCR text na dokument receipt-v1."""
    raw_lines = normalize.clean_lines(text)
    profile = profiles.by_name(profile_name) if profile_name else profiles.detect(raw_lines)
    if profile is None:
        raise ValueError(
            "účtenku nezná žádný profil — podporované: " + ", ".join(profiles.names())
        )

    header = profile.read_header(raw_lines)
    body, footer = profile.split_body(raw_lines)
    parsed = lines_mod.parse_item_lines(body)

    computed = sum(
        (line.line_total for line in parsed.lines if line.line_total is not None), Decimal(0)
    )
    warnings = list(parsed.warnings)
    if footer.printed_total is None:
        warnings.append("na účtence se nenašel řádek s celkovou částkou")
        matches = False
    else:
        matches = abs(computed - footer.printed_total) <= TOTAL_TOLERANCE
        if not matches:
            warnings.append(
                f"součet položek {computed} neodpovídá vytištěné částce {footer.printed_total}"
            )
    if footer.purchased_at is None:
        warnings.append("na účtence se nenašlo datum nákupu")

    merchant = {
        "rawName": header.raw_name,
        "chainSlug": header.chain_slug,
        "country": header.country,
        "street": header.street,
        "postalCode": header.postal_code,
        "city": header.city,
    }
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "profile": profile.name,
        "ocr": ocr or {},
        "merchant": {key: value for key, value in merchant.items() if value is not None},
        "purchasedAt": footer.purchased_at.isoformat() if footer.purchased_at else None,
        "currency": profile.currency,
        "printedTotal": (
            normalize.format_amount(footer.printed_total) if footer.printed_total else None
        ),
        "computedTotal": normalize.format_amount(computed),
        "totalMatches": matches,
        "lines": [_line_to_json(line) for line in parsed.lines],
        "warnings": warnings,
    }
    return document


# Ze souboru <jmeno>.ocr.json se do dokumentu přebírá jen tenhle výřez. Prompt, trvání
# a název místního souboru jsou pro ladění u mě na disku, na server nepatří.
OCR_META_FIELDS = ("model", "imageSha256", "ocrAt")


def _read_ocr_meta(ocr_txt: Path) -> dict:
    meta = ocr_txt.with_suffix(".json")
    if not meta.exists():
        return {}
    stored = json.loads(meta.read_text(encoding="utf-8"))
    return {key: stored[key] for key in OCR_META_FIELDS if key in stored}


def parse_file(ocr_txt: Path, profile_name: str | None = None) -> tuple[Path, dict]:
    document = build_document(
        ocr_txt.read_text(encoding="utf-8"), _read_ocr_meta(ocr_txt), profile_name
    )
    target = ocr_txt.with_name(ocr_txt.name.removesuffix(".ocr.txt") + ".receipt.json")
    target.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return target, document


def _report(name: str, document: dict) -> None:
    items = sum(1 for line in document["lines"] if line["kind"] == lines_mod.ITEM)
    mark = "OK " if document["totalMatches"] else "!! "
    print(
        f"{mark}{name}: {items} položek, součet {document['computedTotal']}"
        f" / tištěno {document['printedTotal']}"
    )
    for warning in document["warnings"]:
        print(f"     ! {warning}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Normalizace OCR textu účtenky do receipt-v1")
    parser.add_argument("files", nargs="*", type=Path, help="konkrétní *.ocr.txt soubory")
    parser.add_argument("--dir", type=Path, help="adresář, ve kterém se hledají *.ocr.txt")
    parser.add_argument("--profile", help=f"vynutí profil ({', '.join(profiles.names())})")
    parser.add_argument("--report", action="store_true", help="vypíše přehled místo ticha")
    args = parser.parse_args()

    targets = sorted(args.files)
    if args.dir:
        targets += sorted(args.dir.glob("*.ocr.txt"))
    if not targets:
        parser.error("zadej soubory nebo --dir")

    failed = 0
    for source in targets:
        try:
            written, document = parse_file(source, args.profile)
        except ValueError as error:
            print(f"!! {source.name}: {error}", file=sys.stderr)
            failed += 1
            continue
        if args.report:
            _report(written.name, document)
        if not document["totalMatches"]:
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
