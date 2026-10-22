"""API de Octomind Progol (Lambda única con ruteo).

Autenticación: API Gateway valida el JWT de Cognito; aquí se lee `sub` (userId)
y `cognito:groups` (para rutas de admin).
"""
from __future__ import annotations

import json
import os
import time
import uuid
from decimal import Decimal

import boto3

dynamodb = boto3.resource("dynamodb")
contests = dynamodb.Table(os.environ["CONTESTS_TABLE"])
templates = dynamodb.Table(os.environ["TEMPLATES_TABLE"])
users = dynamodb.Table(os.environ["USERS_TABLE"])
leaderboard = dynamodb.Table(os.environ["LEADERBOARD_TABLE"])
history = dynamodb.Table(os.environ["HISTORY_TABLE"])

STATES = ("L", "E", "V")
LEADERBOARD_PK = "LEADERBOARD"


def _json_default(value):
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    return str(value)


def resp(status: int, body: dict) -> dict:
    return {
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "Content-Type,Authorization",
            "Access-Control-Allow-Methods": "OPTIONS,GET,POST,DELETE",
        },
        "body": json.dumps(body, default=_json_default),
    }


def claims(event: dict) -> tuple[str, bool, str]:
    c = event.get("requestContext", {}).get("authorizer", {}).get("claims", {})
    groups = c.get("cognito:groups", "")
    return c.get("sub", ""), "admins" in groups.split(","), c.get("email", "")


def lambda_handler(event, context):
    method = event.get("httpMethod", "")
    if method == "OPTIONS":
        return {
            "statusCode": 200,
            "headers": {
                "Access-Control-Allow-Origin": "*",
                "Access-Control-Allow-Headers": "Content-Type,Authorization,X-Amz-Date,X-Api-Key,X-Amz-Security-Token",
                "Access-Control-Allow-Methods": "OPTIONS,GET,POST,DELETE",
            },
            "body": "",
        }
    path = event.get("path", "")
    user_id, admin, email = claims(event)

    try:
        if path == "/data/latest" and method == "GET":
            return get_data_latest(event)
        if path == "/history" and method == "GET":
            return get_history(event)
        if path == "/templates" and method == "GET":
            return list_templates(user_id)
        if path == "/templates" and method == "POST":
            return create_template(user_id, email, event)
        if path.startswith("/templates/") and method == "DELETE":
            return delete_template(user_id, path.rsplit("/", 1)[-1])
        if path == "/leaderboard" and method == "GET":
            return get_leaderboard()
        if path == "/admin/contests" and method == "POST":
            return admin_contests(admin, event)
        if path == "/admin/contests" and method == "GET":
            return admin_list_contests(admin)
        if path == "/admin/contests" and method == "DELETE":
            return admin_delete_contest(admin, event)
        if path == "/admin/publish" and method == "POST":
            return admin_publish(admin, event)
        if path == "/admin/users" and method == "GET":
            return admin_users(admin)
        if path == "/admin/history" and method == "GET":
            return admin_history(admin, event)
        if path == "/admin/templates" and method == "GET":
            return admin_templates(admin, event)
        if path == "/admin/refresh-history" and method == "POST":
            return admin_refresh_history(admin)
        return resp(404, {"error": "No encontrado"})
    except PermissionError:
        return resp(403, {"error": "Sin permisos de administrador"})
    except Exception as exc:  # noqa: BLE001
        return resp(500, {"error": str(exc)})


# ── Datos publicados ─────────────────────────────────────────
def get_data_latest(event: dict) -> dict:
    params = event.get("queryStringParameters") or {}
    draw_type = params.get("drawType", "WEEKEND")
    contest_number = params.get("contestNumber")
    if contest_number:
        item = contests.get_item(
            Key={"drawType": draw_type, "contestNumber": int(contest_number)}
        ).get("Item")
    else:
        result = contests.query(
            KeyConditionExpression=boto3.dynamodb.conditions.Key("drawType").eq(draw_type),
            ScanIndexForward=False,
            Limit=1,
        )
        items = result.get("Items", [])
        item = items[0] if items else None
    if not item or item.get("status") != "PUBLISHED":
        return resp(404, {"error": "Aún no publicado"})
    if _is_expired(item):
        return resp(404, {"error": "Concurso finalizado"})
    return resp(200, item)


def _is_expired(item: dict) -> bool:
    end_date = item.get("endDate")
    if not end_date:
        return False
    try:
        return time.time() > int(end_date)
    except (TypeError, ValueError):
        return False


