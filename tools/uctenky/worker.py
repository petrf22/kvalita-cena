#!/usr/bin/env python3
"""Pull worker účtenek — běží na PC provozovatele (docs/ai.md, „Účtenky").

    KC_API_URL=https://api.kvalitacena.cz KC_WORKER_TOKEN=kcw_... python3 tools/uctenky/worker.py
    python3 tools/uctenky/worker.py --once          # jedna dávka a konec (cron/systemd timer)

Cyklus: vyzvedne dávku účtenek (lease), každou část přečte OCR modelem (obrázek -> text),
text deterministicky rozebere (parse.py) a výsledek pošle zpět. Server z něj udělá NÁVRH,
cenu zapíše až potvrzení uživatele. Chyba jedné účtenky se nahlásí (`fail`) a pokračuje se
další — po několika pokusech ji server označí FAILED.

Volitelně (ARCHIVE_DIR) stáhne obrázky účtenek, u kterých uživatel souhlasil s archivací,
dřív než je server smaže (docs/soukromi.md).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import api as api_mod  # noqa: E402
import ocr as ocr_mod  # noqa: E402
import parse  # noqa: E402
import result as result_mod  # noqa: E402


def process_job(api: api_mod.WorkerApi, extractor: ocr_mod.Extractor, job: dict, log=print) -> str:
    receipt_id = job["receiptId"]
    texts = []
    for path in job["imagePaths"]:
        texts.append(extractor.ocr(api.image(path)))
    document = parse.build_document(texts)
    payload = result_mod.to_payload(document, parse.server_text(texts), extractor.model)
    api.result(receipt_id, payload)
    items = sum(1 for line in payload["lines"])
    mark = "OK " if document["totalMatches"] else "!! "
    log(f"{mark}účtenka {receipt_id}: {payload['status']}, {items} řádků, profil {document['profile']}, "
        f"součet {document['computedTotal']} / tištěno {document['printedTotal']}")
    for warning in document["warnings"]:
        log(f"     ! {warning}")
    return payload["status"]


def run_batch(api: api_mod.WorkerApi, extractor: ocr_mod.Extractor, limit: int, log=print) -> int:
    jobs = api.lease(limit)
    for job in jobs:
        try:
            process_job(api, extractor, job, log)
        except Exception as error:  # noqa: BLE001 — jedna vadná účtenka nesmí zastavit dávku
            log(f"!! účtenka {job.get('receiptId')}: {error}")
            try:
                api.fail(job["receiptId"], f"{type(error).__name__}: {error}")
            except Exception as report_error:  # noqa: BLE001
                log(f"!! nepodařilo se nahlásit chybu: {report_error}")
    return len(jobs)


def archive(api: api_mod.WorkerApi, target: Path, log=print) -> int:
    """Obrázky účtenek se souhlasem s archivací do lokální složky (mimo git)."""
    done = 0
    for candidate in api.archive_candidates():
        receipt_dir = target / str(candidate["receiptId"])
        receipt_dir.mkdir(parents=True, exist_ok=True)
        for index, path in enumerate(candidate["imagePaths"]):
            (receipt_dir / f"{index:02d}.jpg").write_bytes(api.image(path))
        (receipt_dir / "meta.json").write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
        api.archived(candidate["receiptId"])
        done += 1
    if done:
        log(f"archiv: uloženo {done} účtenek do {target}")
    return done


def main() -> int:
    parser = argparse.ArgumentParser(description="Pull worker účtenek")
    parser.add_argument("--api", default=os.environ.get("KC_API_URL", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("KC_WORKER_TOKEN"))
    parser.add_argument("--ollama", default=os.environ.get("OLLAMA_HOST", ocr_mod.DEFAULT_HOST))
    parser.add_argument("--model", default=os.environ.get("OCR_MODEL", ocr_mod.DEFAULT_MODEL))
    parser.add_argument("--archive-dir", type=Path,
                        default=Path(os.environ["ARCHIVE_DIR"]) if os.environ.get("ARCHIVE_DIR") else None)
    parser.add_argument("--batch", type=int, default=5)
    parser.add_argument("--interval", type=int, default=300, help="sekund mezi dávkami, když je fronta prázdná")
    parser.add_argument("--once", action="store_true", help="jedna dávka a konec")
    args = parser.parse_args()
    if not args.token:
        parser.error("chybí token (--token nebo KC_WORKER_TOKEN) — viz docs/nasazeni.md, „Worker účtenek“")

    api = api_mod.WorkerApi(args.api, args.token)
    extractor = ocr_mod.OllamaOcr(args.ollama, args.model)
    while True:
        try:
            processed = run_batch(api, extractor, args.batch)
            if args.archive_dir:
                archive(api, args.archive_dir)
        except (api_mod.ApiError, OSError) as error:
            # Výpadek serveru nebo sítě — degradace, ne pád (docs/ai.md).
            print(f"!! server nedostupný: {error}", file=sys.stderr)
            processed = 0
        if args.once:
            return 0
        if processed == 0:
            time.sleep(args.interval)


if __name__ == "__main__":
    raise SystemExit(main())
