import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "evaluate_vad.py"
SPEC = importlib.util.spec_from_file_location("evaluate_vad", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class EvaluateVadTest(unittest.TestCase):
    def test_merges_touching_and_overlapping_intervals(self):
        self.assertEqual([(0, 30), (40, 50)], MODULE.merge_intervals([(10, 30), (0, 10), (40, 50)]))

    def test_intersection_duration_is_exact(self):
        self.assertEqual(70, MODULE.intersection_duration([(0, 100)], [(20, 60), (70, 100)]))

    def test_boundary_metrics_count_fragmentation(self):
        start, end, fragments = MODULE.boundary_metrics([(100, 500)], [(80, 250), (260, 520)])
        self.assertEqual(160, start)
        self.assertEqual(20, end)
        self.assertEqual(2.0, fragments)


if __name__ == "__main__":
    unittest.main()
