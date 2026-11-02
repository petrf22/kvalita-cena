#!/usr/bin/env python3
"""Kurátorství označení z účtenek na lokální stanici provozovatele (README.md, „Kurátorství").

    KC_API_URL=https://api.kvalitacena.cz KC_CURATOR_TOKEN=kcw_... python3 tools/uctenky/curate.py
    tools/uctenky/run-curate.sh --chain 1

Projde nespárovaná označení ze všech účtenek, seskupená podle řetězce (bez účtenky a bez
uživatele — docs/soukromi.md). U každého ukáže syrový tisk, čitelný název a nejpodobnější
zboží. Provozovatel může:

- opravit čitelný název — oprava se zapíše do slovnik/nazvy.tsv (verzovaný v gitu, worker ho
  použije u dalších účtenek „už během normalizace");
- přidat zkratku do slovnik/zkratky.tsv a název přepočítat;
- spárovat označení se zbožím — mapování platí hned (ACTIVE/OPERATOR) a nespárované řádky
  ostatních účtenek dostanou návrh. Cenu za nikoho nezapisuje, tu dál potvrzuje vlastník.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ai_pretty  # noqa: E402
import api as api_mod  # noqa: E402
import ocr as ocr_mod  # noqa: E402
import pretty  # noqa: E402

HELP = """příkazy:
  Enter            spárovat s 1. kandidátem (jen když nějaký je)
  číslo            spárovat s kandidátem z výpisu
  h [dotaz]        hledat zboží (bez dotazu podle čitelného názvu)
  e <název>        opravit čitelný název (uloží se do slovnik/nazvy.tsv)
  z ZKR=rozepsání  přidat zkratku pro všechny řetězce (slovnik/zkratky.tsv)
  zr ZKR=rozepsání přidat zkratku jen pro tento řetězec
  s                přeskočit
  q                konec
  ?                tahle nápověda"""


def search_query(name: str) -> str:
    """Dotaz na zboží z čitelného názvu — bez čísel (gramáž v názvu zboží často není)."""
    return " ".join(token for token in name.split() if not re.search(r"\d", token))


class Session:
    def __init__(self, api: api_mod.CuratorApi, dictionary: pretty.Dictionary, dict_dir: Path,
                 prettifier=None, read=input, write=print) -> None:
        self.api = api
        self.dictionary = dictionary
        self.dict_dir = dict_dir
        self.prettifier = prettifier
        self.read = read
        self.write = write
        self.mapped = 0

    def display_name(self, item: dict) -> str:
        """Čitelný název podle AKTUÁLNÍHO slovníku — po přidání zkratky se hned projeví."""
        local = pretty.pretty_label(item["label"], item.get("chainSlug"), self.dictionary)
        polished = ai_pretty.polish([local], self.prettifier, self.write)[0]
        return polished.text

    def run(self, items: list[dict]) -> int:
        self.write(f"{len(items)} nespárovaných označení. ? = nápověda")
        for index, item in enumerate(items, 1):
            if not self.review(index, len(items), item):
                break
        self.write(f"\nhotovo: spárováno {self.mapped} označení")
        return self.mapped

    def review(self, index: int, total: int, item: dict) -> bool:
        """False = konec celé relace."""
        scope = item.get("chainName") or item.get("storeName") or "?"
        name = self.display_name(item)
        hint = f", gramáž {item['netContentValue']} {item['netContentUom']}" if item.get("netContentUom") else ""
        self.write(f"\n[{index}/{total}] {scope}: {item['label']}   ({item['lineCount']}× na účtenkách, "
                   f"naposledy {item.get('lastSeenDay')}{hint})")
        self.write(f"      čitelně: {name}")
        candidates = self.search(item, search_query(name))
        while True:
            answer = self.read("> ").strip()
            if answer == "q":
                return False
            if answer == "s":
                return True
            if answer == "?":
                self.write(HELP)
                continue
            if answer.startswith("e "):
                name = answer[2:].strip()
                pretty.append_row(self.dict_dir / pretty.OVERRIDES_FILE, [item["label"], name, item.get("chainSlug") or ""])
                self.dictionary.add_override(item["label"], name, item.get("chainSlug"))
                self.write(f"      uloženo do {pretty.OVERRIDES_FILE}: {name}")
                candidates = self.search(item, search_query(name))
                continue
            if answer.startswith(("z ", "zr ")):
                chain = item.get("chainSlug") if answer.startswith("zr ") else None
                rule = answer.split(" ", 1)[1]
                if "=" not in rule:
                    self.write("      formát: z ZKR=rozepsání")
                    continue
                token, expansion = (part.strip() for part in rule.split("=", 1))
                pretty.append_row(self.dict_dir / pretty.ABBREVIATIONS_FILE, [token, expansion, chain or ""])
                self.dictionary.add_abbreviation(token, expansion, chain)
                name = self.display_name(item)
                self.write(f"      čitelně: {name}")
                continue
            if answer == "h" or answer.startswith("h "):
                candidates = self.search(item, answer[1:].strip() or search_query(name))
                continue
            if answer == "" and candidates:
                answer = "1"
            if answer.isdigit() and 1 <= int(answer) <= len(candidates):
                if self.map(item, candidates[int(answer) - 1]):
                    return True
                continue
            self.write("      ? = nápověda")

    def search(self, item: dict, query: str) -> list[dict]:
        if not query:
            self.write("      (prázdný dotaz — h <dotaz>)")
            return []
        candidates = self.api.search_products(query, item.get("sampleStoreId"))
        if not candidates:
            self.write(f"      nic podobného „{query}“ — h <dotaz>, nebo s")
        for number, product in enumerate(candidates, 1):
            amount = (f" {product['netContentValue']} {product['netContentUom']}"
                      if product.get("netContentUom") else "")
            weight = " (váhové)" if product.get("variableWeight") else ""
            brand = f" [{product['brand']}]" if product.get("brand") else ""
            draft = " DRAFT" if product.get("status") == "DRAFT" else ""
            self.write(f"  {number:2d}. {product['name']}{brand}{amount}{weight}{draft}"
                       f"   #{product['id']} {product.get('score', 0):.2f}")
        return candidates

    def map(self, item: dict, product: dict) -> bool:
        chain_id, store_id = item.get("chainId"), item.get("storeId")
        result = self.api.map_label(item["label"], chain_id, store_id, product["id"])
        if result.get("outcome") == "CONFLICT":
            confirm = self.read(f"      označení už míří na zboží #{result.get('existingProductId')}. "
                                f"Přepsat? [a/N] ").strip().lower()
            if confirm != "a":
                return False
            result = self.api.map_label(item["label"], chain_id, store_id, product["id"], replace=True)
        if result.get("outcome") == "INVALID":
            self.write(f"      !! {result.get('message')}")
            return False
        self.mapped += 1
        self.write(f"      ✓ {product['name']} — {result.get('suggestedLines', 0)} řádků účtenek dostalo návrh")
        return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Kurátorství označení z účtenek")
    parser.add_argument("--api", default=os.environ.get("KC_API_URL", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("KC_CURATOR_TOKEN"))
    parser.add_argument("--chain", type=int, help="jen jeden řetězec (id)")
    parser.add_argument("--limit", type=int, default=200)
    parser.add_argument("--ollama", default=os.environ.get("OLLAMA_HOST", ocr_mod.DEFAULT_HOST))
    parser.add_argument("--pretty-model", default=os.environ.get("PRETTY_MODEL", ""),
                        help=f"dočistit čitelné názvy modelem (např. {ai_pretty.DEFAULT_MODEL})")
    parser.add_argument("--dict-dir", type=Path, default=pretty.DICT_DIR)
    args = parser.parse_args(argv)
    if not args.token:
        parser.error("chybí token (--token nebo KC_CURATOR_TOKEN) — viz README.md, „Kurátorství“")

    curator = api_mod.CuratorApi(args.api, args.token)
    prettifier = ai_pretty.OllamaPrettifier(args.ollama, args.pretty_model) if args.pretty_model else None
    session = Session(curator, pretty.load_dictionary(args.dict_dir), args.dict_dir, prettifier)
    try:
        session.run(curator.unmatched(args.chain, args.limit))
    except (KeyboardInterrupt, EOFError):
        print(f"\npřerušeno, spárováno {session.mapped} označení")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
