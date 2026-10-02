"""Evaluate 20-ticket Progol packages chronologically for every draw.

This tool deliberately scores the *best* ticket in each package.  It therefore
answers the practical question for a player: how close did any of the 20
combinations get, without allowing the future contest into the forecast.
"""
from __future__ import annotations

import argparse
import csv
import itertools
import json
import math
import random
from functools import lru_cache
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATES = ("L", "E", "V")
DRAWS = {
    "MS": (25, 9, ROOT / "app" / "build" / "qa-assets" / "MS.csv"),
    "WEEKEND": (10, 14, ROOT / "app" / "build" / "qa-assets" / "WEEKEND.csv"),
    "REVANCHA": (10, 7, ROOT / "app" / "build" / "qa-assets" / "REVANCHA.csv"),
}


@dataclass(frozen=True)
class Contest:
    number: int
    date: datetime
    results: tuple[str, ...]


@dataclass(frozen=True)
class Settings:
    name: str
    prior: float
    diversity: float


SETTINGS = (
    Settings("prior_0_div_0", 0.0, 0.0),
    Settings("prior_10_div_0", 10.0, 0.0),
    Settings("prior_20_div_0", 20.0, 0.0),
    Settings("prior_20_div_20", 20.0, 0.20),
    Settings("prior_20_div_50", 20.0, 0.50),
    Settings("prior_40_div_20", 40.0, 0.20),
)


def parse_history(path: Path, product: int, slots: int) -> list[Contest]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    expected = ["NPRODUCTO", "CONCURSO"] + [f"R{i}" for i in range(1, slots + 1)]
    if not rows or any(column not in rows[0] for column in expected + ["FECHA"]):
        raise ValueError(f"Formato no válido: {path}")
    contests = []
    for row in rows:
        if int(row["NPRODUCTO"]) != product:
            raise ValueError(f"Producto incorrecto: {path}")
        results = tuple(row[f"R{i}"].strip().upper() for i in range(1, slots + 1))
        if set(results).difference(STATES): raise ValueError(f"Resultado inválido: {path}")
        contests.append(Contest(int(row["CONCURSO"]), datetime.strptime(row["FECHA"].strip(), "%d/%m/%Y"), results))
    return sorted(contests, key=lambda item: (item.date, item.number))


def composition(results: tuple[str, ...]) -> tuple[int, int, int]:
    return tuple(results.count(state) for state in STATES)


def multinomial(composition_: tuple[int, int, int]) -> int:
    total = sum(composition_)
    return math.factorial(total) // math.prod(math.factorial(value) for value in composition_)


def global_probabilities(history: list[Contest]) -> dict[str, float]:
    counts = Counter(result for contest in history for result in contest.results)
    total = sum(counts.values()) + 3
    return {state: (counts[state] + 1) / total for state in STATES}


def composition_probabilities(history: list[Contest], slots: int, prior: float) -> dict[tuple[int, int, int], float]:
    probability = global_probabilities(history)
    observed = Counter(composition(contest.results) for contest in history)
    values = {}
    for local in range(slots + 1):
        for draw in range(slots - local + 1):
            away = slots - local - draw
            key = (local, draw, away)
            base = multinomial(key) * probability["L"] ** local * probability["E"] ** draw * probability["V"] ** away
            values[key] = observed[key] + prior * base
    total = sum(values.values())
    return {key: value / total for key, value in values.items()}


@lru_cache(maxsize=None)
def sequences_for(comp: tuple[int, int, int]) -> tuple[str, ...]:
    slots = sum(comp)
    output = []
    for local_positions in itertools.combinations(range(slots), comp[0]):
        remaining = [position for position in range(slots) if position not in local_positions]
        for draw_positions in itertools.combinations(remaining, comp[1]):
            sequence = ["V"] * slots
            for position in local_positions: sequence[position] = "L"
            for position in draw_positions: sequence[position] = "E"
            output.append("".join(sequence))
    return tuple(output)


@lru_cache(maxsize=None)
def candidate_records(comp: tuple[int, int, int]) -> tuple[tuple[str, int], ...]:
    # The 14-match draw has compositions with hundreds of thousands of
    # permutations. A reproducible sample is enough for package comparison and
    # matches the bounded-candidate approach used by the Android engine.
    if multinomial(comp) > 250:
        rng = random.Random(731 + comp[0] * 101 + comp[1] * 17 + comp[2])
        sampled: set[str] = set()
        states = "L" * comp[0] + "E" * comp[1] + "V" * comp[2]
        while len(sampled) < 250:
            order = list(states)
            rng.shuffle(order)
            sampled.add("".join(order))
        source = tuple(sorted(sampled))
    else:
        source = sequences_for(comp)
    return tuple(
        (sequence, max(len(list(group)) for _, group in itertools.groupby(sequence)))
        for sequence in source
    )


