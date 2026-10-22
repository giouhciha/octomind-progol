# Contrato de API — Octomind Progol

## Autenticación

- Cognito User Pool (login por email, registro público).
- Todas las rutas requieren JWT en `Authorization: Bearer <token>`.
- Rutas `/admin/*` requieren pertenecer al grupo Cognito **`admins`**.

## Endpoints

### `GET /data/latest?drawType=WEEKEND`
Último concurso publicado (con sus partidos). `drawType` ∈ `MS | WEEKEND | REVANCHA`.

**200**
```json
{
  "drawType": "WEEKEND",
  "contestNumber": 2355,
  "date": "2026-10-11",
  "results": ["L","E","V", ...],
  "matches": [{"slot": 1, "local": "Tigres", "visitor": "Toluca", "dateTime": "2026-10-10T05:00"}],
  "status": "PUBLISHED"
}
```

### `GET /templates`
Plantillas personalizadas del usuario autenticado.

**200**
```json
{"templates": [{"templateId":"...", "drawType":"WEEKEND", "contestNumber":2355,
  "config": {...}, "combinations": ["LEV...", "...20"], "createdAt": 1234567890}]}
```

### `POST /templates`
Crea una plantilla personalizada (config + 20 combinaciones).

**Body** `{"drawType":"WEEKEND", "contestNumber":2355, "config":{...}, "combinations":[...20]}`

**201** `{"templateId":"..."}`

### `DELETE /templates/{templateId}`
**204**

### `GET /leaderboard`
Top de usuarios por aciertos.

**200**
```json
{"leaderboard": [{"userId":"...", "email":"...", "totalHits":123,
  "contestsPlayed":15, "averageHits":8.2}]}
```

### `POST /admin/contests` (admin)
Crea/edita un concurso (borrador).

**Body** `{"drawType":"WEEKEND", "contestNumber":2355, "date":"...", "results":[...], "matches":[...]}`

### `POST /admin/publish` (admin)
Publica un concurso y recalcula el ranking.

**Body** `{"drawType":"WEEKEND", "contestNumber":2355}`

### `GET /admin/users` (admin)
Lista de usuarios registrados.

## Errores

- `403` sin permisos de admin.
- `404` recurso no encontrado / no publicado.
- `500` error interno (con `{"error": "..."}`).
