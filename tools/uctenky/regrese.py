#!/usr/bin/env python3
"""Regresní sada rozboru účtenek a měření kvality OCR — bez serveru a bez tokenu workeru.

    python3 tools/uctenky/regrese.py                    # porovná všechny případy s očekáváním
    python3 tools/uctenky/regrese.py uctenky/40         # jen vybraný případ nebo kořen
    python3 tools/uctenky/regrese.py --add              # založí expected.json tam, kde chybí
    python3 tools/uctenky/regrese.py --update           # přijme změny (přepíše expected.json)
    python3 tools/uctenky/regrese.py --ocr --model glm-ocr:bf16     # změří OCR model
    python3 tools/uctenky/regrese.py --compare a.json b.json        # porovná dvě měření

PŘÍPAD je adresář, který přímo obsahuje díly účtenky `*.ocr.txt` (v pořadí podle názvu, shora
dolů) a vedle nich `expected.json` — dokument z `parse.build_document`, tedy přesně to, co
z téhož textu udělá worker. Porovnává se jen deterministická část (regexy a profily řetězců);
jazykový model do ní nevstupuje, takže výsledek je vždy stejný a rozdíl znamená změnu kódu.

Kořeny, které se prohledají, když není zadáno nic:
  tools/uctenky/testdata/regrese/   anonymizované případy v gitu, běží v CI (test_regrese.py)
  uctenky/                          skutečné účtenky provozovatele, mimo git (.gitignore) — tady
                                    sada roste s každou účtenkou, která prošla; expected.json
                                    leží vedle nich a do repozitáře se také nedostane

`expected.json` je pravda jen tehdy, když ho někdo ZKONTROLOVAL proti účtence. `--add` proto
vypíše rozbor a očekávání založí jen u účtenky, které sedí součet a nemá varování; ostatní chce
`--add --force` po ruční kontrole. `--update` přepisuje jen na výslovný pokyn — diff
anonymizovaných případů je pak vidět v gitu a patří do review.

MĚŘENÍ (`--ocr`): pro případy, které mají vedle `NN.ocr.txt` i obrázek stejného jména
(`NN.jpg`), přečte obrázky zadaným modelem znovu a výsledek porovná s `expected.json`. Čísla
(sedící součet, shodné řádky, podobnost názvů) uloží do `<kořen>/metriky/` — dva běhy s různým
modelem pak porovná `--compare`. Bez toho nejde říct, jestli výměna modelu pomohla.
"""

from __future__ import annotations

import argparse
import datetime as dt
import difflib
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ocr as ocr_mod  # noqa: E402
import parse  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
GIT_ROOT = HERE / "testdata" / "regrese"
LOCAL_ROOT = REPO_ROOT / "uctenky"
EXPECTED = "expected.json"
OCR_SUFFIX = ".ocr.txt"
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png")
# Pole řádku, která rozhodují o ceně — shoda v nich je „řádek přečtený správně".
LINE_KEY_FIELDS = ("kind", "quantity", "quantityBasis", "unitPrice", "lineTotal")


def default_roots() -> list[Path]:
    return [root for root in (GIT_ROOT, LOCAL_ROOT) if root.is_dir()]


def find_cases(roots: list[Path]) -> list[Path]:
    """Adresáře s aspoň jedním dílem `*.ocr.txt`, seřazené; kořen sám může být případem."""
    cases = set()
    for root in roots:
        if not root.is_dir():
            raise SystemExit(f"adresář neexistuje: {root}")
        for path in root.rglob("*" + OCR_SUFFIX):
            cases.add(path.parent)
    return sorted(cases)


def parts_of(case: Path) -> list[Path]:
    return sorted(case.glob("*" + OCR_SUFFIX))


def read_texts(case: Path) -> list[str]:
    return [part.read_text(encoding="utf-8") for part in parts_of(case)]


def analyze(case: Path) -> dict:
    """Totéž, co s texty udělá worker — včetně automatické volby profilu."""
    return parse.build_document(read_texts(case))


