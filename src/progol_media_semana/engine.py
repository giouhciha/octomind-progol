from __future__ import annotations

import csv
import io
import itertools
import math
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Callable, Hashable, Iterable, Sequence, TypeVar


OFFICIAL_CSV_URL = (
    "https://www.loterianacional.gob.mx/Documentos/Historicos/"
    "Progol-Media-S.csv"
)
EXPECTED_PRODUCT = 25
STATES = ("L", "E", "V")
SLOTS = 9
EPSILON = 1e-15
Key = TypeVar("Key", bound=Hashable)


@dataclass(frozen=True)
class Contest:
    product: int
    number: int
    date: datetime
    results: tuple[str, ...]


@dataclass(frozen=True)
class AuditResult:
    contests: int
    first_contest: int
    last_contest: int
    first_date: str
    last_date: str
    missing_contests: tuple[int, ...]
    duplicate_contests: tuple[int, ...]
    state_counts: dict[str, int]


@dataclass(frozen=True)
class MetricResult:
    model: str
    observations: int
    log_loss: float
    brier: float
    accuracy: float
    top2_coverage: float


@dataclass(frozen=True)
class JointMetricResult:
    model: str
    contests: int
    card_log_loss: float
    slot_equivalent_log_loss: float


@dataclass(frozen=True)
class ExperimentResult:
    audit: AuditResult
    burn_in: int
    development_end_contest: int
    champion: str
    development_metrics: tuple[MetricResult, ...]
    holdout_metrics: tuple[MetricResult, ...]
    joint_development_metrics: tuple[JointMetricResult, ...]
    joint_holdout_metrics: tuple[JointMetricResult, ...]
    next_contest: int
    forecast: tuple[dict[str, object], ...]
    recommended_combinations: tuple[dict[str, object], ...]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def parse_history_csv(text: str) -> list[Contest]:
    """Parse and strictly validate the official Media Semana CSV."""
    reader = csv.DictReader(io.StringIO(text.lstrip("\ufeff")))
    expected = ["NPRODUCTO", "CONCURSO"] + [f"R{i}" for i in range(1, 10)] + ["FECHA"]
    if reader.fieldnames != expected:
        raise ValueError(
            "CSV columns do not match the expected Media Semana schema: "
            f"{reader.fieldnames!r}"
        )

    contests: list[Contest] = []
    for line_number, row in enumerate(reader, start=2):
        try:
            product = int(row["NPRODUCTO"].strip())
            number = int(row["CONCURSO"].strip())
            date = datetime.strptime(row["FECHA"].strip(), "%d/%m/%Y")
            results = tuple(row[f"R{i}"].strip().upper() for i in range(1, 10))
        except (AttributeError, TypeError, ValueError) as exc:
            raise ValueError(f"Invalid data on CSV line {line_number}: {exc}") from exc

        invalid = [state for state in results if state not in STATES]
        if invalid:
            raise ValueError(
                f"Invalid result state on CSV line {line_number}: {invalid!r}"
            )
        if product != EXPECTED_PRODUCT:
            raise ValueError(
                f"Unexpected product on CSV line {line_number}: {product}"
            )
        contests.append(Contest(product, number, date, results))

    if not contests:
        raise ValueError("The CSV does not contain contests")

    return sorted(contests, key=lambda contest: (contest.date, contest.number))


def audit_history(history: Sequence[Contest]) -> AuditResult:
    if not history:
        raise ValueError("History cannot be empty")

    numbers = [contest.number for contest in history]
    number_counts = Counter(numbers)
    duplicate_contests = tuple(
        sorted(number for number, count in number_counts.items() if count > 1)
    )
    expected_numbers = set(range(min(numbers), max(numbers) + 1))
    missing_contests = tuple(sorted(expected_numbers.difference(numbers)))
    state_counts = Counter(
        state for contest in history for state in contest.results
    )

    return AuditResult(
        contests=len(history),
        first_contest=min(numbers),
        last_contest=max(numbers),
        first_date=min(contest.date for contest in history).strftime("%Y-%m-%d"),
        last_date=max(contest.date for contest in history).strftime("%Y-%m-%d"),
        missing_contests=missing_contests,
        duplicate_contests=duplicate_contests,
        state_counts={state: state_counts[state] for state in STATES},
    )


def _normalize(values: dict[Key, float]) -> dict[Key, float]:
    total = sum(values.values())
    if total <= 0:
        uniform = 1.0 / len(values)
        return {key: uniform for key in values}
    return {key: value / total for key, value in values.items()}


def _global_probabilities(history: Sequence[Contest], alpha: float = 1.0) -> dict[str, float]:
    counts = Counter(state for contest in history for state in contest.results)
    return _normalize({state: counts[state] + alpha for state in STATES})


