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

Po opravě OCR nebo normalizace vrátí --requeue / --requeue-since už zpracované účtenky
k novému rozpoznání (README.md, „Nové rozpoznání").
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import date, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ai_classify  # noqa: E402
import ai_pretty  # noqa: E402
import api as api_mod  # noqa: E402
import ocr as ocr_mod  # noqa: E402
import parse  # noqa: E402
import pretty  # noqa: E402
import result as result_mod  # noqa: E402


def process_job(api: api_mod.WorkerApi, extractor: ocr_mod.Extractor, job: dict, log=print,
                dictionary: pretty.Dictionary | None = None, prettifier=None,
                classifier=None, categories: list[dict] | None = None) -> str:
    receipt_id = job["receiptId"]
    if not job.get("imagePaths") and job.get("rawText"):
        # Nové rozpoznání po retenci obrázků: server pošle uložený syrový text, OCR se nedělá
        # a model se neposílá (na serveru zůstane ten, který text kdysi přečetl).
        texts, model = [job["rawText"]], None
        log(f"~  účtenka {receipt_id}: obrázky už nejsou, znovu z uloženého textu")
    else:
        texts = [extractor.ocr(api.image(path)) for path in job["imagePaths"]]
        model = extractor.model
    document = parse.build_document(texts)
    payload = result_mod.to_payload(document, parse.server_text(texts), model,
                                    dictionary, prettifier, log, classifier, categories)
    api.result(receipt_id, payload)
    items = sum(1 for line in payload["lines"])
    mark = "OK " if document["totalMatches"] else "!! "
    log(f"{mark}účtenka {receipt_id}: {payload['status']}, {items} řádků, profil {document['profile']}, "
        f"součet {document['computedTotal']} / tištěno {document['printedTotal']}")
    for warning in document["warnings"]:
        log(f"     ! {warning}")
    return payload["status"]


def run_batch(api: api_mod.WorkerApi, extractor: ocr_mod.Extractor, limit: int, log=print,
              dictionary: pretty.Dictionary | None = None, prettifier=None, classifier=None) -> int:
    jobs = api.lease(limit)
    categories = _categories(api, log) if jobs and classifier is not None else []
    for job in jobs:
        try:
            process_job(api, extractor, job, log, dictionary, prettifier, classifier, categories)
        except Exception as error:  # noqa: BLE001 — jedna vadná účtenka nesmí zastavit dávku
            log(f"!! účtenka {job.get('receiptId')}: {error}")
            try:
                api.fail(job["receiptId"], f"{type(error).__name__}: {error}", job.get("leaseUntil"))
            except Exception as report_error:  # noqa: BLE001
                log(f"!! nepodařilo se nahlásit chybu: {report_error}")
    return len(jobs)


def _categories(api: api_mod.WorkerApi, log=print) -> list[dict]:
    """Kategorie pro návrh modelem — jednou za dávku; výpadek = účtenky bez návrhu, ne chyba."""
    try:
        return api.categories()
    except (api_mod.ApiError, OSError, ValueError) as error:
        log(f"~ kategorie nedostupné ({error}), účtenky půjdou bez návrhu kategorie")
        return []


def requeue(api: api_mod.WorkerApi, receipt_ids: list[int], since: date | None, log=print) -> int:
    """Vrátí účtenky k novému rozpoznání; vypíše, co se vrátilo a co server přeskočil."""
    processed_since = datetime.combine(since, datetime.min.time()).astimezone().isoformat() if since else None
    answer = api.requeue(receipt_ids, processed_since)
    requeued = answer.get("requeued") or []
    log(f"znovu do fronty: {len(requeued)} účtenek {requeued if requeued else ''}".rstrip())
    for skipped in answer.get("skipped") or []:
        log(f"   přeskočeno {skipped.get('receiptId')}: {skipped.get('reason')}")
    return len(requeued)


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
    parser.add_argument("--pretty-model", default=os.environ.get("PRETTY_MODEL", ai_pretty.DEFAULT_MODEL),
                        help="model pro dočištění čitelných názvů (prázdné = jen slovník a pravidla)")
    parser.add_argument("--classify-model", default=os.environ.get("CLASSIFY_MODEL", ai_classify.DEFAULT_MODEL),
                        help="model pro návrh kategorie a jednotky nového zboží (prázdné = bez návrhu)")
    parser.add_argument("--archive-dir", type=Path,
                        default=Path(os.environ["ARCHIVE_DIR"]) if os.environ.get("ARCHIVE_DIR") else None)
    parser.add_argument("--batch", type=int, default=5)
    parser.add_argument("--interval", type=int, default=300, help="sekund mezi dávkami, když je fronta prázdná")
    parser.add_argument("--once", action="store_true", help="jedna dávka a konec")
    parser.add_argument("--requeue", type=int, nargs="+", default=[], metavar="ID",
                        help="před dávkou vrátit tyto zpracované účtenky k novému rozpoznání")
    parser.add_argument("--requeue-since", type=date.fromisoformat, metavar="YYYY-MM-DD",
                        help="před dávkou vrátit k novému rozpoznání všechny zpracované od data (max 500)")
    args = parser.parse_args()
    if not args.token:
        parser.error("chybí token (--token nebo KC_WORKER_TOKEN) — viz docs/nasazeni.md, „Worker účtenek“")

    api = api_mod.WorkerApi(args.api, args.token)
    if args.requeue or args.requeue_since:
        try:
            requeue(api, args.requeue, args.requeue_since)
        except (api_mod.ApiError, OSError) as error:
            print(f"!! nové rozpoznání se nepodařilo zařadit: {error}", file=sys.stderr)
            return 1
    extractor = ocr_mod.OllamaOcr(args.ollama, args.model)
    prettifier = ai_pretty.OllamaPrettifier(args.ollama, args.pretty_model) if args.pretty_model else None
    classifier = ai_classify.OllamaClassifier(args.ollama, args.classify_model) if args.classify_model else None
    while True:
        # Slovník se načítá před každou dávkou — úprava z curate.py platí bez restartu workeru.
        dictionary = pretty.load_dictionary()
        try:
            processed = run_batch(api, extractor, args.batch, dictionary=dictionary, prettifier=prettifier,
                                  classifier=classifier)
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
