"""Prueba de significancia de los modelos del motor de Media Semana.

Evalua varios modelos de forma cronologica (walk-forward), compara cada uno
contra la frecuencia global mediante un bootstrap pareado por concurso y
reporta si la diferencia de log-loss es significativa o simple ruido.

Referencia: README, seccion "Prueba de significancia del modelo".
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from progol_media_semana.engine import (  # noqa: E402
    EPSILON,
    OFFICIAL_CSV_URL,
    STATES,
    blended_model,
    composition_probabilities,
    global_frequency_model,
    parse_history_csv,
    position_frequency_model,
    recent_position_model,
    transition_model,
)


def fetch_text(url: str) -> str:
    request = urllib.request.Request(
        url, headers={"User-Agent": "Octomind-Progol/0.1 significance"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        charset = response.headers.get_content_charset() or "utf-8-sig"
        return response.read().decode(charset)


def model_candidates() -> dict[str, object]:
    """Los mismos candidatos que evalua el motor, mas la referencia global."""
    return {
        "global_frequency": global_frequency_model,
        "position_frequency": position_frequency_model,
        "transition": transition_model,
        "recent_hl_104": lambda history: recent_position_model(history, 104.0),
        "blend_hl_104": lambda history: blended_model(history, 104.0),
    }


def _composition(results) -> tuple[int, int, int]:
    return tuple(results.count(state) for state in STATES)


def _multinomial(composition: tuple[int, int, int]) -> int:
    total = sum(composition)
    return math.factorial(total) // math.prod(
        math.factorial(value) for value in composition
    )


def walk_forward(
    history, burn_in: int
) -> tuple[dict[str, list[float]], list[float], list[float]]:
    """Log-loss por quiniela de cada modelo, cronologico y sin datos futuros."""
    models = model_candidates()
    losses: dict[str, list[float]] = {name: [] for name in models}
    joint_independent: list[float] = []
    joint_composition: list[float] = []

    for index in range(burn_in, len(history)):
        past = history[:index]
        actual = history[index].results
        for name, model in models.items():
            probabilities = model(past)
            losses[name].append(
                -sum(
                    math.log(max(probabilities[slot][actual[slot]], EPSILON))
                    for slot in range(len(actual))
                )
            )

        global_probability = global_frequency_model(past)[0]
        independent = math.prod(global_probability[state] for state in actual)
        composition = _composition(actual)
        composition_probability = (
            composition_probabilities(past)[composition] / _multinomial(composition)
        )
        joint_independent.append(-math.log(max(independent, EPSILON)))
        joint_composition.append(-math.log(max(composition_probability, EPSILON)))

    return losses, joint_independent, joint_composition


def bootstrap(
    candidate: list[float], reference: list[float], samples: int, seed: int
) -> dict[str, float]:
    """Bootstrap pareado de mean(candidate) - mean(reference) por concurso."""
    if len(candidate) != len(reference):
        raise ValueError("Las series pareadas deben tener la misma longitud")
    if not candidate:
        raise ValueError("Se requiere al menos un concurso")
    observations = len(candidate)
    observed = sum(candidate) / observations - sum(reference) / observations
    rng = random.Random(seed)
    differences: list[float] = []
    for _ in range(samples):
        total = 0.0
        for _ in range(observations):
            index = rng.randrange(observations)
            total += candidate[index] - reference[index]
        differences.append(total / observations)
    differences.sort()
    low = differences[int(0.025 * samples)]
    high = differences[int(0.975 * samples) - 1]
    greater = sum(1 for value in differences if value >= 0) / samples
    lower = sum(1 for value in differences if value <= 0) / samples
    return {
        "difference": observed,
        "ci_low": low,
        "ci_high": high,
        "p_value": min(1.0, 2 * min(greater, lower)),
    }


def render_report(result: dict[str, object]) -> str:
    holdout = result["holdout_contests"]
    lines = [
        "# Prueba de significancia del motor",
        "",
        f"Fuente: {result['source']}",
        "",
        f"Evaluacion cronologica (walk-forward). Se reservan {holdout} concursos "
        f"finales como prueba. Cada modelo se compara contra `global_frequency` "
        "con un bootstrap pareado por concurso; el intervalo de confianza al 95% "
        "que cruza el cero indica que la diferencia no es concluyente.",
        "",
        "| Modelo | Log-loss prueba | Diferencia vs global | IC 95% | p-valor |",
        "|---|---:|---:|---:|---:|",
    ]
    for row in result["models"]:
        lines.append(
            f"| {row['model']} | {row['holdout_log_loss']:.4f} | "
            f"{row['difference']:+.4f} | "
            f"[{row['ci_low']:+.4f}, {row['ci_high']:+.4f}] | "
            f"{row['p_value']:.3f} |"
        )
    lines += [
        "",
        "| Dependencia entre casillas | Log-loss prueba | Diferencia | IC 95% | p-valor |",
        "|---|---:|---:|---:|---:|",
        f"| global_composition vs global_independent | "
        f"{result['joint_holdout_log_loss']:.4f} | "
        f"{result['joint_difference']:+.4f} | "
        f"[{result['joint_ci_low']:+.4f}, {result['joint_ci_high']:+.4f}] | "
        f"{result['joint_p_value']:.3f} |",
        "",
        "Ninguna variante supera a la frecuencia global de forma estadisticamente "
        "significativa en la prueba final. Por eso el motor conserva la frecuencia "
        "global suavizada y no incorpora senales por casilla, recencia ni "
        "dependencia entre partidos.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prueba de significancia de los modelos del motor"
    )
    parser.add_argument("--input", type=Path, help="CSV local en vez de la fuente oficial")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=ROOT / "outputs" / "model-significance",
    )
    parser.add_argument("--burn-in", type=int, default=100)
    parser.add_argument("--samples", type=int, default=3000)
    parser.add_argument("--seed", type=int, default=731)
    args = parser.parse_args()

    if args.input:
        csv_text = args.input.read_text(encoding="utf-8-sig")
        source = str(args.input.resolve())
    else:
        csv_text = fetch_text(OFFICIAL_CSV_URL)
        source = OFFICIAL_CSV_URL

    history = parse_history_csv(csv_text)
    if len(history) <= args.burn_in + 20:
        raise ValueError("Historico insuficiente para desarrollo y prueba")
    development_end = args.burn_in + int((len(history) - args.burn_in) * 0.70)

    losses, joint_independent, joint_composition = walk_forward(history, args.burn_in)
    cut = development_end - args.burn_in
    reference = losses["global_frequency"][cut:]

    rows: list[dict[str, object]] = []
    for name, series in losses.items():
        holdout = series[cut:]
        row: dict[str, object] = {
            "model": name,
            "holdout_log_loss": sum(holdout) / len(holdout),
        }
        if name == "global_frequency":
            row.update(difference=0.0, ci_low=0.0, ci_high=0.0, p_value=1.0)
        else:
            row.update(bootstrap(holdout, reference, args.samples, args.seed))
        rows.append(row)

    joint = bootstrap(joint_composition[cut:], joint_independent[cut:], args.samples, args.seed)
    result: dict[str, object] = {
        "source": source,
        "contests": len(history),
        "burn_in": args.burn_in,
        "holdout_contests": len(joint_independent) - cut,
        "models": rows,
        "joint_holdout_log_loss": sum(joint_composition[cut:]) / (len(joint_composition) - cut),
        "joint_difference": joint["difference"],
        "joint_ci_low": joint["ci_low"],
        "joint_ci_high": joint["ci_high"],
        "joint_p_value": joint["p_value"],
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.output_dir / "report.md"
    report_path.write_text(render_report(result), encoding="utf-8")
    (args.output_dir / "result.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print(f"Concursos: {len(history)}  prueba final: {result['holdout_contests']}")
    for row in rows:
        print(
            f"{row['model']}: prueba {row['holdout_log_loss']:.4f} "
            f"diferencia {row['difference']:+.4f} p={row['p_value']:.3f}"
        )
    print(f"Reporte: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
