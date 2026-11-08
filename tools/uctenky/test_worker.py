#!/usr/bin/env python3
"""Testy parseru a workeru účtenek.

    python3 -m unittest discover -s tools/uctenky -p 'test_*.py'

Fixtures v testdata/ jsou ANONYMIZOVANÉ — reálné účtenky do repozitáře nepatří
(adresa provozovny + přesný čas nákupu identifikují zákazníka, docs/soukromi.md).
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import urllib.error
import unittest
from datetime import date
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ai_classify  # noqa: E402
import ai_pretty  # noqa: E402
import curate  # noqa: E402
import lines as lines_mod  # noqa: E402
import ocr as ocr_mod  # noqa: E402
import normalize  # noqa: E402
import parse  # noqa: E402
import pretty  # noqa: E402
import replay  # noqa: E402
import result as result_mod  # noqa: E402
import worker  # noqa: E402
from profiles import coop as coop_profile  # noqa: E402
from profiles import generic  # noqa: E402

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


class HeaderIdTest(unittest.TestCase):
    """IČO, DIČ a název obchodu z hlavičky, i když je useknutá nebo ji OCR zkomolí."""

    # Horní okraj useknutý — název chybí a OCR přečetlo „IČO" jako „ICU" (2026-11-08).
    CUT = """ICU: 64945880
DIČ: CZ64945880
MŠT. SOUD PRAHA C/42812
TUK.ROHLIK 43G
10 x 3,50 Kč 35,00 B
Celkem k úhradě 35,00 Kč
30.09.2026 07:42:11
"""

    def test_useknuta_hlavicka_ico_ze_zkomoleneho_popisku_a_bez_nazvu(self):
        document = parse.build_document(self.CUT)
        merchant = document["merchant"]
        self.assertEqual(merchant["ico"], "64945880")
        self.assertEqual(merchant["country"], "CZ")
        self.assertNotIn("rawName", merchant)
        self.assertEqual([line["rawLabel"] for line in document["lines"]], ["TUK.ROHLIK 43G"])
        self.assertEqual(document["warnings"], [])

    def test_ulice_nad_psc_neni_nazev(self):
        document = parse.build_document("Hlavní 12\n123 45 Vzorov\n" + self.CUT)
        merchant = document["merchant"]
        self.assertNotIn("rawName", merchant)
        self.assertEqual((merchant["street"], merchant["city"]), ("Hlavní 12", "Vzorov"))

    def test_zkomoleny_popisek_bez_platne_kontrolni_cislice_ico_neni(self):
        self.assertIsNone(generic.find_ico(["ICU: 64945881"]))
        self.assertEqual(generic.find_ico(["JČO: 27160840"]), "27160840")
        self.assertEqual(generic.find_ico(["IC0: 00031747"]), "00031747")

    def test_dic_fyzicke_osoby_neni_ico_a_neodchazi(self):
        document = parse.build_document(self.CUT.replace("ICU: 64945880\nDIČ: CZ64945880",
                                                         "Řeznictví Novák\nDIČ: CZ7801011234"))
        self.assertNotIn("ico", document["merchant"])
        self.assertEqual(document["merchant"]["country"], "CZ")
        self.assertNotIn("7801011234", json.dumps(document))

    def test_stat_podle_prefixu_dic_v_ruznych_jazycich(self):
        cases = {"USt-IdNr.: DE123456789": "DE", "UID: ATU12345678": "AT", "IČ DPH: SK2020123456": "SK",
                 "VAT No. GB123456789": "GB", "VAT Number: EL123456789": "GR", "DIC: CZ64945880": "CZ",
                 "NIP: 5260250929": None, "Tel: 123456789": None}
        for line, country in cases.items():
            with self.subTest(line=line):
                self.assertEqual(generic.find_vat_country([line]), country)

    def test_dic_ciziho_statu_v_ceskem_profilu_je_varovani(self):
        document = parse.build_document(self.CUT.replace("ICU: 64945880\nDIČ: CZ64945880",
                                                         "Potraviny Žilina\nIČ DPH: SK2020123456"))
        self.assertEqual(document["merchant"]["country"], "SK")
        self.assertTrue(any("ze státu SK" in warning for warning in document["warnings"]), document["warnings"])

    def test_kontrolni_cislice_ica(self):
        for code in ("64945880", "27160840", "00031747"):
            self.assertTrue(normalize.is_valid_ico(code), code)
        self.assertFalse(normalize.is_valid_ico("12345678"))
        self.assertFalse(normalize.is_valid_ico("6494588"))


class InfoLineTest(unittest.TestCase):
    """Tištěné poznámky bez údaje o zboží nejsou položky ani varování."""

    BILLA = """OBCHOD VZOR
Hlavní 12
123 45 Vzorov
IČ: 12345678
SIRUP CERNY BEZ B 94,90
SLEVA -35,00
(SIRUP CERNY BEZ 59,90)
SIRUP CERNY BEZ B 94,90
SLEVA -35,00
(SIRUP CERNY BEZ 59,90)
KOLONÁDA B 104,90
Celkem Kč 224,70
30.09.2026 07:42:11
"""

    ALBERT = """albert