def load_expected(case: Path) -> dict | None:
    path = case / EXPECTED
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def save_expected(case: Path, document: dict) -> None:
    text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    (case / EXPECTED).write_text(text, encoding="utf-8")


def diff_documents(expected: dict, actual: dict) -> list[str]:
    """Čitelný rozdíl dvou dokumentů; prázdný seznam = shoda."""
    if expected == actual:
        return []
    old = json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True).splitlines()
    new = json.dumps(actual, ensure_ascii=False, indent=2, sort_keys=True).splitlines()
    return [line for line in difflib.unified_diff(old, new, "očekáváno", "teď", lineterm="", n=2)]


def display_name(case: Path) -> str:
    try:
        return str(case.relative_to(REPO_ROOT))
    except ValueError:
        return str(case)


def clean(document: dict) -> bool:
    return bool(document["totalMatches"]) and not document["warnings"]


def describe(document: dict) -> str:
    mark = "součet sedí" if document["totalMatches"] else "SOUČET NESEDÍ"
    return (f"profil {document['profile']}, {len(document['lines'])} řádků, {mark} "
            f"({document['computedTotal']} / {document['printedTotal']}), varování {len(document['warnings'])}")


def run_check(cases: list[Path], add: bool, update: bool, force: bool, log=print) -> int:
    """Porovná případy s očekáváním. Vrací počet případů, které se liší."""
    same = changed = missing = created = 0
    profiles: dict[str, int] = {}
    for case in cases:
        name = display_name(case)
        actual = analyze(case)
        expected = load_expected(case)
        if expected is None:
            if add and (force or clean(actual)):
                save_expected(case, actual)
                created += 1
                profiles[actual["profile"]] = profiles.get(actual["profile"], 0) + 1
                log(f"+  {name}: založeno ({describe(actual)})")
            elif add:
                missing += 1
                log(f"?  {name}: NEzaloženo — {describe(actual)}")
                for warning in actual["warnings"]:
                    log(f"       ! {warning}")
                log("       zkontroluj rozbor (replay.py) a přidej s --add --force")
            else:
                missing += 1
                log(f"?  {name}: chybí {EXPECTED} ({describe(actual)}) — založ přes --add")
            continue

        profiles[expected["profile"]] = profiles.get(expected["profile"], 0) + 1
        difference = diff_documents(expected, actual)
        if not difference:
            same += 1
            continue
        if update:
            save_expected(case, actual)
            log(f"~  {name}: očekávání přepsáno")
        else:
            log(f"!! {name}: rozbor se změnil")
        for line in difference:
            log("     " + line)
        changed += 1

    summary = ", ".join(f"{profile} {count}×" for profile, count in sorted(profiles.items())) or "žádné"
    log(f"\n{len(cases)} případů: {same} beze změny, {changed} {'přepsáno' if update else 'se liší'}, "
        f"{created} založeno, {missing} bez očekávání. Profily s očekáváním: {summary}.")
    return 0 if update else changed


# --- měření OCR modelu ---

def images_of(case: Path) -> list[Path] | None:
    """Obrázek ke každému dílu (`00.ocr.txt` ↔ `00.jpg`); `None`, když některý chybí."""
    images = []
    for part in parts_of(case):
        stem = part.name[: -len(OCR_SUFFIX)]
        found = next((case / (stem + suffix) for suffix in IMAGE_SUFFIXES if (case / (stem + suffix)).is_file()), None)
        if found is None:
            return None
        images.append(found)
    return images


def _line_key(line: dict) -> tuple:
    return tuple(line.get(field) for field in LINE_KEY_FIELDS)


