#!/usr/bin/env python3
"""Testy parseru a workeru účtenek.

    python3 -m unittest discover -s tools/uctenky -p 'test_*.py'

Fixtures v testdata/ jsou ANONYMIZOVANÉ — reálné účtenky do repozitáře nepatří
(adresa provozovny + přesný čas nákupu identifikují zákazníka, docs/soukromi.md).
"""

from __future__ import annotations

import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import lines as lines_mod  # noqa: E402
import normalize  # noqa: E402
import parse  # noqa: E402
import replay  # noqa: E402
import result as result_mod  # noqa: E402
import worker  # noqa: E402

TESTDATA = Path(__file__).resolve().parent / "testdata"


def _text(name: str) -> str:
    return (TESTDATA / f"{name}.ocr.txt").read_text(encoding="utf-8")


class AlbertProfileTest(unittest.TestCase):
    def setUp(self):
        self.document = parse.build_document(_text("albert-plny"))
        self.lines = {line["rawLabel"]: line for line in self.document["lines"]}

    def test_soucet_sedi(self):
        self.assertEqual(self.document["profile"], "albert")
        self.assertTrue(self.document["totalMatches"])

    def test_jednotkova_cena_je_za_kilo_ne_zaplacena_castka(self):
        losos = self.lines["LOSOS FILET ASC"]
        self.assertEqual((losos["quantityBasis"], losos["unitPrice"], losos["lineTotal"]),
                         ("PER_KG", "289.00", "87.30"))

    def test_gramy_se_prepoctou_na_kilogramy(self):
        self.assertEqual(self.lines["SÝR VÁŽENÝ"]["quantity"], "0.25")

    def test_nasobnost_nemeni_cenu_baleni(self):
        self.assertEqual(self.lines["ROHLÍK43GR"]["unitPrice"], "2.90")

    def test_z_paticky_projde_jen_datum_a_castka(self):
        serialized = json.dumps(self.document, ensure_ascii=False)
        for leak in ("VISA", "SEQ ID", "Autoriz", "DPH", "Terminal", "bodů", "0000000"):
            self.assertNotIn(leak, serialized)
        self.assertEqual(self.document["purchasedAt"], "2026-09-07T10:15:00+02:00")

    def test_syrovy_text_pro_server_nema_paticku(self):
        text = parse.server_text(_text("albert-plny"))
        self.assertIn("LOSOS FILET ASC", text)
        for leak in ("VISA", "SEQ ID", "Autoriz", "Terminal", "bodů", "0000000"):
            self.assertNotIn(leak, text)

    def test_nesedici_soucet_se_hlasi(self):
        document = parse.build_document(_text("albert-nesedi"))
        self.assertFalse(document["totalMatches"])