Hlavní 12
123 45 Vzorov
WHISKY 0,7L
2 x 499,00 Kč 998,00 B
Věkový limit
Celkem 998,00
30/09/26 07:42 Účtenka číslo 00001
"""

    def _kinds(self, document: dict) -> list[str]:
        return [line["kind"] for line in document["lines"]]

    def test_cena_po_sleve_v_zavorce_neni_polozka(self):
        document = parse.build_document(self.BILLA)
        self.assertTrue(document["totalMatches"], document["warnings"])
        self.assertEqual(document["warnings"], [])
        self.assertNotIn(lines_mod.UNKNOWN, self._kinds(document))
        payload = result_mod.to_payload(document, self.BILLA, "glm-ocr")
        self.assertEqual([line["discountAmount"] for line in payload["lines"]], ["35.00", "35.00", None])

    def test_nesedici_cena_po_sleve_se_hlasi(self):
        document = parse.build_document(self.BILLA.replace("(SIRUP CERNY BEZ 59,90)", "(SIRUP CERNY BEZ 50,00)", 1))
        self.assertEqual(document["warnings"], ["cena po slevě '(SIRUP CERNY BEZ 50,00)' nesedí s položkou nad ní"])

    def test_vekovy_limit_neni_polozka(self):
        document = parse.build_document(self.ALBERT)
        self.assertEqual(document["profile"], "albert")
        self.assertTrue(document["totalMatches"], document["warnings"])
        self.assertEqual(document["warnings"], [])
        self.assertEqual(self._kinds(document), [lines_mod.ITEM])

    def test_vekovy_limit_neschova_nazev_bez_ceny(self):
        document = parse.build_document(self.ALBERT.replace("2 x 499,00 Kč 998,00 B\n", ""))
        self.assertEqual(self._kinds(document), [lines_mod.UNKNOWN])
        self.assertEqual(document["warnings"][0], "nerozpoznaný tvar řádku: 'WHISKY 0,7L'")


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

    # Vážené zboží se značkou zadání hmotnosti, datum a čas na dvou řádcích, mezisoučet
    # a zaokrouhlení nad celkovou částkou (hotovost) — anonymizovaný tvar reálné účtenky.
    WEIGHED = """PENNY.