def score(expected: dict, actual: dict) -> dict:
    """Jak blízko je rozbor nového OCR textu k ověřenému očekávání.

    `linesMatched` = řádky shodné v druhu, množství a cenách, spárované ve stejném pořadí
    (vypadlý nebo přidaný řádek tedy neposune všechny za ním). `labelSimilarity` = průměrná
    znaková podobnost názvů na takto spárovaných řádcích — cena může sedět a název ne.
    """
    expected_keys = [_line_key(line) for line in expected["lines"]]
    actual_keys = [_line_key(line) for line in actual["lines"]]
    matcher = difflib.SequenceMatcher(None, expected_keys, actual_keys, autojunk=False)
    matched = 0
    similarities = []
    for block in matcher.get_matching_blocks():
        for offset in range(block.size):
            matched += 1
            label_expected = expected["lines"][block.a + offset].get("rawLabel", "")
            label_actual = actual["lines"][block.b + offset].get("rawLabel", "")
            similarities.append(difflib.SequenceMatcher(None, label_expected, label_actual, autojunk=False).ratio())
    return {
        "profileMatches": expected["profile"] == actual["profile"],
        "totalMatches": bool(actual["totalMatches"]),
        "printedTotalMatches": expected["printedTotal"] == actual["printedTotal"],
        "purchasedAtMatches": expected["purchasedAt"] == actual["purchasedAt"],
        "linesExpected": len(expected_keys),
        "linesActual": len(actual_keys),
        "linesMatched": matched,
        "labelSimilarity": round(sum(similarities) / len(similarities), 4) if similarities else 0.0,
        "identical": expected == actual,
    }


def summarize(results: dict[str, dict]) -> dict:
    count = len(results)
    if count == 0:
        return {"cases": 0}
    lines_expected = sum(r["linesExpected"] for r in results.values())
    lines_matched = sum(r["linesMatched"] for r in results.values())
    weighted = sum(r["labelSimilarity"] * r["linesMatched"] for r in results.values())

    def share(field: str) -> float:
        return round(sum(1 for r in results.values() if r[field]) / count, 4)

    return {
        "cases": count,
        "identical": share("identical"),
        "totalMatches": share("totalMatches"),
        "printedTotalMatches": share("printedTotalMatches"),
        "purchasedAtMatches": share("purchasedAtMatches"),
        "profileMatches": share("profileMatches"),
        "linesMatched": round(lines_matched / lines_expected, 4) if lines_expected else 0.0,
        "labelSimilarity": round(weighted / lines_matched, 4) if lines_matched else 0.0,
    }


SUMMARY_LABELS = (
    ("identical", "rozbor úplně shodný"),
    ("totalMatches", "sedí součet"),
    ("printedTotalMatches", "správně přečtená celková částka"),
    ("purchasedAtMatches", "správně přečtené datum"),
    ("profileMatches", "správně poznaný řetězec"),
    ("linesMatched", "řádky se správnou cenou a množstvím"),
    ("labelSimilarity", "podobnost názvů na těch řádcích"),
)


def print_summary(summary: dict, log=print) -> None:
    for key, label in SUMMARY_LABELS:
        log(f"  {label:<38} {summary.get(key, 0) * 100:6.1f} %")


def run_ocr(cases: list[Path], extractor, output_dir: Path | None, log=print) -> dict:
    """Přečte obrázky případů modelem a změří rozbor proti očekávání."""
    results: dict[str, dict] = {}
    skipped = 0
    for case in cases:
        expected = load_expected(case)
        images = images_of(case)
        if expected is None or not images:
            skipped += 1
            continue
        name = display_name(case)
        log(f"OCR {name} ({len(images)} díl/ů) …", file=sys.stderr)
        try:
            actual = parse.build_document([extractor.ocr(image.read_bytes()) for image in images])
        except Exception as error:  # noqa: BLE001 — výpadek modelu u jedné účtenky nesmí zahodit zbytek měření
            log(f"    selhalo: {error}", file=sys.stderr)
            skipped += 1
            continue
        results[name] = score(expected, actual)
        row = results[name]
        log(f"    řádky {row['linesMatched']}/{row['linesExpected']}, názvy {row['labelSimilarity'] * 100:.0f} %, "
            f"součet {'sedí' if row['totalMatches'] else 'NESEDÍ'}", file=sys.stderr)

    report = {
        "model": extractor.model,
        "measuredAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "summary": summarize(results),
        "cases": results,
    }
    log(f"\nModel {extractor.model}: {len(results)} účtenek změřeno, {skipped} přeskočeno "
        f"(bez {EXPECTED} nebo bez obrázku ke každému dílu).")
    if results:
        print_summary(report["summary"], log)
    if output_dir is not None and results:
        output_dir.mkdir(parents=True, exist_ok=True)
        safe_model = re.sub(r"[^A-Za-z0-9._-]+", "_", extractor.model)
        target = output_dir / f"{dt.date.today().isoformat()}-{safe_model}.json"
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        log(f"Uloženo: {target}")
    return report


