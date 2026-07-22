import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "evaluate_asr.py"
SPEC = importlib.util.spec_from_file_location("evaluate_asr", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class EvaluateAsrTest(unittest.TestCase):
    def test_normalization_keeps_spanish_letters_and_removes_punctuation(self):
        self.assertEqual(["qué", "tal", "señor"], MODULE.normalize_words("¿Qué tal, señor?"))

    def test_word_error_breakdown(self):
        counts = MODULE.edit_counts(
            ["uno", "dos", "tres"],
            ["uno", "cuatro", "tres", "cinco"],
        )
        self.assertEqual(1, counts.substitutions)
        self.assertEqual(0, counts.deletions)
        self.assertEqual(1, counts.insertions)
        self.assertAlmostEqual(2 / 3, counts.rate)

    def test_empty_reference_has_no_rate_but_detectable_insertion(self):
        counts = MODULE.edit_counts([], ["texto"])
        self.assertIsNone(counts.rate)
        self.assertEqual(1, counts.insertions)


if __name__ == "__main__":
    unittest.main()