class GenericProfileTest(unittest.TestCase):
    TEXT = """PEKÁRNA U MLÝNA s.r.o.
Hlavní 12
123 45 Vzorov
IČ: 12345678 DIČ: CZ12345678
TUK.ROHLIK 43G
10 x 3,50 Kč 35,00 B
CHLÉB ŠUMAVA 1200G 54,90 B
SLEVA KLUB -5,00
Celkem k úhradě 84,90 Kč
Platební karta **** 1234
30.09.2026 07:42:11 Účtenka 000123
"""

    def setUp(self):
        self.document = parse.build_document(self.TEXT)

    def test_neznamy_retezec_jde_obecnym_profilem(self):
        self.assertEqual(self.document["profile"], "obecny")
        self.assertTrue(self.document["totalMatches"], self.document["warnings"])
        self.assertEqual(self.document["merchant"]["ico"], "12345678")
        self.assertEqual(self.document["merchant"]["city"], "Vzorov")
        self.assertEqual(self.document["purchasedAt"], "2026-09-30T07:42:00+02:00")

    def test_hlavicka_neni_polozka(self):
        labels = [line["rawLabel"] for line in self.document["lines"]]
        self.assertEqual(labels[0], "TUK.ROHLIK 43G")
        self.assertNotIn("Platební karta", json.dumps(self.document, ensure_ascii=False))

    def test_payload_pripoji_klubovou_slevu_k_polozce(self):
        payload = result_mod.to_payload(self.document, self.TEXT, "glm-ocr")
        self.assertEqual(payload["status"], "PROCESSED")
        chleb = next(line for line in payload["lines"] if line["label"].startswith("CHLÉB"))
        self.assertEqual((chleb["discountAmount"], chleb["priceKind"]), ("5.00", "CLUB_CARD"))
        self.assertEqual(len(payload["lines"]), 2)
        self.assertEqual(payload["storeIco"], "12345678")


    def test_payload_neposila_cislo_karty_ani_v_syrovem_textu(self):
        payload = result_mod.to_payload(self.document, parse.server_text(self.TEXT), "glm-ocr")
        serialized = json.dumps(payload, ensure_ascii=False)
        self.assertNotIn("Platební karta", serialized)
        self.assertNotIn("**** 1234", serialized)
        self.assertIn("TUK.ROHLIK 43G", payload["rawText"])

    def test_bez_celkove_castky_se_syrovy_text_neposila(self):
        self.assertIsNone(parse.server_text("TUK.ROHLIK 43G 3,50 B\nPlatební karta **** 1234"))

    def test_maskovane_cislo_karty_vypadne_i_nad_patickou(self):
        text = parse.server_text("CHLÉB 54,90 B\nKarta XXXXXXXXXXXX1234\nCelkem k úhradě 54,90 Kč")
        self.assertNotIn("1234", text)


class WeightUnitTest(unittest.TestCase):
    """Základ PER_KG/PER_L znamená cenu za kg/l — g a ml se převádí v množství i v ceně."""

    def _item(self, raw: str) -> lines_mod.ParsedLine:
        return lines_mod.parse_item_lines([raw]).lines[0]

    def test_cena_za_gram_se_prevede_na_cenu_za_kilo(self):
        line = self._item("Syr 200 g x 0,25 Kč/g 50,00 A")
        self.assertEqual((line.quantity, line.quantity_basis, line.unit_price),
                         (Decimal("0.2"), lines_mod.PER_KG, Decimal("250.00")))

    def test_kilogramy_zustanou_beze_zmeny(self):
        line = self._item("SYR EIDAM 0,200 kg x 250,00 Kč/kg 50,00 A")
        self.assertEqual((line.quantity, line.unit_price), (Decimal("0.200"), Decimal("250.00")))

    def test_cena_za_mililitr_se_prevede_na_cenu_za_litr(self):
        line = self._item("OLEJ 500 ml x 0,05 Kč/ml 25,00 A")
        self.assertEqual((line.quantity, line.quantity_basis, line.unit_price),
                         (Decimal("0.5"), lines_mod.PER_L, Decimal("50.00")))

    def test_mnozstvi_krat_cena_dal_sedi_na_zaplacenou_castku(self):
        line = self._item("Syr 200 g x 0,25 Kč/g 50,00 A")
        self.assertEqual(line.quantity * line.unit_price, line.line_total)


