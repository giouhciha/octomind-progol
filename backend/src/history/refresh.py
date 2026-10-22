"""Actualiza el historial oficial (resultados L/E/V) en DynamoDB.

Se ejecuta por cron; descarga los CSV oficiales de los tres sorteos y los
guarda en la tabla History. No toca los nombres de partidos ni el estado de
publicación (eso vive en la tabla Contests y lo maneja el admin).
"""
from __future__ import annotations

import csv
import io
import os
import urllib.request

import boto3

history = boto3.resource("dynamodb").Table(os.environ["HISTORY_TABLE"])

SOURCES = {
    "MS": (
        "https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv",
        25,
        9,
    ),
    "WEEKEND": (
        "https://www.loterianacional.gob.mx/Home/Historicos?ARHP=UAByAG8AZwBvAGwA",
        10,
        14,
    ),
    "REVANCHA": (
        "https://www.loterianacional.gob.mx/Home/Historicos?ARHP="
        "UAByAG8AZwBvAGwALQBSAGUAdgBhAG4AYwBoAGEA",
        10,
        7,
    ),
}


def fetch(url: str) -> str:
    req = urllib.request.Request(
        url, headers={"User-Agent": "Octomind-Backend/1.0", "Accept": "text/csv"}
    )
    with urllib.request.urlopen(req, timeout=90) as response:
        return response.read().decode("utf-8-sig")


def parse(text: str, product: int, slots: int, draw_type: str) -> list[dict]:
    reader = csv.DictReader(io.StringIO(text))
    items = []
    for row in reader:
        try:
            if int(row["NPRODUCTO"].strip()) != product:
                continue
        except (KeyError, ValueError):
            continue
        number = int(row["CONCURSO"].strip())
        results = [row[f"R{i}"].strip().upper() for i in range(1, slots + 1)]
        items.append(
            {
                "drawType": draw_type,
                "contestNumber": number,
                "date": row.get("FECHA", "").strip(),
                "results": results,
            }
        )
    return items


def lambda_handler(event, context):
    stored = 0
    with history.batch_writer() as batch:
        for draw_type, (url, product, slots) in SOURCES.items():
            try:
                items = parse(fetch(url), product, slots, draw_type)
            except Exception as exc:  # noqa: BLE001
                print(f"ERROR {draw_type}: {exc}")
                continue
            for item in items:
                batch.put_item(Item=item)
                stored += 1
    return {"stored": stored}