# ── Historial ────────────────────────────────────────────────
def get_history(event: dict) -> dict:
    draw_type = (event.get("queryStringParameters") or {}).get("drawType", "WEEKEND")
    result = history.query(
        KeyConditionExpression=boto3.dynamodb.conditions.Key("drawType").eq(draw_type),
        ScanIndexForward=True,
    )
    items = result.get("Items", [])
    items.sort(key=lambda c: int(c.get("contestNumber", 0)))
    return resp(200, {"drawType": draw_type, "history": items})


# ── Plantillas personalizadas ────────────────────────────────
def list_templates(user_id: str) -> dict:
    result = templates.query(
        KeyConditionExpression=boto3.dynamodb.conditions.Key("userId").eq(user_id),
        ScanIndexForward=False,
    )
    return resp(200, {"templates": result.get("Items", [])})


def create_template(user_id: str, email: str, event: dict) -> dict:
    body = json.loads(event.get("body") or "{}")
    draw_type = body.get("drawType")
    contest_number = body.get("contestNumber")
    config = body.get("config") or {}
    combinations = body.get("combinations") or []

    if not draw_type or not contest_number:
        return resp(400, {"error": "Faltan drawType o contestNumber"})
    if not isinstance(combinations, list) or not combinations:
        return resp(400, {"error": "Faltan las combinaciones"})

    template_id = str(uuid.uuid4())
    item = {
        "userId": user_id,
        "templateId": template_id,
        "contestKey": f"{draw_type}#{contest_number}",
        "drawType": draw_type,
        "contestNumber": int(contest_number),
        "email": email,
        "config": config,
        "combinations": combinations,
        "createdAt": int(time.time()),
    }
    templates.put_item(Item=item)
    return resp(201, {"templateId": template_id})


def delete_template(user_id: str, template_id: str) -> dict:
    templates.delete_item(Key={"userId": user_id, "templateId": template_id})
    return resp(204, {})


# ── Ranking ──────────────────────────────────────────────────
def get_leaderboard() -> dict:
    result = leaderboard.query(
        KeyConditionExpression=boto3.dynamodb.conditions.Key("pk").eq(LEADERBOARD_PK)
    )
    rows = []
    for item in result.get("Items", []):
        played = int(item.get("contestsPlayed", 0))
        total = int(item.get("totalHits", 0))
        rows.append(
            {
                "userId": item.get("userId"),
                "email": item.get("email", ""),
                "totalHits": total,
                "contestsPlayed": played,
                "averageHits": round(total / played, 2) if played else 0,
            }
        )
    rows.sort(key=lambda r: (-r["totalHits"], -r["averageHits"]))
    return resp(200, {"leaderboard": rows})


# ── Admin ────────────────────────────────────────────────────
def _require_admin(admin: bool) -> None:
    if not admin:
        raise PermissionError


def admin_contests(admin: bool, event: dict) -> dict:
    _require_admin(admin)
    body = json.loads(event.get("body") or "{}")
    draw_type = body.get("drawType")
    contest_number = body.get("contestNumber")
    if not draw_type or not contest_number:
        return resp(400, {"error": "Faltan drawType o contestNumber"})
    start_date = body.get("startDate")
    end_date = body.get("endDate")
    item = {
        "drawType": draw_type,
        "contestNumber": int(contest_number),
        "date": body.get("date", ""),
        "startDate": int(start_date) if start_date else 0,
        "endDate": int(end_date) if end_date else 0,
        "results": body.get("results") or [],
        "matches": body.get("matches") or [],
        "status": "DRAFT",
        "updatedAt": int(time.time()),
    }
    contests.put_item(Item=item)
    return resp(200, {"ok": True})


def admin_list_contests(admin: bool) -> dict:
    _require_admin(admin)
    items = contests.scan().get("Items", [])
    items.sort(key=lambda c: (c.get("drawType", ""), -int(c.get("contestNumber", 0))))
    return resp(200, {"contests": items})


def admin_delete_contest(admin: bool, event: dict) -> dict:
    _require_admin(admin)
    params = event.get("queryStringParameters") or {}
    draw_type = params.get("drawType")
    contest_number = params.get("contestNumber")
    if not draw_type or not contest_number:
        return resp(400, {"error": "Faltan drawType o contestNumber"})
    contests.delete_item(Key={"drawType": draw_type, "contestNumber": int(contest_number)})
    return resp(204, {})