def _composition(results: Sequence[str]) -> tuple[int, int, int]:
    counts = Counter(results)
    return counts["L"], counts["E"], counts["V"]


def _multinomial_count(composition: tuple[int, int, int]) -> int:
    local, draw, away = composition
    return math.factorial(SLOTS) // (
        math.factorial(local) * math.factorial(draw) * math.factorial(away)
    )


def _multinomial_probability(
    composition: tuple[int, int, int], probability: dict[str, float]
) -> float:
    local, draw, away = composition
    return (
        _multinomial_count(composition)
        * probability["L"] ** local
        * probability["E"] ** draw
        * probability["V"] ** away
    )


def composition_probabilities(
    history: Sequence[Contest], prior_strength: float = 20.0
) -> dict[tuple[int, int, int], float]:
    """Smoothed probability for the total L/E/V composition of a card."""
    global_probability = _global_probabilities(history)
    observed = Counter(_composition(contest.results) for contest in history)
    raw: dict[tuple[int, int, int], float] = {}
    for local in range(SLOTS + 1):
        for draw in range(SLOTS - local + 1):
            away = SLOTS - local - draw
            key = (local, draw, away)
            raw[key] = observed[key] + prior_strength * _multinomial_probability(
                key, global_probability
            )
    return _normalize(raw)


def _maximum_run(sequence: Sequence[str]) -> int:
    maximum = 0
    current = 0
    previous: str | None = None
    for state in sequence:
        current = current + 1 if state == previous else 1
        maximum = max(maximum, current)
        previous = state
    return maximum


def _hamming(left: Sequence[str], right: Sequence[str]) -> int:
    return sum(a != b for a, b in zip(left, right))


def recommend_combinations(
    history: Sequence[Contest],
    slot_probabilities: Sequence[dict[str, float]],
    quantity: int = 20,
    diversity_weight: float = 0.20,
) -> tuple[dict[str, object], ...]:
    """Allocate a diverse package across historically plausible compositions."""
    if len(slot_probabilities) != SLOTS:
        raise ValueError(f"Expected probabilities for {SLOTS} slots")
    if quantity < 1:
        raise ValueError("quantity must be positive")

    composition_probability = composition_probabilities(history)
    raw_quotas = {
        composition: quantity * probability
        for composition, probability in composition_probability.items()
    }
    quotas = {
        composition: math.floor(raw_quota)
        for composition, raw_quota in raw_quotas.items()
    }
    remaining = quantity - sum(quotas.values())
    remainder_order = sorted(
        composition_probability,
        key=lambda composition: (
            -(raw_quotas[composition] - quotas[composition]),
            -composition_probability[composition],
            composition,
        ),
    )
    for composition in remainder_order[:remaining]:
        quotas[composition] += 1

    candidates_by_composition: dict[
        tuple[int, int, int], list[dict[str, object]]
    ] = {composition: [] for composition, quota in quotas.items() if quota > 0}
    for sequence in itertools.product(STATES, repeat=SLOTS):
        comp = _composition(sequence)
        if comp not in candidates_by_composition:
            continue
        independent_probability = math.prod(
            slot_probabilities[slot][state]
            for slot, state in enumerate(sequence)
        )
        candidates_by_composition[comp].append(
            {
                "sequence": sequence,
                "composition": comp,
                "probability": independent_probability,
                "max_run": _maximum_run(sequence),
            }
        )

    composition_tasks = [
        composition
        for composition, quota in sorted(
            quotas.items(),
            key=lambda item: (-composition_probability[item[0]], item[0]),
        )
        for _ in range(quota)
    ]
    selected: list[dict[str, object]] = []
    for composition in composition_tasks:
        pool = candidates_by_composition[composition]
        best_index = max(
            range(len(pool)),
            key=lambda index: (
                math.log(max(float(pool[index]["probability"]), EPSILON))
                + (
                    diversity_weight
                    * min(
                        _hamming(pool[index]["sequence"], item["sequence"])
                        for item in selected
                    )
                    if selected
                    else 0.0
                ),
                -int(pool[index]["max_run"]),
                tuple(pool[index]["sequence"]),
            ),
        )
        selected.append(pool.pop(best_index))

    best_probability = max(float(item["probability"]) for item in selected)
    output: list[dict[str, object]] = []
    for rank, candidate in enumerate(selected, start=1):
        local, draw, away = candidate["composition"]
        probability = float(candidate["probability"])
        output.append(
            {
                "rank": rank,
                "sequence": "".join(candidate["sequence"]),
                "local": local,
                "draw": draw,
                "away": away,
                "probability": round(probability, 10),
                "composition_probability": round(
                    composition_probability[candidate["composition"]], 8
                ),
                "relative_to_best": round(probability / best_probability, 6),
                "minimum_distance": (
                    0
                    if rank == 1
                    else min(
                        _hamming(candidate["sequence"], item["sequence"])
                        for item in selected[: rank - 1]
                    )
                ),
            }
        )
    return tuple(output)


