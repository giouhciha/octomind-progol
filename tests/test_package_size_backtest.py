import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "src"))

import run_package_backtest as backtest  # noqa: E402
import run_package_size_backtest as size_backtest  # noqa: E402


def history(count: int):
    start = datetime(2020, 1, 1)
    return [
        backtest.Contest(index + 1, start + timedelta(days=7 * index),
                         tuple("LEV"[(index + slot) % 3] for slot in range(7)))
        for index in range(count)
    ]


class PackageSizeTests(unittest.TestCase):
    def test_best_hits_uses_only_the_requested_prefix(self):
        tickets = ["LLLLLLL", "VVVVVVV", "EEEEEEE"]
        results = ("V", "V", "V", "V", "V", "V", "V")
        self.assertEqual(7, size_backtest.best_hits(tickets, results, 2))
        self.assertEqual(0, size_backtest.best_hits(tickets, results, 1))

    def test_evaluate_sizes_returns_one_series_per_size(self):
        scores = size_backtest.evaluate_sizes(
            history(110), 7, size_backtest.CHAMPION, (1, 3, 5), burn_in=100, step=1
        )
        self.assertEqual({1, 3, 5}, set(scores))
        for values in scores.values():
            self.assertEqual(10, len(values))


if __name__ == "__main__":
    unittest.main()
