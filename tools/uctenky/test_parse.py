#!/usr/bin/env python3
"""Regresní testy parseru účtenek.

    python3 -m unittest discover -s tools/uctenky

Fixtures v testdata/ jsou ANONYMIZOVANÉ — reálné účtenky do repozitáře nepatří
(adresa provozovny + přesný čas nákupu identifikují zákazníka, docs/soukromi.md).
Očekávané výstupy vedle nich jsou zlatá kopie: co je jednou správně rozebrané,
nesmí příští úprava regexu tiše rozbít.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import lines as lines_mod  # noqa: E402
import normalize  # noqa: E402
from parse import build_document  # noqa: E402

TESTDATA = Path(__file__).resolve().parent / "testdata"


def _document(name: str) -> dict:
    return build_document((TESTDATA / f"{name}.ocr.txt").read_text(encoding="utf-8"))


def _expected(name: str) -> dict:
    return json.loads((TESTDATA / f"{name}.receipt.json").read_text(encoding="utf-8"))


class GoldenFileTest(unittest.TestCase):
    """Celý dokument proti uložené zlaté kopii."""

    def test_plna_uctenka(self):
        self.assertEqual(_document("albert-plny"), _expected("albert-plny"))

    def test_uctenka_ktera_nesedi(self):
        self.assertEqual(_document("albert-nesedi"), _expected("albert-nesedi"))


class ChecksumTest(unittest.TestCase):
    """Kontrolní součet je brána kvality — účtenka, která nesedí, nesmí projít jako v pořádku."""

    def test_soucet_sedi(self):
        document = _document("albert-plny")
        self.assertTrue(document["totalMatches"])
        self.assertEqual(document["computedTotal"], document["printedTotal"])

    def test_soucet_nesedi_a_hlasi_se(self):
        document = _document("albert-nesedi")
        self.assertFalse(document["totalMatches"])
        self.assertTrue(any("neodpovídá" in w for w in document["warnings"]))

    def test_nerozpoznany_radek_se_neztrati(self):
        document = _document("albert-nesedi")
        unknown = [line for line in document["lines"] if line["kind"] == lines_mod.UNKNOWN]
        self.assertEqual([line["rawLabel"] for line in unknown], ["ROZMAZANÝ ŘÁDEK BEZ CENY"])


class WeightedGoodsTest(unittest.TestCase):
    """Nejpravděpodobnější tichá chyba celé funkce (docs/rozvoj.md)."""

    def setUp(self):
        self.lines = {line["rawLabel"]: line for line in _document("albert-plny")["lines"]}

    def test_jednotkova_cena_je_za_kilo_ne_zaplacena_castka(self):
        losos = self.lines["LOSOS FILET ASC"]
        self.assertEqual(losos["quantityBasis"], "PER_KG")
        self.assertEqual(losos["unitPrice"], "289.00")
        self.assertEqual(losos["lineTotal"], "87.30")

    def test_gramy_se_prepoctou_na_kilogramy(self):
        syr = self.lines["SÝR VÁŽENÝ"]
        self.assertEqual(syr["quantity"], "0.25")
        self.assertEqual(syr["quantityBasis"], "PER_KG")
        self.assertEqual(syr["unitPrice"], "320.00")

    def test_objem_ma_vlastni_zaklad(self):
        olej = self.lines["OLEJ STÁČENÝ"]
        self.assertEqual(olej["quantityBasis"], "PER_L")
        self.assertEqual(olej["unitPrice"], "89.00")

    def test_znacka_vahoveho_zbozi_neni_soucast_nazvu(self):
        self.assertNotIn("LOSOS FILET ASC E", self.lines)


class PackagePriceTest(unittest.TestCase):
    def test_nasobnost_nemeni_cenu_baleni(self):
        lines = {line["rawLabel"]: line for line in _document("albert-plny")["lines"]}
        rohlik = lines["ROHLÍK43GR"]
        self.assertEqual(rohlik["quantity"], "5")
        self.assertEqual(rohlik["unitPrice"], "2.90")
        self.assertEqual(rohlik["quantityBasis"], "PACKAGE")


class DiscountTest(unittest.TestCase):
    def test_sleva_je_vlastni_radek_navazany_na_polozku(self):
        document = _document("albert-plny")
        sleva = next(line for line in document["lines"] if line["kind"] == lines_mod.DISCOUNT)
        polozka = document["lines"][sleva["appliesToLineNo"] - 1]
        self.assertEqual(sleva["lineTotal"], "-79.00")
        self.assertEqual(polozka["rawLabel"], "TAPAS SPIAN.ROM.70G")


class PrivacyTest(unittest.TestCase):
    """Patička se zahazuje už v parseru — na server se nikdy nedostane."""

    def test_z_paticky_projde_jen_datum_a_castka(self):
        serialized = json.dumps(_document("albert-plny"), ensure_ascii=False)
        for leak in ("VISA", "SEQ ID", "Autoriz", "DPH", "Terminal", "bodů", "0000000"):
            self.assertNotIn(leak, serialized, f"do dokumentu prosákla patička: {leak}")

    def test_datum_a_cas_nakupu_se_precte(self):
        self.assertEqual(_document("albert-plny")["purchasedAt"], "2026-09-07T10:15:00+02:00")


class NormalizeTest(unittest.TestCase):
    def test_castka_s_mezerou_v_tisicich(self):
        self.assertEqual(str(normalize.parse_amount("1 369,20")), "1369.20")

    def test_pismeno_O_misto_nuly_jen_v_cisle(self):
        self.assertEqual(str(normalize.parse_amount("98O,2O")), "980.20")

    def test_oddelovace_a_hlavicka_sloupce_se_zahodi(self):
        self.assertEqual(normalize.clean_lines("albert\n-----\nKč\n\nROHLÍK"), ["albert", "ROHLÍK"])


if __name__ == "__main__":
    unittest.main()
