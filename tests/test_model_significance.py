import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "src"))

import run_model_significance as significance  # noqa: E402
from progol_media_semana.engine import Contest  # noqa: E402


def history(count: int):
    start = datetime(2020, 1, 1)
    return [
        Contest(
            25,
            index + 1,
            start + timedelta(days=7 * index),
            tuple("LEV"[(index + slot) % 3] for slot in range(9)),
        )
        for index in range(count)
    ]


class SignificanceTests(unittest.TestCase):
    def test_bootstrap_flags_a_clear_difference(self):
        result = significance.bootstrap([2.0] * 60, [1.0] * 60, samples=400, seed=1)
        self.assertAlmostEqual(1.0, result["difference"])
        self.assertGreater(result["ci_low"], 0)
        self.assertLess(result["p_value"], 0.05)

    def test_bootstrap_keeps_similar_series_inconclusive(self):
        a = [1.0, 2.0, 1.5, 2.5] * 20
        b = [1.1, 1.9, 1.6, 2.4] * 20
        result = significance.bootstrap(a, b, samples=400, seed=2)
        self.assertLess(result["ci_low"], 0)
        self.assertGreater(result["ci_high"], 0)

    def test_walk_forward_covers_every_model_and_target(self):
        losses, joint_independent, joint_composition = significance.walk_forward(
            history(130), burn_in=100
        )
        self.assertEqual(30, len(losses["global_frequency"]))
        self.assertEqual(30, len(joint_independent))
        self.assertEqual(30, len(joint_composition))
        for name, values in losses.items():
            self.assertEqual(30, len(values), name)
            self.assertTrue(all(value > 0 for value in values), name)


if __name__ == "__main__":
    unittest.main()
