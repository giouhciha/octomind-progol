"""Cuánto aporta cada boleto: curva de reducción del paquete.

Reutiliza el motor de `run_package_backtest.py` (misma composición, cupos y
diversidad) para generar el paquete ordenado y mide el mejor acierto medio
cuando se juegan solo los primeros 1, 2, 3, 5, 8, 10, 15 o 20 boletos.
Responde a la pregunta de si conviene reducir el paquete de 20.

Los históricos se descargan a `app/build/qa-assets/` (ignorado por git) si no
existen; también los puede dejar ahí para `run_package_backtest.py`.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import run_package_backtest as backtest  # noqa: E402

SOURCES = {
    "MS": (
        "https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv",
        "MS.csv",
    ),
    "WEEKEND": (
        "https://www.loterianacional.gob.mx/Home/Historicos?ARHP=UAByAG8AZwBvAGwA",
        "WEEKEND.csv",
    ),
    "REVANCHA": (
        "https://www.loterianacional.gob.mx/Home/Historicos?ARHP="
        "UAByAG8AZwBvAGwALQBSAGUAdgBhAG4AYwBoAGEA",
        "REVANCHA.csv",
    ),
}
CHAMPION = backtest.Settings("prior_20_div_20", 20.0, 0.20)
DEFAULT_SIZES = (1, 2, 3, 5, 8, 10, 15, 20)


def ensure_history(cache_dir: Path, refresh: bool) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    for draw, (url, filename) in SOURCES.items():
        destination = cache_dir / filename
        if destination.exists() and not refresh:
            continue
        request = urllib.request.Request(
            url, headers={"User-Agent": "Octomind-Progol/0.1 package-size"}
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            destination.write_bytes(response.read())
        print(f"Descargado {draw}: {destination}")


def best_hits(tickets: list[str], results: tuple[str, ...], size: int) -> int:
    return max(
        sum(a == b for a, b in zip(tickets[index], results))
        for index in range(min(size, len(tickets)))
    )


def evaluate_sizes(
    history,
    slots: int,
    settings: backtest.Settings,
    sizes: tuple[int, ...],
    burn_in: int,
    step: int,
) -> dict[int, list[int]]:
    maximum = max(sizes)
    scores: dict[int, list[int]] = {size: [] for size in sizes}
    for index, target in enumerate(history):
        if index < burn_in or (index - burn_in) % step != 0:
            continue
        tickets = backtest.package(history[:index], slots, settings, maximum)
        for size in sizes:
            scores[size].append(best_hits(tickets, target.results, size))
    return scores


def analyze_draw(cache_dir: Path, draw: str, sizes: tuple[int, ...], step: int) -> dict[str, object]:
    product, slots, _ = backtest.DRAWS[draw]
    filename = SOURCES[draw][1]
    history = backtest.parse_history(cache_dir / filename, product, slots)
    scores = evaluate_sizes(history, slots, CHAMPION, sizes, burn_in=100, step=step)
    split = 100 + int((len(history) - 100) * 0.70)
    split_scores = max(1, (split - 100 + step - 1) // step)

    rows = []
    for size in sizes:
        values = scores[size]
        development, holdout = values[:split_scores], values[split_scores:]
        rows.append(
            {
                "size": size,
                "development": round(sum(development) / len(development), 4),
                "holdout": round(sum(holdout) / len(holdout), 4),
                "holdout_near": round(
                    sum(score >= slots - 1 for score in holdout) / len(holdout), 4
                ),
            }
        )
    return {"draw": draw, "slots": slots, "history": len(history), "rows": rows}


def markdown(result: dict[str, object]) -> str:
    sizes = result["sizes"]
    lines = [
        "# Reducción del paquete: aporte por boleto",
        "",
        "El paquete se genera ordenado (primero las composiciones más probables). "
        "Cada fila juega solo los primeros N boletos y reporta el mejor acierto medio. "
        "No usa datos futuros: el paquete se arma antes del concurso evaluado.",
        "",
        f"Se evaluó uno de cada {result['step']} concursos. Configuración: "
        f"`{CHAMPION.name}`.",
        "",
    ]
    for draw in result["draws"]:
        lines += [
            f"## {draw['draw']} · {draw['slots']} partidos",
            "",
            f"Histórico: {draw['history']} concursos.",
            "",
            "| Boletos | Mejor acierto (desarrollo) | Mejor acierto (prueba final) | "
            "Ganancia marginal vs anterior | Cerca del pleno en prueba |",
            "|---:|---:|---:|---:|---:|",
        ]
        previous = None
        for row in draw["rows"]:
            if previous is None:
                marginal = "—"
            else:
                marginal = f"{row['holdout'] - previous:+.3f}"
            lines.append(
                f"| {row['size']} | {row['development']:.2f} | {row['holdout']:.2f} | "
                f"{marginal} | {row['holdout_near'] * 100:.2f}% |"
            )
            previous = row["holdout"]
        lines.append("")
    lines.append(
        "La ganancia marginal muestra cuánto aporta cada recorte adicional. "
        "Si cae por debajo del costo de un boleto, jugar ese boleto extra deja de "
        "ser eficiente."
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Reducción del paquete por boletos")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / "app" / "build" / "qa-assets")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "outputs" / "package-size")
    parser.add_argument("--step", type=int, default=5)
    parser.add_argument("--sizes", default=",".join(str(size) for size in DEFAULT_SIZES))
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    if args.step < 1:
        raise ValueError("step debe ser positivo")
    sizes = tuple(sorted({int(value) for value in args.sizes.split(",") if value.strip()}))
    if not sizes or sizes[0] < 1:
        raise ValueError("sizes debe contener enteros positivos")

    ensure_history(args.cache_dir, args.refresh)
    result = {
        "step": args.step,
        "sizes": list(sizes),
        "draws": [analyze_draw(args.cache_dir, draw, sizes, args.step) for draw in backtest.DRAWS],
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.output_dir.joinpath("report.md").write_text(markdown(result), encoding="utf-8")
    args.output_dir.joinpath("result.json").write_text(
        json.dumps(result, indent=2), encoding="utf-8"
    )
    for draw in result["draws"]:
        print(f"{draw['draw']} ({draw['slots']}): " + ", ".join(
            f"{row['size']}->{row['holdout']:.2f}" for row in draw["rows"]
        ))
    print(f"Reporte: {args.output_dir / 'report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