class PreQuantityTest(unittest.TestCase):
    """Penny tiskne množství NAD název a sazbu DPH PŘED částku (anonymizovaný tvar)."""

    TEXT = """PENNY.
PENNY MARKET S.R.O.
Nádražní 1, 10000 Vzorov
IČO: 12345678
DIČ: CZ12345678
MĚST. SOUD PRAHA C/00000
Datum: 06.09.2026    Čas: 13:07:15
Kč
3.000 ks x 36.90 Kč /ks
Balsýr bloček 47% 200g C 110.70
0.512 kg x 49.90 Kč /kg
Banány C 25.55
Celkem Kč 136.25
PRIJATO plateb.karta 136.25
06.09.2026 13:07:26
xxxx xxxx xxxx 0000
"""

    def test_cena_za_kus_ne_zaplacena_castka(self):
        document = parse.build_document(self.TEXT)
        self.assertTrue(document["totalMatches"], document["warnings"])
        balsyr, banany = [line for line in document["lines"] if line["kind"] == lines_mod.ITEM]
        self.assertEqual((balsyr["rawLabel"], balsyr["quantity"], balsyr["unitPrice"]),
                         ("Balsýr bloček 47% 200g", "3", "36.90"))
        self.assertEqual((banany["quantityBasis"], banany["unitPrice"], banany["lineTotal"]),
                         ("PER_KG", "49.90", "25.55"))
        self.assertEqual(document["purchasedAt"], "2026-09-06T13:07:00+02:00")
        self.assertNotIn("xxxx", json.dumps(document))


class SegmentsTest(unittest.TestCase):
    def test_prekryv_dvou_casti_se_nezapocita_dvakrat(self):
        first = "albert\nNádražní 1\n100 00 Vzorov\nROHLÍK43GR\n5 x 2,90 Kč 14,50 A\nGRAN MORAV.STR.100G 59,90 A"
        second = "5 x 2,90 Kč 14,50 A\nGRAN MORAV.STR.100G 59,90 A\nMLÉKO 1L 24,90 A\nCelkem 99,30\n07/09/26 10:15"
        document = parse.build_document([first, second])
        self.assertTrue(document["totalMatches"], document["warnings"])
        self.assertEqual(len(document["lines"]), 3)


    def test_prekryv_precteny_ocr_trochu_jinak_se_take_pozna(self):
        first = "BILLA\n251 01 ŘÍCANY U PRAHY\nČF ŽAMPIONY 300G 47,90 B\nORBIT REFRESHER 69,90 B"
        second = "ČF ZAMPIONY 300G 47,90 B\nORBIT REFRESHER 69,90 B\nPIZZA SE SUNKOU 19,90 B\nCelkem 137,70\n30.08.2026 11:10"
        document = parse.build_document([first, second])
        self.assertTrue(document["totalMatches"], document["warnings"])

    def test_ruzna_cena_neni_tentyz_radek(self):
        merged = parse.merge_segments(["A\nROHLIK 3,90 A", "ROHLIK 4,90 A\nB"])
        self.assertEqual(merged, ["A", "ROHLIK 3,90 A", "ROHLIK 4,90 A", "B"])


class StatusTest(unittest.TestCase):
    def test_obrazek_bez_castek_neni_uctenka(self):
        document = parse.build_document("Kočka na gauči\nnějaký text")
        self.assertEqual(result_mod.status_of(document), "NOT_A_RECEIPT")

    def test_castky_bez_polozek_jsou_necitelne(self):
        document = parse.build_document("Celkem 120,00")
        self.assertEqual(result_mod.status_of(document), "UNREADABLE")


class FakeApi:
    def __init__(self, jobs):
        self.jobs = jobs
        self.results = {}
        self.failed = {}

    def lease(self, limit):
        jobs, self.jobs = self.jobs, []
        return jobs

    def image(self, path):
        if "broken" in path:
            raise OSError("nelze stáhnout")
        return path.encode()

    def result(self, receipt_id, payload):
        self.results[receipt_id] = payload

    def fail(self, receipt_id, error, lease_until=None):
        self.failed[receipt_id] = (error, lease_until)


class FakeOcr:
    model = "fake"

    def ocr(self, image):
        return _text("albert-plny")