def _best_hits(combinations: list, results: list) -> int:
    best = 0
    for combo in combinations:
        hits = sum(1 for a, b in zip(combo, results) if a == b)
        best = max(best, hits)
    return best


def admin_publish(admin: bool, event: dict) -> dict:
    _require_admin(admin)
    body = json.loads(event.get("body") or "{}")
    draw_type = body.get("drawType")
    contest_number = body.get("contestNumber")
    if not draw_type or not contest_number:
        return resp(400, {"error": "Faltan drawType o contestNumber"})

    result = contests.get_item(
        Key={"drawType": draw_type, "contestNumber": int(contest_number)}
    )
    if "Item" not in result:
        return resp(404, {"error": "Concurso no existe"})
    item = result["Item"]
    item["status"] = "PUBLISHED"
    item["publishedAt"] = int(time.time())
    contests.put_item(Item=item)

    results = item.get("results") or []
    contest_key = f"{draw_type}#{contest_number}"

    # Actualiza el ranking a partir de las plantillas personalizadas.
    if results:
        tpl_result = templates.query(
            IndexName="contest-index",
            KeyConditionExpression=boto3.dynamodb.conditions.Key("contestKey").eq(contest_key),
        )
        for tpl in tpl_result.get("Items", []):
            user_id = tpl["userId"]
            hits = _best_hits(tpl.get("combinations") or [], results)
            current = leaderboard.get_item(
                Key={"pk": LEADERBOARD_PK, "userId": user_id}
            ).get("Item", {})
            updated = {
                "pk": LEADERBOARD_PK,
                "userId": user_id,
                "email": tpl.get("email", current.get("email", "")),
                "totalHits": int(current.get("totalHits", 0)) + hits,
                "contestsPlayed": int(current.get("contestsPlayed", 0)) + 1,
                "updatedAt": int(time.time()),
            }
            leaderboard.put_item(Item=updated)

    # TODO(fase 4): disparar notificación push (SNS/FCM).
    return resp(200, {"ok": True, "published": True})


def admin_users(admin: bool) -> dict:
    _require_admin(admin)
    client = boto3.client("cognito-idp")
    pool_id = os.environ["USER_POOL_ID"]
    rows = []
    paginator = client.get_paginator("list_users")
    for page in paginator.paginate(UserPoolId=pool_id):
        for user in page.get("Users", []):
            attributes = {a["Name"]: a.get("Value", "") for a in user.get("Attributes", [])}
            groups = client.admin_list_groups_for_user(
                UserPoolId=pool_id, Username=user["Username"]
            ).get("Groups", [])
            created = user.get("UserCreateDate")
            rows.append({
                "userId": attributes.get("sub", user.get("Username", "")),
                "email": attributes.get("email", user.get("Username", "")),
                "status": user.get("UserStatus", ""),
                "groups": [g.get("GroupName") for g in groups],
                "createdAt": created.isoformat() if created else None,
            })
    rows.sort(key=lambda u: u.get("email", "").lower())
    return resp(200, {"users": rows})


def admin_history(admin: bool, event: dict) -> dict:
    _require_admin(admin)
    params = event.get("queryStringParameters") or {}
    draw_type = params.get("drawType", "WEEKEND")
    contest_number = params.get("contestNumber")
    if contest_number:
        item = history.get_item(
            Key={"drawType": draw_type, "contestNumber": int(contest_number)}
        ).get("Item")
        if not item:
            return resp(404, {"error": "Concurso no encontrado en el histórico"})
        return resp(200, {"history": [item]})
    limit = min(int(params.get("limit", 10)), 50)
    result = history.query(
        KeyConditionExpression=boto3.dynamodb.conditions.Key("drawType").eq(draw_type),
        ScanIndexForward=False,
        Limit=limit,
    )
    items = result.get("Items", [])
    return resp(200, {"history": items})


def admin_refresh_history(admin: bool) -> dict:
    _require_admin(admin)
    boto3.client("lambda").invoke(
        FunctionName="OctomindRefreshHistory",
        InvocationType="Event",
    )
    return resp(200, {"ok": True, "message": "Actualización de histórico iniciada"})


def admin_templates(admin: bool, event: dict) -> dict:
    _require_admin(admin)
    params = event.get("queryStringParameters") or {}
    user_id = params.get("userId")
    if not user_id:
        return resp(400, {"error": "Falta userId"})
    result = templates.query(
        KeyConditionExpression=boto3.dynamodb.conditions.Key("userId").eq(user_id),
        ScanIndexForward=False,
    )
    return resp(200, {"templates": result.get("Items", [])})