def hamming(left: str, right: str) -> int:
    return sum(a != b for a, b in zip(left, right))


def package(history: list[Contest], slots: int, settings: Settings, quantity: int = 20) -> list[str]:
    comp_probability = composition_probabilities(history, slots, settings.prior)
    quotas = {key: math.floor(quantity * value) for key, value in comp_probability.items()}
    remainder = quantity - sum(quotas.values())
    for key in sorted(comp_probability, key=lambda key: (-(quantity * comp_probability[key] - quotas[key]), -comp_probability[key], key))[:remainder]:
        quotas[key] += 1
    chosen: list[str] = []
    pools = {comp: list(candidate_records(comp)) for comp, quota in quotas.items() if quota}
    for comp in sorted(comp_probability, key=lambda key: (-comp_probability[key], key)):
        for _ in range(quotas[comp]):
            candidates = pools[comp]
            # All tickets in this composition have the same base probability;
            # only package coverage, streaks and deterministic tie breaking differ.
            if settings.diversity == 0 or not chosen:
                best = min(candidates, key=lambda candidate: (candidate[1], candidate[0]))
            else:
                best = min(candidates, key=lambda candidate: (
                    -settings.diversity * min(hamming(candidate[0], existing) for existing in chosen),
                    candidate[1], candidate[0],
                ))
            candidates.remove(best)
            chosen.append(best[0])
    return chosen


def evaluate(history: list[Contest], slots: int, settings: Settings, burn_in: int = 100, step: int = 1) -> list[int]:
    return [max(sum(a == b for a, b in zip(ticket, target.results)) for ticket in package(history[:index], slots, settings))
            for index, target in enumerate(history) if index >= burn_in and (index - burn_in) % step == 0]


def summarize(scores: list[int], slots: int) -> dict[str, float | int]:
    return {
        "contests": len(scores), "mean_best_hits": round(sum(scores) / len(scores), 4),
        "median_best_hits": sorted(scores)[len(scores) // 2],
        "at_least_slots_minus_1": round(sum(score >= slots - 1 for score in scores) / len(scores), 4),
        "perfect": round(sum(score == slots for score in scores) / len(scores), 4),
    }


def run_draw(draw: str, path: Path, step: int) -> dict[str, object]:
    product, slots, _ = DRAWS[draw]
    history = parse_history(path, product, slots)
    split = 100 + int((len(history) - 100) * .70)
    development, holdout = {}, {}
    for setting in SETTINGS:
        scores = evaluate(history, slots, setting, step=step)
        split_scores = math.ceil((split - 100) / step)
        development[setting.name] = summarize(scores[:split_scores], slots)
        holdout[setting.name] = summarize(scores[split_scores:], slots)
    champion = max(SETTINGS, key=lambda setting: (development[setting.name]["mean_best_hits"], development[setting.name]["at_least_slots_minus_1"]))
    return {"draw": draw, "slots": slots, "history": len(history), "champion": champion.name,
            "development": development, "holdout": holdout}


def markdown(result: dict[str, object]) -> str:
    lines = ["# Backtest de paquetes de 20 combinaciones", "", "No usa datos futuros: cada paquete se genera antes del concurso evaluado.", f"Se evaluó cada {result['step']} concurso para mantener el análisis reproducible y manejable.", ""]
    for draw in result["draws"]:
        lines += [f"## {draw['draw']} · {draw['slots']} partidos", "", f"Histórico: {draw['history']} concursos. Parámetro elegido en desarrollo: `{draw['champion']}`.", "", "| Configuración | Mejor acierto medio (desarrollo) | Mejor acierto medio (prueba final) | Cerca del pleno en prueba final |", "|---|---:|---:|---:|"]
        for setting in SETTINGS:
            name = setting.name; dev = draw["development"][name]; test = draw["holdout"][name]
            lines.append(f"| {name} | {dev['mean_best_hits']:.2f} | {test['mean_best_hits']:.2f} | {test['at_least_slots_minus_1'] * 100:.2f}% |")
        lines.append("")
    lines.append("La configuración se considera adoptable solo si mejora también el tramo final reservado; de lo contrario se conserva la actual `prior_20_div_20`.")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Backtest de paquetes Progol")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "outputs" / "package-backtest")
    parser.add_argument("--step", type=int, default=5, help="Evaluar uno de cada N concursos (predeterminado: 5)")
    args = parser.parse_args(); args.output_dir.mkdir(parents=True, exist_ok=True)
    if args.step < 1: raise ValueError("step debe ser positivo")
    result = {"step": args.step, "draws": [run_draw(draw, source, args.step) for draw, (_, _, source) in DRAWS.items()]}
    (args.output_dir / "report.md").write_text(markdown(result), encoding="utf-8")
    (args.output_dir / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(args.output_dir / "report.md")
    return 0


if __name__ == "__main__": raise SystemExit(main())