class WorkerLoopTest(unittest.TestCase):
    def test_vadna_uctenka_nezastavi_davku(self):
        api = FakeApi([{"receiptId": 1, "imagePaths": ["/x/broken"], "leaseUntil": "2026-10-24T10:15:00.123+02:00"},
                       {"receiptId": 2, "imagePaths": ["/x/ok"]}])
        worker.run_batch(api, FakeOcr(), 5, log=lambda *_: None)
        # Chyba nese pronájem, ke kterému patří — server podle něj ignoruje opožděná hlášení.
        self.assertEqual(api.failed[1][1], "2026-10-24T10:15:00.123+02:00")
        self.assertEqual(api.results[2]["status"], "PROCESSED")
        self.assertTrue(all(isinstance(v, (str, type(None))) for line in api.results[2]["lines"]
                            for v in (line["unitPrice"], line["quantity"])))


def _parts(prefix: str) -> list[str]:
    return [path.read_text(encoding="utf-8") for path in sorted(TESTDATA.glob(f"{prefix}-dil*.ocr.txt"))]


class TamdaProfileTest(unittest.TestCase):
    """Číslované položky s EAN na druhém řádku, účtenka focená po třech dílech s překryvem."""

    def setUp(self):
        self.document = parse.build_document(_parts("tamda"))
        self.by_no = {line["lineNo"]: line for line in self.document["lines"]}

    def test_prekryv_dilu_se_nezapocita_dvakrat(self):
        self.assertEqual(self.document["profile"], "tamda")
        self.assertEqual(len(self.document["lines"]), 10)
        self.assertTrue(self.document["totalMatches"], self.document["warnings"])
        self.assertEqual(self.document["warnings"], [])

    def test_hlavicka_ico_i_pres_spatne_prectene_JCO_a_datum_z_hlavicky(self):
        merchant = self.document["merchant"]
        self.assertEqual((merchant["ico"], merchant["street"], merchant["postalCode"], merchant["city"]),
                         ("27160840", "Nádražní 1", "100 00", "Vzorov"))
        # OCR přečetlo „IMDA FOODS" — podle IČO profil ví, čí účtenka to je.
        self.assertEqual(merchant["rawName"], "TAMDA FOODS s.r.o.")
        self.assertEqual(self.document["purchasedAt"], "2026-09-07T10:15:00+02:00")

    def test_ze_dvou_cteni_vyhraje_ean_s_platnou_kontrolni_cislici(self):
        self.assertEqual(self.by_no[4]["code"], "8594001740659")   # díl 2: ...658
        self.assertEqual(self.by_no[6]["code"], "5906927034812")   # díl 2: ...612

    def test_cteni_kde_mnozstvi_krat_cena_nesedi_prohraje(self):
        finish = self.by_no[7]                                     # díl 3: 679,00 a ...796297
        self.assertEqual((finish["lineTotal"], finish["code"]), ("679.80", "5997321736297"))
        self.assertEqual((finish["quantity"], finish["unitPrice"]), ("2", "339.90"))

    def test_osirely_radek_s_ean_na_zacatku_dilu_se_neprilepi_k_cizi_polozce(self):
        avokado = self.by_no[8]
        self.assertEqual((avokado["code"], avokado["quantity"], avokado["unitPrice"]),
                         ("8595012035352", "4", "9.50"))

    def test_cislice_pred_cenou_neni_oddelovac_tisicu_ale_tisice_se_poznaji(self):
        self.assertEqual(self.by_no[7]["rawLabel"], "Finish Allin1 Max tablety do myčky nádobi 8")
        trouba = self.by_no[9]
        self.assertEqual((trouba["rawLabel"], trouba["lineTotal"], trouba["unitPrice"]),
                         ("Mikrovlnná trouba 20L", "2599.80", "1299.90"))

    def test_cena_na_radku_s_ean(self):
        self.assertEqual(self.by_no[1]["lineTotal"], "950.00")

    def test_ean_jde_na_server_jako_internal_code(self):
        payload = result_mod.to_payload(self.document, None, "test")
        self.assertEqual(payload["lines"][0]["internalCode"], "8851212221408")
        self.assertTrue(all(line["internalCode"] for line in payload["lines"]))

    def test_syrovy_text_konci_celkovou_castkou(self):
        text = parse.server_text(_parts("tamda"))
        self.assertTrue(text.endswith("Celkem: 5 983,60"))
        self.assertNotIn("Přijatá", text)

    def test_chybejici_poradove_cislo_se_hlasi(self):
        document = parse.build_document("""TAMDA FOODS s.r.o.
1 12 Garak Udon @3x213g Kimchi 79,90
8800242940117
3 12 Varmuža pomazánka 135g tuňáková 27,90
8594002060138
Celkem: 107,80
""")
        self.assertEqual(len(document["lines"]), 2)
        self.assertTrue(any("chybí položka č. 2" in warning for warning in document["warnings"]))

    def test_neplatny_ean_se_neposle(self):
        document = parse.build_document("""TAMDA FOODS s.r.o.
1 12 Garak Udon @3x213g Kimchi 79,90
8800242940118
Celkem: 79,90
""")
        line = document["lines"][0]
        self.assertNotIn("code", line)
        self.assertIn("kontrolní číslicí", line["note"])


