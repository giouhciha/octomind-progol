from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from progol_media_semana import OFFICIAL_CSV_URL, parse_history_csv, run_experiment


def fetch_text(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Octomind-Progol/0.1 historical-analysis"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        content_type = response.headers.get_content_charset() or "utf-8-sig"
        return response.read().decode(content_type)


def percentage(value: float, decimals: int = 2) -> str:
    return f"{value * 100:.{decimals}f}%"


def render_report(result: dict[str, object], source: str) -> str:
    audit = result["audit"]
    development = result["development_metrics"]
    holdout = result["holdout_metrics"]
    joint_development = result["joint_development_metrics"]
    joint_holdout = result["joint_holdout_metrics"]
    champion = result["champion"]
    champion_holdout = next(metric for metric in holdout if metric["model"] == champion)
    global_holdout = next(
        metric for metric in holdout if metric["model"] == "global_frequency"
    )

    lines = [
        "# Experimento Progol Media Semana",
        "",
        f"Fuente: {source}",
        "",
        "## Auditoria del historico",
        "",
        f"- Concursos: {audit['contests']}",
        f"- Periodo: {audit['first_date']} a {audit['last_date']}",
        f"- Concursos faltantes en la numeracion: {', '.join(map(str, audit['missing_contests'])) or 'ninguno'}",
        f"- Concursos duplicados: {', '.join(map(str, audit['duplicate_contests'])) or 'ninguno'}",
        "- Estados: " + ", ".join(
            f"{state}={count}" for state, count in audit["state_counts"].items()
        ),
        "",
        "## Metodo de evaluacion",
        "",
        f"Los primeros {result['burn_in']} concursos forman el historial minimo. "
        "Cada prediccion posterior usa solo concursos ya cerrados. El modelo se "
        f"elige en desarrollo hasta el concurso {result['development_end_contest']} "
        "y se informa su rendimiento en el tramo final reservado.",
        "",
        "## Comparacion en desarrollo",
        "",
        "| Modelo | Log-loss | Brier | Acierto principal | Cobertura top 2 |",
        "|---|---:|---:|---:|---:|",
    ]
    for metric in development:
        lines.append(
            f"| {metric['model']} | {metric['log_loss']:.4f} | "
            f"{metric['brier']:.4f} | {percentage(metric['accuracy'])} | "
            f"{percentage(metric['top2_coverage'])} |"
        )

    lines.extend(
        [
            "",
            "## Prueba final reservada",
            "",
            "| Modelo | Log-loss | Brier | Acierto principal | Cobertura top 2 |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for metric in holdout:
        lines.append(
            f"| {metric['model']} | {metric['log_loss']:.4f} | "
            f"{metric['brier']:.4f} | {percentage(metric['accuracy'])} | "
            f"{percentage(metric['top2_coverage'])} |"
        )

    delta = champion_holdout["log_loss"] - global_holdout["log_loss"]
    comparison = (
        "mejora" if delta < 0 else "empeora" if delta > 0 else "iguala"
    )
    lines.extend(
        [
            "",
            "## Modelo seleccionado",
            "",
            f"El modelo elegido en desarrollo es `{champion}`. En la prueba final "
            f"{comparison} el log-loss de la frecuencia global por {abs(delta):.4f}.",
            "",
            "## Evaluacion de combinaciones completas",
            "",
            "| Tramo | Modelo | Log-loss por quiniela | Equivalente por casilla |",
            "|---|---|---:|---:|",
        ]
    )
    for label, metrics in (
        ("Desarrollo", joint_development),
        ("Prueba final", joint_holdout),
    ):
        for metric in metrics:
            lines.append(
                f"| {label} | {metric['model']} | "
                f"{metric['card_log_loss']:.4f} | "
                f"{metric['slot_equivalent_log_loss']:.4f} |"
            )

    joint_holdout_by_name = {metric["model"]: metric for metric in joint_holdout}
    composition_delta = (
        joint_holdout_by_name["global_composition"]["card_log_loss"]
        - joint_holdout_by_name["global_independent"]["card_log_loss"]
    )
    lines.extend(
        [
            "",
            "La composicion historica no mejoro la probabilidad predictiva conjunta "
            f"en la prueba final; aumento el log-loss por quiniela en {composition_delta:.4f}. "
            "Por eso se usa para repartir un paquete de combinaciones plausibles, "
            "no como una supuesta certeza adicional.",
            "",
            f"## Pronostico experimental del concurso {result['next_contest']}",
            "",
            "| Casilla | L | E | V | Principal | Segunda | Incertidumbre |",
            "|---:|---:|---:|---:|:---:|:---:|---:|",
        ]
    )
    for row in result["forecast"]:
        probabilities = row["probabilities"]
        lines.append(
            f"| {row['slot']} | {percentage(probabilities['L'])} | "
            f"{percentage(probabilities['E'])} | {percentage(probabilities['V'])} | "
            f"{row['prediction']} | {row['second_choice']} | "
            f"{percentage(row['entropy'])} |"
        )

    lines.extend(
        [
            "",
            "## Paquete de combinaciones recomendado",
            "",
            "El universo completo contiene 19,683 quinielas sencillas. La seleccion "
            "asigna cupos segun la composicion historica y despues maximiza "
            "probabilidad base y diversidad.",
            "Cuando varias secuencias tienen el mismo peso, su orden se decide por "
            "diversidad; no se interpreta como una ventaja predictiva adicional.",
            "",
            "| Orden | Combinacion | L | E | V | Peso composicion | Prob. base exacta | Distancia |",
            "|---:|:---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for row in result["recommended_combinations"]:
        lines.append(
            f"| {row['rank']} | {row['sequence']} | {row['local']} | "
            f"{row['draw']} | {row['away']} | "
            f"{percentage(row['composition_probability'])} | "
            f"{percentage(row['probability'], 4)} | {row['minimum_distance']} |"
        )

    lines.extend(
        [
            "",
            "Este pronostico es una estimacion experimental basada solo en la "
            "secuencia historica L/E/V. No incorpora los equipos del siguiente "
            "concurso y no implica certeza ni garantia de premio.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Backtest cronologico de Progol Media Semana"
    )
    parser.add_argument("--input", type=Path, help="CSV local en vez de la fuente oficial")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=ROOT / "outputs" / "media_semana",
    )
    args = parser.parse_args()

    if args.input:
        csv_text = args.input.read_text(encoding="utf-8-sig")
        source = str(args.input.resolve())
    else:
        csv_text = fetch_text(OFFICIAL_CSV_URL)
        source = OFFICIAL_CSV_URL

    history = parse_history_csv(csv_text)
    experiment = run_experiment(history)
    result = experiment.to_dict()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.output_dir / "experiment.json"
    report_path = args.output_dir / "report.md"
    json_path.write_text(
        json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    report_path.write_text(render_report(result, source), encoding="utf-8")

    print(f"Historico validado: {result['audit']['contests']} concursos")
    print(f"Modelo seleccionado: {result['champion']}")
    print(f"Siguiente concurso estimado: {result['next_contest']}")
    print(f"Reporte: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