PENNY MARKET S.R.O.
Nádražní 1, 10000 Vzorov
000 VZOROV
IČO: 12345678
DIČ: CZ12345678
MĚST. SOUD PRAHA C/00000
Datum: 07.09.2026
Cas: 10:15:00
Kč
0.526 kg (E) x 109.90 Kč /kg
Kuř. prs.řízkyDM C 57.81
0.575 kg (V) x 109.90 Kč /kg
Kuř. prs.řízkyDM C 63.19
6.000 ks x 2.90 Kč /ks
Rohlík 43g C 17.40
Mezisoučet Kč 138.40
Zaokrouhlení Kč -0.40
Celkem Kč 138.00
PRIJATO v hotovosti 200.00
VRACENO Kč 62.00
Vážená položka byla zadána:
(V) = z pokladní váhy
"""

    def test_vazene_zbozi_se_znackou_zadani_a_zaokrouhleni(self):
        document = parse.build_document(self.WEIGHED)
        self.assertEqual(document["warnings"], [])
        # Zaokrouhlení (-0,40) je víc než tolerance součtu — musí se připočíst, ne tolerovat.
        self.assertTrue(document["totalMatches"])
        self.assertEqual((document["computedTotal"], document["printedTotal"]), ("138.00", "138.00"))
        items = document["lines"]
        self.assertEqual([line["kind"] for line in items], [lines_mod.ITEM] * 3)
        self.assertEqual([(line["quantity"], line["quantityBasis"], line["unitPrice"]) for line in items],
                         [("0.526", "PER_KG", "109.90"), ("0.575", "PER_KG", "109.90"),
                          ("6", "PACKAGE", "2.90")])
        self.assertEqual(document["purchasedAt"], "2026-09-07T10:15:00+02:00")


    def test_dvojtecka_misto_desetinne_tecky_v_cene_za_jednotku(self):
        # Účtenka 24 (2026-11-08): OCR přečetlo „49.90" jako „49:90" a řádek se ztratil.
        document = parse.build_document(self.TEXT.replace("3.000 ks x 36.90", "3.000 ks x 36:90"))
        self.assertEqual(document["warnings"], [])
        balsyr = next(line for line in document["lines"] if line["kind"] == lines_mod.ITEM)
        self.assertEqual((balsyr["quantity"], balsyr["unitPrice"], balsyr["lineTotal"]), ("3", "36.90", "110.70"))
        self.assertEqual([line["kind"] for line in document["lines"]], [lines_mod.ITEM] * 2)

    def test_mnozstvi_krat_cena_nesedi_s_castkou(self):
        document = parse.build_document(self.TEXT.replace("3.000 ks x 36.90", "3.000 ks x 38.90"))
        self.assertTrue(any("nesedí s částkou u 'Balsýr bloček 47% 200g'" in warning
                            for warning in document["warnings"]), document["warnings"])

    def test_cas_s_dvojteckou_neni_castka(self):
        self.assertIsNone(normalize.AMOUNT_RE.search("Čas: 13:07:15"))
        self.assertEqual(normalize.parse_amount("49:90"), Decimal("49.90"))


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

    def test_horni_okraj_dilu_useknul_castku(self):
        # Druhý díl začíná řádkem, kterému okraj snímku usekl částku slevy (Billa).
        first = "BILLA\n251 01 Vzorov\nSIRUP B 94,90\nSLEVA -35,00\nKOLONÁDA B 104,90\nLAZ. OP. B 104,90"
        second = "SLEVA\nKOLONADA B 104,90\nLAZ. OP. B 104,90\nPIZZA B 19,90\nCelkem 289,60\n30.08.2026 11:10"
        document = parse.build_document([first, second])
        self.assertTrue(document["totalMatches"], document["warnings"])
        self.assertEqual(document["warnings"], [])
        self.assertEqual([line["rawLabel"] for line in document["lines"]],
                         ["SIRUP", "SLEVA", "KOLONÁDA", "LAZ. OP.", "PIZZA"])

    def test_spodni_okraj_dilu_useknul_castku(self):
        merged = parse.merge_segments(["A\nROHLIK 3,90 A\nMLEKO 24,90 A\nSLEVA",
                                       "ROHLIK 3,90 A\nMLEKO 24,90 A\nSLEVA -5,00\nB"])
        self.assertEqual(merged, ["A", "ROHLIK 3,90 A", "MLEKO 24,90 A", "SLEVA -5,00", "B"])

    def test_useknuty_radek_s_jinou_castkou_neni_prekryv(self):
        merged = parse.merge_segments(["A\nSLEVA -35,00\nROHLIK 3,90 A\nMLEKO 24,90 A",
                                       "SLEVA -20,00\nROHLIK 3,90 A\nMLEKO 24,90 A\nB"])
        self.assertEqual(len(merged), 8)

    def test_useknuty_radek_s_jedinou_shodou_neni_prekryv(self):
        # Jeden shodný řádek je na spárování useknutého okraje málo — mohla by to být náhoda.
        merged = parse.merge_segments(["A\nSLEVA -35,00\nROHLIK 3,90 A", "SLEVA\nROHLIK 3,90 A\nB"])
        self.assertEqual(merged, ["A", "SLEVA -35,00", "ROHLIK 3,90 A", "SLEVA", "ROHLIK 3,90 A", "B"])


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


class ReprocessTest(unittest.TestCase):
    """Nové rozpoznání (README.md, „Nové rozpoznání")."""

    def test_bez_obrazku_se_znovu_rozebere_ulozeny_text_bez_ocr(self):
        class NoOcr:
            model = "nesmí se volat"

            def ocr(self, image):
                raise AssertionError("OCR v textovém režimu")

        raw_text = parse.server_text(_text("albert-plny"))
        api = FakeApi([{"receiptId": 7, "imagePaths": [], "rawText": raw_text}])
        worker.run_batch(api, NoOcr(), 5, log=lambda *_: None)
        self.assertEqual(api.results[7]["status"], "PROCESSED")
        self.assertIsNone(api.results[7]["model"])
        self.assertTrue(api.results[7]["lines"])

    def test_requeue_vypise_vracene_i_preskocene(self):
        class RequeueApi:
            def requeue(self, receipt_ids, processed_since=None):
                self.args = (receipt_ids, processed_since)
                return {"requeued": [1], "skipped": [{"receiptId": 2, "reason": "already queued"}]}

        api, logged = RequeueApi(), []
        self.assertEqual(worker.requeue(api, [1, 2], date(2026, 10, 1), log=logged.append), 1)
        self.assertEqual(api.args[0], [1, 2])
        self.assertTrue(api.args[1].startswith("2026-10-01T00:00:00"))
        self.assertIn("already queued", logged[-1])


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


COOP_RECEIPT = """COOP Vzorov družstvo,
Náměstí 1
100 00 Vzorov
IČO: 12345678 DIČ: CZ12345678
PJ:007 Prodejna 7 Lhota TIP
Daňový doklad č. F007P1P00000001 (kopie)
07.09.2026 10:15:20
pokladna 1
%D Zboží Množství Kč/MJ Cena Kč
12 Dušená šunka výběrová 57,08
váhový kód: 2801234003020
0,302 kg x 189,00 Kč/kg
12 MONTE KELÍMEK 150g 19,90
21 Red bull summer 250ml 36,90
12 **Rohlík Hořovický 43g 19,20
6 ks x 3,20 Kč/ks
12 MILENA TYČINKA 32G 19,80
2 KS x 9,90 Kč/KS
Celkem: 152,88
Zaokrouhlení: 0,00
Platební karta 152,88
07/09/26 10:15 Účtenka číslo 00001
COOP Vzorov,družstvo
Coop Lhota 007
Lhotská 12
111 11 Lhota u Vzorova
IČO: 12345678
Terminál: COOP0001 (M1COOP0001-00000000)
**** **** **** 1234 / 00 (L) VISA
"""


class CoopProfileTest(unittest.TestCase):
    """Sazba DPH před názvem, množství a váhový kód POD názvem, adresa prodejny v bloku terminálu."""

    def setUp(self):
        self.document = parse.build_document(COOP_RECEIPT)
        self.lines = self.document["lines"]

    def test_hlavicka_nepropadne_do_polozek(self):
        self.assertEqual(self.document["profile"], "coop")
        self.assertEqual(self.document["warnings"], [])
        self.assertTrue(self.document["totalMatches"])
        self.assertEqual([line["kind"] for line in self.lines], ["ITEM"] * 5)
        self.assertEqual(self.document["purchasedAt"], "2026-09-07T10:15:00+02:00")

    def test_mnozstvi_patri_k_polozce_nad_nim(self):
        sunka, kelimek, _, rohlik, milena = self.lines
        self.assertEqual((sunka["quantity"], sunka["quantityBasis"], sunka["unitPrice"]), ("0.302", "PER_KG", "189.00"))
        self.assertEqual((kelimek["quantity"], kelimek["quantityBasis"], kelimek["unitPrice"]), ("1", "PACKAGE", "19.90"))
        self.assertEqual((rohlik["quantity"], rohlik["unitPrice"]), ("6", "3.20"))
        self.assertEqual((milena["quantity"], milena["unitPrice"]), ("2", "9.90"))

    def test_sazba_dph_neni_v_nazvu(self):
        self.assertEqual(self.lines[0]["rawLabel"], "Dušená šunka výběrová")
        self.assertEqual(self.lines[2]["vatCode"], "21")

    def test_vahovy_kod_se_neposila(self):
        self.assertNotIn("code", self.lines[0])
        self.assertNotIn("note", self.lines[0])
        payload = result_mod.to_payload(self.document, None, "test")
        self.assertIsNone(payload["lines"][0]["internalCode"])

    def test_vahovy_kod_s_jinou_hmotnosti_se_poznamena(self):
        document = parse.build_document(COOP_RECEIPT.replace("2801234003020", "2801234004020"))
        self.assertIn("0.402 kg nesedí s 0.302 kg", document["lines"][0]["note"])

    def test_mnozstvi_ktere_nesedi_s_cenou_se_neprilepi(self):
        document = parse.build_document(COOP_RECEIPT.replace("6 ks x 3,20", "6 ks x 3,30"))
        rohlik = document["lines"][3]
        self.assertEqual((rohlik["quantity"], rohlik["quantityBasis"], rohlik["unitPrice"]), ("1", "PACKAGE", "19.20"))
        self.assertTrue(any("nesedí s cenou řádku" in warning for warning in document["warnings"]))

    def test_adresa_prodejny_z_bloku_terminalu(self):
        merchant = self.document["merchant"]
        self.assertEqual(merchant["rawName"], "COOP Vzorov družstvo – Prodejna 7 Lhota")
        self.assertEqual(merchant["chainSlug"], "coop-tip")
        self.assertEqual((merchant["street"], merchant["postalCode"], merchant["city"], merchant["ico"]),
                         ("Lhotská 12", "111 11", "Lhota u Vzorova", "12345678"))

    def test_bez_bloku_terminalu_se_adresa_sidla_neposle(self):
        cash = COOP_RECEIPT.split("Platební karta")[0] + "Hotově 160,00\n"
        merchant = parse.build_document(cash)["merchant"]
        self.assertEqual(merchant["ico"], "12345678")
        for key in ("street", "postalCode", "city"):
            self.assertNotIn(key, merchant)

    def test_coop_s_nulami_z_ocr_se_pozna_a_nazev_se_opravi(self):
        document = parse.build_document(COOP_RECEIPT.replace("COOP Vzorov družstvo,", "C00P Vzorov družstvo,")
                                        .replace("COOP Vzorov,družstvo\nCoop Lhota", "C00P Vzorov,družstvo\nC00p Lhota"))
        self.assertEqual(document["profile"], "coop")
        self.assertEqual(document["merchant"]["rawName"], "COOP Vzorov družstvo – Prodejna 7 Lhota")
        self.assertEqual(document["merchant"]["street"], "Lhotská 12")
        self.assertEqual(len(document["lines"]), 5)

    def test_slovo_s_nulami_mimo_coop_se_nemeni(self):
        self.assertEqual(coop_profile._fix_coop("C00P Lhota, kód C0P 100"), "COOP Lhota, kód C0P 100")
        self.assertEqual(coop_profile._fix_coop("C00p Lhota"), "Coop Lhota")

    def test_syrovy_text_konci_celkovou_castkou(self):
        text = parse.server_text(COOP_RECEIPT)
        self.assertTrue(text.endswith("Celkem: 152,88"))
        self.assertNotIn("Lhotská", text)


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

    def test_hlavicka_sloupce_v_oddelovaci_je_sum(self):
        # Albert tiskne „-------Kč---", OCR z toho přečte „Kc---".
        for line in ("Kc---", "-------Kč---", "--- Kč ---", "Kč", "-----"):
            self.assertTrue(normalize.is_noise(line), line)
        for line in ("Kč 19,90", "UNI TWIST ZEL.80ML 19,90 A", "Kc--- 5,90"):
            self.assertFalse(normalize.is_noise(line), line)

    def test_pismeno_O_misto_nuly_jen_v_cisle(self):
        self.assertEqual(str(normalize.parse_amount("98O,2O")), "980.20")


class OllamaOcrTest(unittest.TestCase):
    """Požadavek na Ollamu — bez běžící Ollamy, urlopen je podvržený."""

    def _call(self, respond):
        sent = {}

        def fake_urlopen(request, timeout):
            sent.update(json.loads(request.data.decode("utf-8")))
            return respond(request)

        original = ocr_mod.urllib.request.urlopen
        ocr_mod.urllib.request.urlopen = fake_urlopen
        try:
            return ocr_mod.OllamaOcr().ocr(b"\xff\xd8\xff"), sent
        finally:
            ocr_mod.urllib.request.urlopen = original

    def test_kontext_unese_obrazek_cele_uctenky(self):
        class Response(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

        text, sent = self._call(lambda _: Response(json.dumps({"response": "Celkem 10,00"}).encode()))
        self.assertEqual(text, "Celkem 10,00")
        # Výchozí kontext Ollamy (4096) se s obrázkem celé účtenky (~4050 tokenů) nevejde.
        self.assertGreaterEqual(sent["options"]["num_ctx"], 16384)
        self.assertEqual((sent["options"]["temperature"], sent["options"]["seed"]), (0, 0))

    def test_chyba_ollamy_nese_jeji_duvod(self):
        body = b'{"error":"request (4118 tokens) exceeds the available context size (4096 tokens)"}'

        def refuse(request):
            raise urllib.error.HTTPError(request.full_url, 400, "Bad Request", {}, io.BytesIO(body))

        with self.assertRaises(ocr_mod.OcrError) as raised:
            self._call(refuse)
        self.assertIn("400", str(raised.exception))
        self.assertIn("exceeds the available context size", str(raised.exception))


def _dictionary() -> pretty.Dictionary:
    """Pevný slovník pro testy — skutečný slovnik/*.tsv se mění s provozem."""
    dictionary = pretty.Dictionary()
    dictionary.add_abbreviation("SELS.", "Selské")
    dictionary.add_abbreviation("MLÉ.", "mléko")
    dictionary.add_abbreviation("ARAŠÍDOVÁ", "arašídová")
    dictionary.add_abbreviation("MORAV.", "Moravia", "albert")
    dictionary.add_abbreviation("BIO", "BIO")
    dictionary.add_abbreviation("ROHLÍK", "rohlík")
    return dictionary


class PrettyLabelTest(unittest.TestCase):
    """Čitelný název řádku (pretty.py) — příklady ze zadání a pasti tokenizace."""

    def _pretty(self, raw, chain="albert"):
        return pretty.pretty_label(raw, chain, _dictionary())

    def test_priklady_ze_zadani(self):
        self.assertEqual(self._pretty("ROHLÍK43GR").text, "Rohlík 43g")
        self.assertEqual(self._pretty("NUGETA ARASÍDOVÁ340G").text, "Nugeta arašídová 340g")
        self.assertEqual(self._pretty("OLMA SELS.MLÉ.3,9%1L").text, "Olma Selské mléko 3,9% 1l")

    def test_vodítko_gramaze_jen_z_hmotnosti_a_objemu(self):
        self.assertEqual(self._pretty("ROHLÍK43GR").net_content, ("43", "G"))
        self.assertEqual(self._pretty("RED BULL 0,25L").net_content, ("0.25", "L"))
        self.assertIsNone(self._pretty("WM TORTILLA 25CM").net_content)
        # Násobné balení: cena je za celé balení, vodítkem je celková gramáž (8 x 50 ml).
        self.assertEqual(self._pretty("UNI TWISTER 8X50ML").net_content, ("400", "ML"))
        self.assertEqual(self._pretty("VODA 4X1,5L").net_content, ("6", "L"))
        self.assertIsNone(self._pretty("JOGURT 6X").net_content)
        self.assertIsNone(self._pretty("PAPIR 4X10KS").net_content)
        self.assertEqual(self._pretty("UNI TWISTER 8X50ML").text, "Uni twister 8x50ml")

    def test_zkratka_retezce_plati_jen_pro_nej(self):
        self.assertEqual(self._pretty("GRAN MORAV.100G", "albert").text, "Gran Moravia 100g")
        self.assertEqual(self._pretty("GRAN MORAV.100G", "obecny").text, "Gran morav. 100g")

    def test_nevyresena_slova_jdou_modelu(self):
        result = self._pretty("CHOC.SM.BÍLY330G")
        self.assertEqual(result.source, pretty.SOURCE_RULES)
        self.assertEqual(result.unresolved, ("CHOC.", "SM.", "BÍLY"))

    def test_citelny_popisek_obchodu_se_nemeni(self):
        raw = "Lenor aviváž 1239ml 59PD Sensitive"
        result = self._pretty(raw, "tamda")
        self.assertEqual(result.text, raw)
        self.assertEqual(result.unresolved, ())
        self.assertEqual(result.net_content, ("1239", "ML"))

    def test_cislo_slepene_s_pismeny_neni_jednotka(self):
        self.assertEqual(pretty.tokenize("PRACI GEL 4L 80PD"), ["PRACI", "GEL", "4L", "80", "PD"])

    def test_rucni_prepis_celeho_oznaceni_vyhraje(self):
        dictionary = _dictionary()
        dictionary.add_override("TAPAS SPIAN.ROM.70G", "Tapas Spianata Romana 70g", "albert")
        result = pretty.pretty_label("TAPAS SPIAN. ROM.70G", "albert", dictionary)
        self.assertEqual((result.text, result.source), ("Tapas Spianata Romana 70g", pretty.SOURCE_DICT))
        self.assertEqual(result.net_content, ("70", "G"))

    def test_norm_label_jako_v_databazi(self):
        # core.norm_label: interpunkce -> mezera, bez diakritiky, malými, sloučené mezery.
        self.assertEqual(pretty.norm_label("TUK.ROHLIK"), "tuk rohlik")
        self.assertEqual(pretty.norm_label(" GRAN MORAV.STR.100G "), "gran morav str 100g")
        self.assertEqual(pretty.norm_label("KUŘ.PRSNÍ  ŘÍZKY"), "kur prsni rizky")
        self.assertEqual(pretty.norm_label("ÖLZ_SANDWICH"), "olz sandwich")

    def test_slovnik_v_repozitari_jde_nacist(self):
        dictionary = pretty.load_dictionary()
        self.assertEqual(dictionary.abbreviation("sels.", None), "Selské")

    def test_zapis_do_slovniku_a_zpet(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            pretty.append_row(directory / pretty.ABBREVIATIONS_FILE, ["KVAS.", "kváskem", "albert"])
            pretty.append_row(directory / pretty.OVERRIDES_FILE, ["ROHLÍK43GR", "Rohlík tukový 43g", ""])
            dictionary = pretty.load_dictionary(directory)
        self.assertEqual(dictionary.abbreviation("KVAS.", "albert"), "kváskem")
        self.assertEqual(dictionary.override("rohlík43gr", "penny"), "Rohlík tukový 43g")


class AiPrettyTest(unittest.TestCase):
    """Kontrola výstupu modelu (ai_pretty.accept) — jen kosmetika projde."""

    def test_doplnena_diakritika_a_rozepsana_zkratka_projde(self):
        self.assertIsNone(ai_pretty.accept("Nugeta arasidova 340g", "Nugeta arašídová 340g"))
        self.assertIsNone(ai_pretty.accept("Olma sels. mlé. 3,9% 1l", "Olma Selské mléko 3,9% 1l"))
        self.assertIsNone(ai_pretty.accept("Choc. jogurt bíly 330g", "Choc. jogurt bílý 330g"))

    def test_zmena_cisla_neprojde(self):
        self.assertIn("číslo", ai_pretty.accept("Rohlík 43g", "Rohlík 45g"))
        self.assertIn("číslo", ai_pretty.accept("Mléko 3,9% 1l", "Mléko 3.9% 1l"))

    def test_prejmenovani_a_zmena_poctu_slov_neprojde(self):
        self.assertIn("slovo", ai_pretty.accept("Gran str. 100g", "Gran Padano 100g"))
        self.assertIn("počet", ai_pretty.accept("Rohlík 43g", "Rohlík tukový 43g"))
        self.assertIn("počet", ai_pretty.accept("Pilsner Urquell ležák 0,5l", "Pilsner ležák 0,5l"))

    def test_vytistenou_diakritiku_model_neodebere(self):
        self.assertIn("slovo", ai_pretty.accept("Ölz sandwich 750g", "Olz sandwich 750g"))

    def test_polish_vezme_jen_co_projde_a_vypadek_neni_chyba(self):
        items = [pretty.pretty_label(raw, "albert", _dictionary())
                 for raw in ("NUGETA LISKOVA 340G", "ROHLÍK43GR", "GRAN STR.100G")]

        class Model:
            def complete(self, names):
                self.sent = names
                return {"0": "Nugeta lísková 340g", "2": "Gran Padano 100g"}

        model = Model()
        log = []
        polished = ai_pretty.polish(items, model, log.append)
        # „Rohlík 43g" pravidla vyřešila celé — modelu se neposílá.
        self.assertEqual(set(model.sent), {"0", "2"})
        self.assertEqual((polished[0].text, polished[0].source), ("Nugeta lísková 340g", pretty.SOURCE_AI))
        self.assertEqual((polished[2].text, polished[2].source), ("Gran str. 100g", pretty.SOURCE_RULES))
        self.assertTrue(any("zamítnut" in line for line in log))

        class Down:
            def complete(self, names):
                raise OSError("connection refused")

        self.assertEqual(ai_pretty.polish(items, Down(), log.append), items)

    def test_payload_nese_citelny_nazev_vedle_syroveho(self):
        document = parse.build_document(_text("albert-plny"))
        payload = result_mod.to_payload(document, None, "test", _dictionary())
        rohlik = next(line for line in payload["lines"] if line["label"] == "ROHLÍK43GR")
        self.assertEqual(rohlik["labelDisplay"], "Rohlík 43g")
        self.assertEqual(rohlik["labelDisplaySource"], "RULES")
        self.assertEqual((rohlik["netContentValue"], rohlik["netContentUom"]), ("43", "G"))

    def test_bez_slovniku_payload_beze_zmeny(self):
        document = parse.build_document(_text("albert-plny"))
        payload = result_mod.to_payload(document, None, "test")
        self.assertNotIn("labelDisplay", payload["lines"][0])

    def test_replay_vypise_citelne_nazvy(self):
        out = []
        document = parse.build_document(_text("albert-plny"))
        replay.report_names(document, _dictionary(), log=out.append)
        self.assertTrue(any("ROHLÍK43GR" in line and "Rohlík 43g" in line for line in out))


class FakeCuratorApi:
    def __init__(self, conflict_with=None):
        self.searches = []
        self.mapped = []
        self.conflict_with = conflict_with

    def search_products(self, query, store_id, limit=8):
        self.searches.append((query, store_id))
        return [{"id": 11, "name": "Gran Moravia strouhaný", "netContentValue": 100, "netContentUom": "G",
                 "status": "ACTIVE", "score": 0.81},
                {"id": 12, "name": "Grana Padano", "status": "ACTIVE", "score": 0.4}]

    def map_label(self, label, chain_id, store_id, product_id, replace=False):
        self.mapped.append((label, chain_id, store_id, product_id, replace))
        if self.conflict_with and not replace:
            return {"outcome": "CONFLICT", "existingProductId": self.conflict_with}
        return {"outcome": "CREATED", "productId": product_id, "suggestedLines": 3}


class CurateTest(unittest.TestCase):
    """Kurátorství na lokální stanici (curate.py) — vstup z klávesnice nahrazený seznamem."""

    ITEM = {"label": "GRAN MORAV.STR.100G", "chainId": 1, "chainSlug": "albert", "chainName": "Albert",
            "storeId": None, "sampleStoreId": 5, "lineCount": 4, "lastSeenDay": "2026-10-30",
            "netContentValue": 100, "netContentUom": "G"}

    def _session(self, answers, api=None, directory=None):
        out = []
        answers = iter(answers)
        session = curate.Session(api or FakeCuratorApi(), _dictionary(), directory or Path("/nonexistent"),
                                 read=lambda prompt="": next(answers), write=out.append)
        return session, out

    def test_hleda_bez_cisel_a_paruje_vybrane_zbozi_v_rozsahu_retezce(self):
        api = FakeCuratorApi()
        session, out = self._session(["2"], api)
        session.run([dict(self.ITEM)])
        self.assertEqual(api.searches[0], ("Gran Moravia str.", 5))
        self.assertEqual(api.mapped, [("GRAN MORAV.STR.100G", 1, None, 12, False)])
        self.assertEqual(session.mapped, 1)

    def test_enter_vezme_prvniho_kandidata(self):
        api = FakeCuratorApi()
        session, _ = self._session([""], api)
        session.run([dict(self.ITEM)])
        self.assertEqual(api.mapped[0][3], 11)

    def test_konflikt_se_prepise_jen_po_potvrzeni(self):
        api = FakeCuratorApi(conflict_with=99)
        session, _ = self._session(["1", "n", "1", "a"], api)
        session.run([dict(self.ITEM)])
        self.assertEqual([call[4] for call in api.mapped], [False, False, True])

    def test_oprava_nazvu_a_zkratka_se_zapisou_do_slovniku(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            session, out = self._session(["zr STR.=strouhaný", "e Gran Moravia strouhaný 100g", "s"],
                                         directory=directory)
            session.run([dict(self.ITEM)])
            dictionary = pretty.load_dictionary(directory)
        self.assertEqual(dictionary.abbreviation("STR.", "albert"), "strouhaný")
        self.assertIsNone(dictionary.abbreviation("STR.", "penny"))
        self.assertEqual(dictionary.override("GRAN MORAV.STR.100G", "albert"), "Gran Moravia strouhaný 100g")
        self.assertIn("      čitelně: Gran Moravia strouhaný 100g", out)

    def test_q_ukonci_celou_relaci(self):
        api = FakeCuratorApi()
        session, _ = self._session(["q"], api)
        session.run([dict(self.ITEM), dict(self.ITEM)])
        self.assertEqual(len(api.searches), 1)


if __name__ == "__main__":
    unittest.main()


class FakeClassifier:
    def __init__(self, answers: dict | None = None, error: Exception | None = None):
        self.answers = answers or {}
        self.error = error
        self.calls = []

    def complete(self, categories, items):
        self.calls.append((categories, items))
        if self.error:
            raise self.error
        return self.answers


class ClassifyTest(unittest.TestCase):
    CATEGORIES = [{"slug": "mleko", "path": "Potraviny › Mléčné výrobky › Mléko"},
                  {"slug": "testoviny", "path": "Potraviny › Trvanlivé › Těstoviny"}]

    def test_platny_navrh_projde(self):
        lines = [{"label": "MLEKO POL.", "labelDisplay": "Mléko pol.", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": {"category": "mleko", "unit": "l"}}),
                             log=lambda *_: None)
        self.assertEqual((lines[0]["categoryHint"], lines[0]["unitHint"]), ("mleko", "L"))

    def test_model_dostane_citelny_nazev_a_cesty_kategorii(self):
        classifier = FakeClassifier({})
        lines = [{"label": "MLEKO POL.", "labelDisplay": "Mléko pol.", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.CATEGORIES, classifier, log=lambda *_: None)
        categories, items = classifier.calls[0]
        self.assertEqual(items, {"0": "Mléko pol."})
        self.assertEqual(categories["mleko"], "Potraviny › Mléčné výrobky › Mléko")

    def test_neznama_kategorie_a_jednotka_se_zahodi(self):
        lines = [{"label": "NECO", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": {"category": "vymysl", "unit": "KS"}}),
                             log=lambda *_: None)
        self.assertEqual((lines[0]["categoryHint"], lines[0]["unitHint"]), (None, None))

    def test_slug_podany_jako_cesta_se_prijme_podle_posledniho_segmentu(self):
        # Skutečná odpověď qwen2.5:14b — rodič „mlecko-vyrobky" neexistuje, slug „mleko" ano.
        lines = [{"label": "MLEKO", "quantityUnit": "PCS"}, {"label": "SPAGETY", "quantityUnit": "PCS"}]
        answers = {"0": {"category": "mlecko-vyrobky/mleko", "unit": None},
                   "1": {"category": "Potraviny › Trvanlivé › testoviny", "unit": None}}
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier(answers), log=lambda *_: None)
        self.assertEqual([l["categoryHint"] for l in lines], ["mleko", "testoviny"])

    def test_cesta_s_neznamym_poslednim_segmentem_se_zahodi(self):
        lines = [{"label": "JOGURT", "quantityUnit": "PCS"}]
        logged = []
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": {"category": "mleko/jogurty"}}),
                             log=logged.append)
        self.assertIsNone(lines[0]["categoryHint"])
        self.assertTrue(any("neznámá kategorie „mleko/jogurty“" in message for message in logged))

    def test_jednotka_proti_vytistene_gramazi_se_zahodi(self):
        lines = [{"label": "TESTOV. 500G", "quantityUnit": "PCS", "netContentUom": "G"}]
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": {"category": "testoviny", "unit": "L"}}),
                             log=lambda *_: None)
        self.assertEqual((lines[0]["categoryHint"], lines[0]["unitHint"]), ("testoviny", None))

    def test_vahove_zbozi_jednotku_nedostane(self):
        lines = [{"label": "BANANY", "quantityUnit": "KG"}]
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": {"category": None, "unit": "G"}}),
                             log=lambda *_: None)
        self.assertIsNone(lines[0]["unitHint"])

    def test_vypadek_modelu_neni_chyba_uctenky(self):
        lines = [{"label": "MLEKO", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier(error=urllib.error.URLError("down")),
                             log=lambda *_: None)
        self.assertNotIn("categoryHint", lines[0])

    def test_payload_nese_navrh_az_po_citelnych_nazvech(self):
        document = parse.build_document(_text("albert-plny"))
        classifier = FakeClassifier({})
        payload = result_mod.to_payload(document, None, "m", pretty.Dictionary(), None, lambda *_: None,
                                        classifier, self.CATEGORIES)
        _, items = classifier.calls[0]
        self.assertEqual(len(items), len(payload["lines"]))
        self.assertTrue(all("categoryHint" in line for line in payload["lines"]))

    def test_jednotka_ani_kategorie_jineho_typu_nesrazi_uctenku(self):
        lines = [{"label": "MLEKO", "quantityUnit": "PCS"}, {"label": "SIRUP", "quantityUnit": "PCS"}]
        answers = {"0": {"category": ["mleko"], "unit": ["L"]}, "1": {"category": {"x": 1}, "unit": {"u": "L"}}}
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier(answers), log=lambda *_: None)
        self.assertEqual([(l["categoryHint"], l["unitHint"]) for l in lines], [(None, None), (None, None)])

    ICE_CREAM = CATEGORIES + [{"slug": "zmrzlina", "path": "Potraviny › Mražené › Zmrzlina"},
                              {"slug": "jogurty", "path": "Potraviny › Mléčné výrobky › Jogurty"}]

    def _dictionary(self):
        dictionary = pretty.Dictionary()
        dictionary.add_category("twister", "jogurty")
        dictionary.add_category("uni twister", "zmrzlina")
        dictionary.add_category("míša", "zmrzlina")
        return dictionary

    def test_slovnik_prebije_kategorii_modelu_jednotka_zustava(self):
        lines = [{"label": "MISA TVAROHOVY 6X45ML", "labelDisplay": "Míša tvarohový nanuk 6x45ml",
                  "quantityUnit": "PCS", "netContentUom": "ML"}]
        logged = []
        ai_classify.classify(lines, self.ICE_CREAM,
                             FakeClassifier({"0": {"category": "mlecko-vyrobky/tvaroh", "unit": "ML"}}),
                             log=logged.append, dictionary=self._dictionary())
        self.assertEqual((lines[0]["categoryHint"], lines[0]["unitHint"]), ("zmrzlina", "ML"))
        self.assertEqual(logged, [])

    def test_delsi_klic_slovniku_ma_prednost(self):
        lines = [{"label": "UNI TWISTER 8X50ML", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.ICE_CREAM, None, log=lambda *_: None, dictionary=self._dictionary())
        self.assertEqual(lines[0]["categoryHint"], "zmrzlina")

    def test_slovnik_plati_i_pri_vypadku_modelu(self):
        lines = [{"label": "MISA 6X45ML", "quantityUnit": "PCS"}, {"label": "MLEKO", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.ICE_CREAM, FakeClassifier(error=urllib.error.URLError("down")),
                             log=lambda *_: None, dictionary=self._dictionary())
        self.assertEqual((lines[0]["categoryHint"], lines[0]["unitHint"]), ("zmrzlina", None))
        self.assertNotIn("categoryHint", lines[1])

    def test_slovnik_s_neznamym_slugem_se_ignoruje(self):
        dictionary = pretty.Dictionary()
        dictionary.add_category("mleko", "vymysl")
        lines = [{"label": "MLEKO", "quantityUnit": "PCS"}]
        logged = []
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": {"category": "mleko"}}),
                             log=logged.append, dictionary=dictionary)
        self.assertEqual(lines[0]["categoryHint"], "mleko")
        self.assertTrue(any("neznámý slug „vymysl“" in message for message in logged))

    def test_klic_slovniku_musi_byt_cele_slovo(self):
        dictionary = pretty.Dictionary()
        dictionary.add_category("misa", "zmrzlina")
        self.assertIsNone(dictionary.category("Misanka sýr", None))
        self.assertEqual(dictionary.category("MÍŠA TVAROHOVÝ", None), "zmrzlina")

    def test_retezcova_polozka_slovniku_ma_prednost(self):
        dictionary = pretty.Dictionary()
        dictionary.add_category("tip", "mleko")
        dictionary.add_category("tip", "testoviny", "coop")
        self.assertEqual(dictionary.category("Tip", "coop"), "testoviny")
        self.assertEqual(dictionary.category("Tip", "albert"), "mleko")

    def test_slovnik_se_nacte_ze_souboru(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "kategorie.tsv").write_text("# komentář\nmíša\tzmrzlina\ntip\tchleba\tcoop\n",
                                                       encoding="utf-8")
            dictionary = pretty.load_dictionary(Path(directory))
        self.assertEqual(dictionary.category("Míša tvarohový", None), "zmrzlina")
        self.assertEqual(dictionary.category("Tip", "coop"), "chleba")
        self.assertIsNone(dictionary.category("Tip", None))

    def test_odpoved_ktera_neni_objekt_se_zahodi(self):
        lines = [{"label": "MLEKO", "quantityUnit": "PCS"}]
        ai_classify.classify(lines, self.CATEGORIES, FakeClassifier({"0": ["mleko", "L"]}), log=lambda *_: None)
        self.assertEqual((lines[0]["categoryHint"], lines[0]["unitHint"]), (None, None))

