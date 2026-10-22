"""Registra automáticamente a un usuario al confirmar su alta en Cognito."""
from __future__ import annotations

import os
import time

import boto3

users = boto3.resource("dynamodb").Table(os.environ["USERS_TABLE"])


def lambda_handler(event, context):
    attributes = (event.get("request") or {}).get("userAttributes") or {}
    user_id = attributes.get("sub")
    if user_id:
        users.put_item(
            Item={
                "userId": user_id,
                "email": attributes.get("email", ""),
                "createdAt": int(time.time()),
            }
        )
    return event