def uniform_model(history: Sequence[Contest]) -> tuple[dict[str, float], ...]:
    del history
    probability = {state: 1.0 / len(STATES) for state in STATES}
    return tuple(dict(probability) for _ in range(SLOTS))


def global_frequency_model(history: Sequence[Contest]) -> tuple[dict[str, float], ...]:
    probability = _global_probabilities(history)
    return tuple(dict(probability) for _ in range(SLOTS))


def position_frequency_model(
    history: Sequence[Contest], prior_strength: float = 20.0
) -> tuple[dict[str, float], ...]:
    global_probability = _global_probabilities(history)
    output: list[dict[str, float]] = []
    for slot in range(SLOTS):
        counts = Counter(contest.results[slot] for contest in history)
        output.append(
            _normalize(
                {
                    state: counts[state] + prior_strength * global_probability[state]
                    for state in STATES
                }
            )
        )
    return tuple(output)


def recent_position_model(
    history: Sequence[Contest],
    half_life: float,
    prior_strength: float = 20.0,
) -> tuple[dict[str, float], ...]:
    global_probability = _global_probabilities(history)
    decay = math.log(2.0) / half_life
    output: list[dict[str, float]] = []
    for slot in range(SLOTS):
        counts = {state: 0.0 for state in STATES}
        for age, contest in enumerate(reversed(history)):
            counts[contest.results[slot]] += math.exp(-decay * age)
        output.append(
            _normalize(
                {
                    state: counts[state] + prior_strength * global_probability[state]
                    for state in STATES
                }
            )
        )
    return tuple(output)


def transition_model(
    history: Sequence[Contest], prior_strength: float = 20.0
) -> tuple[dict[str, float], ...]:
    position_probability = position_frequency_model(history)
    if len(history) < 2:
        return position_probability

    output: list[dict[str, float]] = []
    for slot in range(SLOTS):
        previous_state = history[-1].results[slot]
        counts = Counter()
        for earlier, later in zip(history, history[1:]):
            if (
                later.number == earlier.number + 1
                and earlier.results[slot] == previous_state
            ):
                counts[later.results[slot]] += 1
        output.append(
            _normalize(
                {
                    state: counts[state]
                    + prior_strength * position_probability[slot][state]
                    for state in STATES
                }
            )
        )
    return tuple(output)


def blended_model(
    history: Sequence[Contest],
    half_life: float = 52.0,
    transition_weight: float = 0.25,
) -> tuple[dict[str, float], ...]:
    recent = recent_position_model(history, half_life=half_life)
    transition = transition_model(history)
    return tuple(
        _normalize(
            {
                state: (1.0 - transition_weight) * recent[slot][state]
                + transition_weight * transition[slot][state]
                for state in STATES
            }
        )
        for slot in range(SLOTS)
    )


ModelFunction = Callable[[Sequence[Contest]], tuple[dict[str, float], ...]]


def model_candidates() -> dict[str, ModelFunction]:
    candidates: dict[str, ModelFunction] = {
        "uniform": uniform_model,
        "global_frequency": global_frequency_model,
        "position_frequency": position_frequency_model,
        "transition": transition_model,
    }
    for half_life in (13.0, 26.0, 52.0, 104.0):
        candidates[f"recent_hl_{int(half_life)}"] = (
            lambda history, half_life=half_life: recent_position_model(
                history, half_life=half_life
            )
        )
    for half_life in (26.0, 52.0, 104.0):
        candidates[f"blend_hl_{int(half_life)}"] = (
            lambda history, half_life=half_life: blended_model(
                history, half_life=half_life
            )
        )
    return candidates


def _ranked_states(probability: dict[str, float]) -> list[str]:
    state_order = {state: index for index, state in enumerate(STATES)}
    return sorted(STATES, key=lambda state: (-probability[state], state_order[state]))


def _metric_from_predictions(
    model: str,
    predictions: Iterable[tuple[dict[str, float], str]],
) -> MetricResult:
    observations = 0
    log_loss = 0.0
    brier = 0.0
    correct = 0
    top2_correct = 0
    for probability, actual in predictions:
        observations += 1
        log_loss -= math.log(max(probability[actual], EPSILON))
        brier += sum(
            (probability[state] - (1.0 if state == actual else 0.0)) ** 2
            for state in STATES
        )
        ranked = _ranked_states(probability)
        correct += int(ranked[0] == actual)
        top2_correct += int(actual in ranked[:2])

    if observations == 0:
        raise ValueError("Cannot calculate metrics without observations")
    return MetricResult(
        model=model,
        observations=observations,
        log_loss=log_loss / observations,
        brier=brier / observations,
        accuracy=correct / observations,
        top2_coverage=top2_correct / observations,
    )


