#!/usr/bin/env python3
"""Offline přehrání účtenky pro ladění rozpoznání — bez serveru a bez tokenu workeru.

    python3 tools/uctenky/replay.py uctenky/123/*.jpg              # OCR (Ollama) + rozbor
    python3 tools/uctenky/replay.py --save-ocr uctenky/123/*.jpg   # navíc uloží 00.ocr.txt…
    python3 tools/uctenky/replay.py uctenky/123/*.ocr.txt          # jen rozbor, bez Ollamy
    python3 tools/uctenky/replay.py --profile albert soubor.ocr.txt

Vstupem jsou díly jedné účtenky v pořadí shora dolů: obrázky čte OCR stejně jako worker,
`.txt` se berou jako hotový OCR text. Rozbor dělá tentýž kód jako worker (parse.py), takže
varování odpovídají logu `run-worker.sh` — jen je u nich vidět, nad jakým textem vznikla.

Obrázky a OCR text skutečných účtenek do gitu nepatří (README.md, „Ladění účtenky").
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ai_pretty  # noqa: E402
import ocr as ocr_mod  # noqa: E402
import parse  # noqa: E402
import pretty  # noqa: E402
import result as result_mod  # noqa: E402

TEXT_SUFFIXES = {".txt"}


def read_parts(paths: list[Path], extractor_factory, save_ocr: bool, log=print) -> list[str]:
    """Text každého dílu — `.txt` rovnou, obrázek přes OCR (volitelně uložený vedle)."""
    texts = []
    extractor = None
    for path in paths:
        if path.suffix.lower() in TEXT_SUFFIXES:
            texts.append(path.read_text(encoding="utf-8"))
            continue
        if extractor is None:
            extractor = extractor_factory()
        log(f"OCR {path} ({extractor.model}) …", file=sys.stderr)
        text = extractor.ocr(path.read_bytes())
        if save_ocr:
            target = path.with_suffix(".ocr.txt")
            target.write_text(text, encoding="utf-8")
            log(f"     uloženo {target}", file=sys.stderr)
        texts.append(text)
    return texts


def report(texts: list[str], profile_name: str | None = None, log=print) -> dict:
    """Vypíše řádky, jak je vidí parser, rozebrané položky a varování; vrátí dokument."""
    raw_lines = parse.merge_segments(texts)
    document = parse.build_document(texts, profile_name)
    profile = parse._profile_for(raw_lines, profile_name)  # noqa: SLF001 — stejná volba jako v build_document
    _, footer = profile.split_body(raw_lines)

    log(f"== řádky po spojení dílů ({len(raw_lines)}), profil {document['profile']}")
    for index, line in enumerate(raw_lines):
        mark = "  <- celková částka" if index == footer.total_index else ""
        log(f"{index:4d}  {line}{mark}")

    log(f"\n== položky ({len(document['lines'])})")
    for line in document["lines"]:
        quantity = f"{line.get('quantity', '')} {line.get('quantityBasis', '')}".strip()
        log(f"{line['lineNo']:4d}  {line['kind']:<8} {line.get('rawLabel', ''):<32} "
            f"{quantity:<16} {line.get('unitPrice', ''):>10} {line.get('lineTotal', ''):>10}")

    mark = "OK " if document["totalMatches"] else "!! "
    log(f"\n{mark}součet {document['computedTotal']} / tištěno {document['printedTotal']}, "
        f"nákup {document['purchasedAt']}, obchod {document['merchant']}")
    for warning in document["warnings"]:
        log(f"     ! {warning}")
    return document


def report_names(document: dict, dictionary: pretty.Dictionary, prettifier=None, log=print) -> list[dict]:
    """Čitelné názvy, jak je worker pošle na server (pretty.py, ai_pretty.py)."""
    payload = result_mod.to_payload(document, None, "replay", dictionary, prettifier, log)
    log(f"\n== čitelné názvy (slovník {result_mod.chain_of(document)})")
    for line in payload["lines"]:
        hint = f"{line['netContentValue']} {line['netContentUom']}" if line.get("netContentUom") else ""
        log(f"      {line['label']:<32} -> {line.get('labelDisplay', ''):<36} "
            f"{line.get('labelDisplaySource', ''):<5} {hint}")
    return payload["lines"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Offline přehrání účtenky (OCR + rozbor)")
    parser.add_argument("parts", nargs="+", type=Path, help="díly účtenky shora dolů (obrázky nebo .txt)")
    parser.add_argument("--profile", help="vynutit profil řetězce (jinak detekce jako ve workeru)")
    parser.add_argument("--save-ocr", action="store_true", help="uložit OCR text vedle obrázku jako .ocr.txt")
    parser.add_argument("--json", action="store_true", help="vypsat celý dokument z parse.build_document")
    parser.add_argument("--ollama", default=os.environ.get("OLLAMA_HOST", ocr_mod.DEFAULT_HOST))
    parser.add_argument("--model", default=os.environ.get("OCR_MODEL", ocr_mod.DEFAULT_MODEL))
    parser.add_argument("--pretty-model", default="",
                        help=f"dočistit čitelné názvy modelem (např. {ai_pretty.DEFAULT_MODEL}; prázdné = jen slovník)")
    args = parser.parse_args(argv)
    if args.profile and parse.profiles.by_name(args.profile) is None:
        parser.error("neznámý profil: " + args.profile)

    missing = [str(path) for path in args.parts if not path.is_file()]
    if missing:
        parser.error("soubor neexistuje: " + ", ".join(missing))

    texts = read_parts(args.parts, lambda: ocr_mod.OllamaOcr(args.ollama, args.model), args.save_ocr)
    document = report(texts, args.profile)
    prettifier = ai_pretty.OllamaPrettifier(args.ollama, args.pretty_model) if args.pretty_model else None
    report_names(document, pretty.load_dictionary(), prettifier)
    if args.json:
        print(json.dumps(document, ensure_ascii=False, indent=2))
    return 0 if document["totalMatches"] and not document["warnings"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