class ReplayTest(unittest.TestCase):
    """Offline přehrání (replay.py) — stejný rozbor jako worker, jen nad lokálními soubory."""

    def _run(self, paths, **kwargs):
        out = []
        texts = replay.read_parts(paths, self._no_ocr, kwargs.pop("save_ocr", False),
                                  log=lambda *a, **k: out.append(" ".join(map(str, a))))
        document = replay.report(texts, log=lambda *a, **k: out.append(" ".join(map(str, a))), **kwargs)
        return document, "\n".join(out)

    @staticmethod
    def _no_ocr():
        raise AssertionError("textový vstup nesmí volat OCR")

    def test_textovy_vstup_bez_ollamy(self):
        document, output = self._run([TESTDATA / "albert-plny.ocr.txt"])
        self.assertEqual(document, parse.build_document(_text("albert-plny")))
        self.assertIn("profil albert", output)
        self.assertIn("OK součet 387.45 / tištěno 387.45", output)
        self.assertIn("Celkem 387,45  <- celková částka", output)

    def test_varovani_jako_ve_workeru(self):
        _, output = self._run([TESTDATA / "albert-nesedi.ocr.txt"])
        self.assertIn("! nerozpoznaný tvar řádku: 'ROZMAZANÝ ŘÁDEK BEZ CENY'", output)

    def test_obrazek_jde_pres_ocr_a_ulozi_text(self):
        import tempfile

        class FakeOcr:
            model = "fake"

            def ocr(self, image):
                return _text("albert-plny")

        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "00.jpg"
            image.write_bytes(b"jpg")
            texts = replay.read_parts([image], FakeOcr, True, log=lambda *a, **k: None)
            self.assertEqual((Path(tmp) / "00.ocr.txt").read_text(encoding="utf-8"), texts[0])


class NormalizeTest(unittest.TestCase):
    def test_kontrolni_cislice_gtin(self):
        for code in ("8594001740659", "90169588", "011210617001", "08594001740659"):
            self.assertTrue(normalize.is_valid_gtin(code), code)
        for code in ("5997321796297", "400990052165", "859400174065", "85940017406599", "8594O01740659"):
            self.assertFalse(normalize.is_valid_gtin(code), code)

    def test_castka_s_mezerou_v_tisicich(self):
        self.assertEqual(str(normalize.parse_amount("1 369,20")), "1369.20")

    def test_castka_nad_tisic_bez_oddelovace(self):
        self.assertEqual(normalize.AMOUNT_RE.findall("CELKEM 1526,70"), ["1526,70"])
        line = lines_mod.parse_item_lines(["TELEVIZE 12990,00 A"]).lines[0]
        self.assertEqual((line.kind, line.line_total), (lines_mod.ITEM, Decimal("12990.00")))

    def test_pismeno_O_misto_nuly_jen_v_cisle(self):
        self.assertEqual(str(normalize.parse_amount("98O,2O")), "980.20")


if __name__ == "__main__":
    unittest.main()
