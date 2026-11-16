"""Regresní sada rozboru účtenek (regrese.py) — běží v CI spolu s test_worker.py.

Případy v testdata/regrese/ jsou ANONYMIZOVANÉ; skutečné účtenky patří do `uctenky/` mimo git
(README.md, „Regresní sada"). Test selže, když se rozbor kteréhokoli případu změní — úmyslnou
změnu přijme `python3 tools/uctenky/regrese.py --update` a diff expected.json jde do review.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import parse  # noqa: E402
import regrese  # noqa: E402


class RegresniSadaTest(unittest.TestCase):

    def setUp(self):
        self.cases = regrese.find_cases([regrese.GIT_ROOT])

    def test_sada_neni_prazdna_a_kazdy_pripad_ma_ocekavani(self):
        self.assertGreaterEqual(len(self.cases), 5)
        missing = [case.name for case in self.cases if regrese.load_expected(case) is None]
        self.assertEqual(missing, [], "případ bez expected.json — založ přes regrese.py --add")

    def test_rozbor_odpovida_ocekavani(self):
        for case in self.cases:
            with self.subTest(pripad=case.name):
                difference = regrese.diff_documents(regrese.load_expected(case), regrese.analyze(case))
                self.assertEqual(difference, [], "\n" + "\n".join(difference))

    def test_kazdy_pripad_pozna_svuj_profil_i_mezi_ostatnimi(self):
        """Nový profil nesmí přebít starší: `profiles.detect` bere první, který se přihlásí."""
        for case in self.cases:
            with self.subTest(pripad=case.name):
                expected = regrese.load_expected(case)
                lines = parse.merge_segments(regrese.read_texts(case))
                self.assertEqual(parse._profile_for(lines, None).name, expected["profile"])  # noqa: SLF001

    def test_kazdy_profil_retezce_ma_aspon_jeden_pripad(self):
        """Seznam profilů bez případu je vidět v testu, ne až když ho rozbije cizí úprava."""
        covered = {regrese.load_expected(case)["profile"] for case in self.cases}
        known = set(parse.profiles.names()) | {parse.profiles.generic.PROFILE.name}
        self.assertEqual(known - covered, regrese_bez_pripadu(),
                         "profil přibyl nebo dostal případ — uprav seznam v regrese_bez_pripadu()")


def regrese_bez_pripadu() -> set[str]:
    """Profily, pro které v gitu zatím není anonymizovaná účtenka. Cílem je prázdná množina."""
    return {"coop", "obecny"}


class ModelyVeVysledkuTest(unittest.TestCase):
    """Worker k výsledku zapisuje, které modely za ním stojí (ops/sql/receipt-quality.sql)."""

    def test_extraction_nese_nazvy_modelu_a_puvodni_dokument(self):
        import result as result_mod

        class Named:
            def __init__(self, model):
                self.model = model

        document = regrese.analyze(regrese.GIT_ROOT / "albert-plny")
        payload = result_mod.to_payload(document, "text", "ocr-x", None, Named("text-y"), print, None, None)
        self.assertEqual(payload["extraction"]["models"], {"ocr": "ocr-x", "pretty": "text-y"})
        self.assertEqual(payload["model"], "ocr-x")
        # Server z extraction čte totalMatches (ReceiptWorkerService.sumCheck) — nesmí zmizet.
        self.assertEqual({k: v for k, v in payload["extraction"].items() if k != "models"}, document)

    def test_bez_modelu_zadne_prazdne_klice(self):
        import result as result_mod

        document = regrese.analyze(regrese.GIT_ROOT / "albert-plny")
        payload = result_mod.to_payload(document, None, None)
        self.assertEqual(payload["extraction"]["models"], {})


class NastrojTest(unittest.TestCase):
    """Chování regrese.py nad dočasným adresářem — sada v gitu se nemění."""

    def _case(self, root: Path, name: str = "pripad") -> Path:
        case = root / name
        case.mkdir()
        source = regrese.GIT_ROOT / "albert-plny" / "00.ocr.txt"
        (case / "00.ocr.txt").write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
        return case

    def _run(self, cases, **kwargs):
        output: list[str] = []
        options = {"add": False, "update": False, "force": False}
        options.update(kwargs)
        changed = regrese.run_check(cases, log=lambda *args, **_: output.append(" ".join(map(str, args))), **options)
        return changed, "\n".join(output)

    def test_add_zalozi_jen_cistou_uctenku(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            clean = self._case(root, "cista")
            broken = root / "nesedi"
            broken.mkdir()
            source = regrese.GIT_ROOT / "albert-nesedi" / "00.ocr.txt"
            (broken / "00.ocr.txt").write_text(source.read_text(encoding="utf-8"), encoding="utf-8")

            self._run(regrese.find_cases([root]), add=True)
            self.assertTrue((clean / regrese.EXPECTED).is_file())
            self.assertFalse((broken / regrese.EXPECTED).exists(), "účtenka s nesedícím součtem chce --force")

            self._run(regrese.find_cases([root]), add=True, force=True)
            self.assertTrue((broken / regrese.EXPECTED).is_file())

    def test_zmena_rozboru_se_ohlasi_a_update_ji_prijme(self):
        with tempfile.TemporaryDirectory() as tmp:
            case = self._case(Path(tmp))
            self._run([case], add=True)
            expected = regrese.load_expected(case)
            expected["lines"][0]["unitPrice"] = "999.00"
            regrese.save_expected(case, expected)

            changed, output = self._run([case])
            self.assertEqual(changed, 1)
            self.assertIn("999.00", output)
            self.assertEqual(regrese.load_expected(case)["lines"][0]["unitPrice"], "999.00", "bez --update se nepřepisuje")

            changed, _ = self._run([case], update=True)
            self.assertEqual(changed, 0)
            self.assertEqual(self._run([case])[0], 0)

    def test_skore_pocita_radky_i_pri_vypadlem_radku(self):
        expected = regrese.load_expected(regrese.GIT_ROOT / "albert-plny")
        self.assertEqual(regrese.score(expected, expected)["linesMatched"], len(expected["lines"]))
        self.assertTrue(regrese.score(expected, expected)["identical"])

        damaged = json.loads(json.dumps(expected))
        del damaged["lines"][1]                              # OCR vynechalo řádek
        damaged["lines"][0]["rawLabel"] = "R0HLIK43GR"        # a v prvním spletlo dva znaky
        result = regrese.score(expected, damaged)
        self.assertEqual(result["linesMatched"], len(expected["lines"]) - 1, "řádky za výpadkem se nesmí rozsypat")
        self.assertFalse(result["identical"])
        self.assertLess(result["labelSimilarity"], 1.0)
        self.assertGreater(result["labelSimilarity"], 0.9)

    def test_obrazek_se_paruje_k_dilu_podle_nazvu(self):
        with tempfile.TemporaryDirectory() as tmp:
            case = self._case(Path(tmp))
            self.assertIsNone(regrese.images_of(case))
            (case / "puvodni-fotka.jpeg").write_bytes(b"x")
            self.assertIsNone(regrese.images_of(case), "obrázek jiného jména k dílu nepatří")
            (case / "00.jpg").write_bytes(b"x")
            self.assertEqual(regrese.images_of(case), [case / "00.jpg"])

    def test_mereni_a_porovnani_dvou_modelu(self):
        class FakeOcr:
            def __init__(self, model, text):
                self.model, self.text = model, text

            def ocr(self, _image):
                return self.text

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            case = self._case(root)
            (case / "00.jpg").write_bytes(b"x")
            self._run([case], add=True)
            good_text = (case / "00.ocr.txt").read_text(encoding="utf-8")
            bad_text = good_text.replace("ROHLÍK43GR", "R0HLIK43GR").replace("14,50", "74,50")
            self.assertNotEqual(good_text, bad_text)

            quiet = lambda *args, **kwargs: None  # noqa: E731
            good = regrese.run_ocr([case], FakeOcr("dobry", good_text), root / "metriky", log=quiet)
            bad = regrese.run_ocr([case], FakeOcr("horsi", bad_text), root / "metriky", log=quiet)
            self.assertEqual(good["summary"]["identical"], 1.0)
            self.assertLess(bad["summary"]["linesMatched"], 1.0)

            files = sorted((root / "metriky").glob("*.json"))
            self.assertEqual(len(files), 2)
            output: list[str] = []
            first, second = (path for name in ("dobry", "horsi") for path in files if name in path.name)
            self.assertEqual(regrese.run_compare(first, second, log=lambda *a, **_: output.append(" ".join(map(str, a)))), 0)
            self.assertIn("horší v B", "\n".join(output))


if __name__ == "__main__":
    unittest.main()