def run_compare(first: Path, second: Path, log=print) -> int:
    """Dvě měření vedle sebe — jen nad účtenkami, které jsou v obou."""
    a = json.loads(first.read_text(encoding="utf-8"))
    b = json.loads(second.read_text(encoding="utf-8"))
    common = sorted(set(a["cases"]) & set(b["cases"]))
    if not common:
        log("Měření nemají žádnou společnou účtenku — není co porovnat.")
        return 1
    summary_a = summarize({name: a["cases"][name] for name in common})
    summary_b = summarize({name: b["cases"][name] for name in common})
    log(f"Společných účtenek: {len(common)} (A má {len(a['cases'])}, B má {len(b['cases'])})")
    log(f"  {'':<38} {'A: ' + a['model']:>22} {'B: ' + b['model']:>22}   rozdíl")
    for key, label in SUMMARY_LABELS:
        value_a, value_b = summary_a[key] * 100, summary_b[key] * 100
        log(f"  {label:<38} {value_a:20.1f} % {value_b:20.1f} %  {value_b - value_a:+6.1f}")
    worse = [name for name in common if b["cases"][name]["linesMatched"] < a["cases"][name]["linesMatched"]]
    better = [name for name in common if b["cases"][name]["linesMatched"] > a["cases"][name]["linesMatched"]]
    log(f"\nB přečetlo víc řádků správně u {len(better)} účtenek, méně u {len(worse)}.")
    for name in worse:
        log(f"  horší v B: {name} ({a['cases'][name]['linesMatched']} → {b['cases'][name]['linesMatched']} "
            f"z {a['cases'][name]['linesExpected']})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Regresní sada rozboru účtenek a měření OCR")
    parser.add_argument("paths", nargs="*", type=Path, help="kořeny nebo jednotlivé případy (výchozí: sada v gitu + uctenky/)")
    parser.add_argument("--add", action="store_true", help=f"založit {EXPECTED} tam, kde chybí (jen čisté účtenky)")
    parser.add_argument("--force", action="store_true", help="s --add založit i účtenku s varováním nebo nesedícím součtem")
    parser.add_argument("--update", action="store_true", help=f"přepsat {EXPECTED} aktuálním rozborem")
    parser.add_argument("--ocr", action="store_true", help="přečíst obrázky modelem a změřit proti očekávání")
    parser.add_argument("--ollama", default=os.environ.get("OLLAMA_HOST", ocr_mod.DEFAULT_HOST))
    parser.add_argument("--model", default=os.environ.get("OCR_MODEL", ocr_mod.DEFAULT_MODEL))
    parser.add_argument("--output", type=Path, help="kam uložit měření (výchozí: uctenky/metriky/)")
    parser.add_argument("--compare", nargs=2, type=Path, metavar=("A", "B"), help="porovnat dvě uložená měření")
    args = parser.parse_args(argv)

    if args.compare:
        return run_compare(*args.compare)
    if args.force and not args.add:
        parser.error("--force dává smysl jen s --add")

    cases = find_cases(args.paths or default_roots())
    if not cases:
        print("Žádný případ (adresář s díly *.ocr.txt) se nenašel.")
        return 1
    if args.ocr:
        report = run_ocr(cases, ocr_mod.OllamaOcr(args.ollama, args.model), args.output or LOCAL_ROOT / "metriky")
        return 0 if report["cases"] else 1
    return 1 if run_check(cases, args.add, args.update, args.force) else 0


if __name__ == "__main__":
    raise SystemExit(main())
