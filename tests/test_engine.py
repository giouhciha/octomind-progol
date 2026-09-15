from __future__ import annotations

import math
import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from progol_media_semana.engine import (
    Contest,
    audit_history,
    parse_history_csv,
    recent_position_model,
    recommend_combinations,
    run_experiment,
    transition_model,
)


HEADER = "NPRODUCTO,CONCURSO,R1,R2,R3,R4,R5,R6,R7,R8,R9,FECHA\n"


class ParsingTests(unittest.TestCase):
    def test_parse_sorts_chronologically_and_audits_gap(self) -> None:
        text = HEADER + (
            "25,3,L,E,V,L,E,V,L,E,V,03/01/2026\n"
            "25,1,V,L,E,V,L,E,V,L,E,01/01/2026\n"
        )
        history = parse_history_csv(text)
        self.assertEqual([contest.number for contest in history], [1, 3])
        audit = audit_history(history)
        self.assertEqual(audit.missing_contests, (2,))
        self.assertEqual(audit.duplicate_contests, ())

    def test_rejects_invalid_state(self) -> None:
        text = HEADER + "25,1,L,E,X,L,E,V,L,E,V,01/01/2026\n"
        with self.assertRaisesRegex(ValueError, "Invalid result state"):
            parse_history_csv(text)

    def test_rejects_wrong_schema(self) -> None:
        with self.assertRaisesRegex(ValueError, "columns"):
            parse_history_csv("CONCURSO,R1\n1,L\n")

    def test_rejects_another_product(self) -> None:
        text = HEADER + "10,1,L,E,V,L,E,V,L,E,V,01/01/2026\n"
        with self.assertRaisesRegex(ValueError, "Unexpected product"):
            parse_history_csv(text)


class ModelTests(unittest.TestCase):
    def setUp(self) -> None:
        start = datetime(2020, 1, 1)
        states = ("L", "E", "V")
        self.history = [
            Contest(
                25,
                index + 1,
                start + timedelta(days=7 * index),
                tuple(states[(index + slot) % 3] for slot in range(9)),
            )
            for index in range(150)
        ]

    def test_probabilities_are_normalized(self) -> None:
        for model_output in (
            recent_position_model(self.history, 26.0),
            transition_model(self.history),
        ):
            self.assertEqual(len(model_output), 9)
            for probability in model_output:
                self.assertTrue(math.isclose(sum(probability.values()), 1.0))
                self.assertTrue(all(value > 0 for value in probability.values()))

    def test_experiment_has_holdout_and_forecast(self) -> None:
        result = run_experiment(self.history, burn_in=30)
        self.assertEqual(result.next_contest, 151)
        self.assertEqual(len(result.forecast), 9)
        self.assertGreater(len(result.holdout_metrics), 1)
        self.assertEqual(len(result.recommended_combinations), 20)
        self.assertIn(
            result.champion,
            {metric.model for metric in result.development_metrics},
        )

    def test_recommended_combinations_are_balanced_and_unique(self) -> None:
        probability = tuple(
            {"L": 0.44, "E": 0.26, "V": 0.30} for _ in range(9)
        )
        recommendations = recommend_combinations(
            self.history, probability, quantity=10
        )
        sequences = [item["sequence"] for item in recommendations]
        self.assertEqual(len(sequences), len(set(sequences)))
        self.assertNotEqual(sequences[0], "LLLLLLLLL")
        for item in recommendations:
            self.assertEqual(item["local"] + item["draw"] + item["away"], 9)
            self.assertGreater(item["probability"], 0)


if __name__ == "__main__":
    unittest.main()