def _joint_metric(
    model: str,
    history: Sequence[Contest],
    target_indexes: Iterable[int],
) -> JointMetricResult:
    contests = 0
    log_loss = 0.0
    for target_index in target_indexes:
        past = history[:target_index]
        actual = history[target_index].results
        global_probability = _global_probabilities(past)
        if model == "global_independent":
            probability = math.prod(global_probability[state] for state in actual)
        elif model == "global_composition":
            composition = _composition(actual)
            probability = (
                composition_probabilities(past)[composition]
                / _multinomial_count(composition)
            )
        else:
            raise ValueError(f"Unknown joint model: {model}")
        log_loss -= math.log(max(probability, EPSILON))
        contests += 1

    if contests == 0:
        raise ValueError("Cannot calculate joint metrics without contests")
    card_log_loss = log_loss / contests
    return JointMetricResult(
        model=model,
        contests=contests,
        card_log_loss=card_log_loss,
        slot_equivalent_log_loss=card_log_loss / SLOTS,
    )


def run_experiment(
    history: Sequence[Contest],
    burn_in: int = 100,
    development_fraction: float = 0.70,
) -> ExperimentResult:
    if len(history) <= burn_in + 20:
        raise ValueError("Insufficient history for development and holdout evaluation")
    if not 0.5 <= development_fraction <= 0.9:
        raise ValueError("development_fraction must be between 0.5 and 0.9")

    audit = audit_history(history)
    candidates = model_candidates()
    prediction_rows: dict[str, list[tuple[int, dict[str, float], str]]] = {
        name: [] for name in candidates
    }

    for target_index in range(burn_in, len(history)):
        past = history[:target_index]
        target = history[target_index]
        for name, model in candidates.items():
            probabilities = model(past)
            for slot, actual in enumerate(target.results):
                prediction_rows[name].append(
                    (target_index, probabilities[slot], actual)
                )

    evaluation_contests = len(history) - burn_in
    development_contests = max(
        1, min(evaluation_contests - 1, int(evaluation_contests * development_fraction))
    )
    split_index = burn_in + development_contests

    development_metrics: list[MetricResult] = []
    holdout_metrics: list[MetricResult] = []
    for name, rows in prediction_rows.items():
        development_metrics.append(
            _metric_from_predictions(
                name,
                ((probability, actual) for index, probability, actual in rows if index < split_index),
            )
        )
        holdout_metrics.append(
            _metric_from_predictions(
                name,
                ((probability, actual) for index, probability, actual in rows if index >= split_index),
            )
        )

    development_metrics.sort(key=lambda metric: (metric.log_loss, metric.brier, metric.model))
    holdout_metrics.sort(key=lambda metric: (metric.log_loss, metric.brier, metric.model))
    joint_models = ("global_independent", "global_composition")
    joint_development_metrics = tuple(
        sorted(
            (
                _joint_metric(model, history, range(burn_in, split_index))
                for model in joint_models
            ),
            key=lambda metric: metric.card_log_loss,
        )
    )
    joint_holdout_metrics = tuple(
        sorted(
            (
                _joint_metric(model, history, range(split_index, len(history)))
                for model in joint_models
            ),
            key=lambda metric: metric.card_log_loss,
        )
    )
    champion = development_metrics[0].model
    forecast_probability = candidates[champion](history)
    forecast = tuple(
        {
            "slot": slot + 1,
            "probabilities": {
                state: round(probability[state], 6) for state in STATES
            },
            "prediction": _ranked_states(probability)[0],
            "second_choice": _ranked_states(probability)[1],
            "entropy": round(
                -sum(
                    probability[state] * math.log(probability[state])
                    for state in STATES
                    if probability[state] > 0
                )
                / math.log(len(STATES)),
                6,
            ),
        }
        for slot, probability in enumerate(forecast_probability)
    )

    return ExperimentResult(
        audit=audit,
        burn_in=burn_in,
        development_end_contest=history[split_index - 1].number,
        champion=champion,
        development_metrics=tuple(development_metrics),
        holdout_metrics=tuple(holdout_metrics),
        joint_development_metrics=joint_development_metrics,
        joint_holdout_metrics=joint_holdout_metrics,
        next_contest=max(contest.number for contest in history) + 1,
        forecast=forecast,
        recommended_combinations=recommend_combinations(
            history, forecast_probability, quantity=20
        ),
    )
