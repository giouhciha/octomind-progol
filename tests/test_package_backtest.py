import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import run_package_backtest as backtest


class PackageBacktestTests(unittest.TestCase):
    def test_package_contains_twenty_unique_sequences(self):
        history = [backtest.Contest(index, __import__("datetime").datetime(2020, 1, 1), tuple("LEV"[slot % 3] for slot in range(7))) for index in range(100)]
        picks = backtest.package(history, 7, backtest.SETTINGS[3])
        self.assertEqual(20, len(picks))
        self.assertEqual(20, len(set(picks)))

    def test_summary_uses_best_ticket_metric(self):
        summary = backtest.summarize([3, 5, 5, 7], 7)
        self.assertEqual(5.0, summary["mean_best_hits"])
        self.assertEqual(.25, summary["perfect"])


if __name__ == "__main__": unittest.main()
