#!/usr/bin/env python3
"""Upload the generated notification media set to S3 and attach it to rules.

Run this script on the production host. It reads S3 credentials from the
existing /docker/.env file, never prints them, uploads deterministic object
keys, and performs the database changes in one transaction.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import mimetypes
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


REQUIRED_S3_ENV = (
    "S3_ACCESS_KEY",
    "S3_SECRET_KEY",
    "S3_BUCKET",
    "S3_ENDPOINT",
    "S3_REGION",
    "S3_PUBLIC_BASE_URL",
)


def load_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def mysql(sql: str) -> str:
    command = [
        "docker",
        "exec",
        "-i",
        "my-mysql",
        "sh",
        "-lc",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" -N -B',
    ]
    result = subprocess.run(
        command,
        input=sql,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("MySQL command failed: " + result.stderr.strip())
    return result.stdout.strip()


def sign(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def signing_key(secret_key: str, date_stamp: str, region: str) -> bytes:
    date_key = sign(("AWS4" + secret_key).encode("utf-8"), date_stamp)
    region_key = sign(date_key, region)
    service_key = sign(region_key, "s3")
    return sign(service_key, "aws4_request")


def upload_s3(data: bytes, key: str, content_type: str, env: dict[str, str]) -> None:
    endpoint = urllib.parse.urlsplit(env["S3_ENDPOINT"].rstrip("/"))
    object_path = "/".join(
        part.strip("/")
        for part in (endpoint.path, env["S3_BUCKET"], key)
        if part.strip("/")
    )
    canonical_uri = "/" + urllib.parse.quote(object_path, safe="/-_.~")
    url = urllib.parse.urlunsplit(
        (endpoint.scheme, endpoint.netloc, canonical_uri, "", "")
    )

    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(data).hexdigest()
    canonical_headers = (
        f"content-type:{content_type}\n"
        f"host:{endpoint.netloc}\n"
        "x-amz-acl:public-read\n"
        f"x-amz-content-sha256:{payload_hash}\n"
        f"x-amz-date:{amz_date}\n"
    )
    signed_headers = "content-type;host;x-amz-acl;x-amz-content-sha256;x-amz-date"
    canonical_request = "\n".join(
        ("PUT", canonical_uri, "", canonical_headers, signed_headers, payload_hash)
    )
    credential_scope = (
        f"{date_stamp}/{env['S3_REGION']}/s3/aws4_request"
    )
    string_to_sign = "\n".join(
        (
            "AWS4-HMAC-SHA256",
            amz_date,
            credential_scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        )
    )
    signature = hmac.new(
        signing_key(env["S3_SECRET_KEY"], date_stamp, env["S3_REGION"]),
        string_to_sign.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    authorization = (
        "AWS4-HMAC-SHA256 "
        f"Credential={env['S3_ACCESS_KEY']}/{credential_scope}, "
        f"SignedHeaders={signed_headers}, Signature={signature}"
    )
    request = urllib.request.Request(
        url,
        data=data,
        method="PUT",
        headers={
            "Authorization": authorization,
            "Content-Type": content_type,
            "Host": endpoint.netloc,
            "x-amz-acl": "public-read",
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": amz_date,
        },
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        if response.status not in (200, 201, 204):
            raise RuntimeError(f"S3 returned HTTP {response.status} for {key}")


def upload_with_retry(
    data: bytes, key: str, content_type: str, env: dict[str, str]
) -> None:
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            upload_s3(data, key, content_type, env)
            return
        except Exception as error:  # noqa: BLE001 - retry boundary
            last_error = error
            if attempt < 3:
                time.sleep(attempt * 2)
    raise RuntimeError(f"S3 upload failed for {key}: {last_error}")


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def public_url(base_url: str, key: str) -> str:
    return base_url.rstrip("/") + "/" + urllib.parse.quote(key, safe="/-_.~")


def validate_manifest(root: Path, manifest: dict) -> list[dict[str, str]]:
    themes = manifest.get("themes")
    file_names = manifest.get("file_names")
    if not isinstance(themes, list) or len(themes) != 15:
        raise ValueError("Manifest must contain exactly 15 themes")
    if not isinstance(file_names, list) or len(file_names) != 10:
        raise ValueError("Manifest must contain exactly 10 file names")

    rows: list[dict[str, str]] = []
    for theme in themes:
        directory = str(theme["directory"])
        event_code = str(theme["event_code"])
        recipient_type = str(theme["recipient_type"])
        for file_name in file_names:
            image = root / directory / str(file_name)
            if not image.is_file() or image.stat().st_size == 0:
                raise FileNotFoundError(image)
            rows.append(
                {
                    "directory": directory,
                    "event_code": event_code,
                    "recipient_type": recipient_type,
                    "file_name": str(file_name),
                    "path": str(image),
                }
            )
    if len(rows) != 150:
        raise ValueError(f"Expected 150 images, got {len(rows)}")
    return rows


def ensure_rules_exist(manifest: dict) -> None:
    expected = {
        (str(theme["event_code"]), str(theme["recipient_type"]))
        for theme in manifest["themes"]
    }
    statements = ["START TRANSACTION;"]
    for event_code, recipient_type in sorted(expected):
        statements.append(
            "INSERT INTO notification_media_rules "
            "(event_code,recipient_type,enabled,image_probability_percent,cooldown_minutes,created_at,updated_at) "
            f"SELECT {sql_quote(event_code)},{sql_quote(recipient_type)},b'1',100,360,"
            "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) "
            "WHERE NOT EXISTS (SELECT 1 FROM notification_media_rules "
            f"WHERE event_code={sql_quote(event_code)} AND recipient_type={sql_quote(recipient_type)});"
        )
    statements.append("COMMIT;")
    mysql("\n".join(statements) + "\n")
    output = mysql(
        "SELECT event_code, recipient_type FROM notification_media_rules "
        "WHERE enabled=b'1';\n"
    )
    actual = {
        tuple(line.split("\t", 1))
        for line in output.splitlines()
        if "\t" in line
    }
    missing = sorted(expected - actual)
    if missing:
        raise RuntimeError(f"Missing enabled notification rules: {missing}")


def attach_assets(rows: list[dict[str, str]], env: dict[str, str], prefix: str) -> None:
    statements = ["START TRANSACTION;"]
    for row in rows:
        key = f"notification-media/{row['event_code'].lower()}/{prefix}/{row['file_name']}"
        url = public_url(env["S3_PUBLIC_BASE_URL"], key)
        sequence = int(Path(row["file_name"]).stem)
        prefix_like = f"notification-media/{row['event_code'].lower()}/{prefix}/%"
        statements.append(
            "INSERT INTO notification_media_assets "
            "(rule_id,storage_key,image_url,original_filename,content_type,active,sort_order,created_at,updated_at) "
            "SELECT r.rule_id,{key},{url},{name},'image/png',b'1',"
            "COALESCE((SELECT MAX(existing.sort_order) FROM notification_media_assets existing "
            "WHERE existing.rule_id=r.rule_id AND existing.storage_key NOT LIKE {prefix_like}),-1)+{sequence},"
            "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) "
            "FROM notification_media_rules r "
            "WHERE r.event_code={event} AND r.recipient_type={recipient} "
            "ON DUPLICATE KEY UPDATE "
            "rule_id=VALUES(rule_id),image_url=VALUES(image_url),original_filename=VALUES(original_filename),"
            "content_type=VALUES(content_type),active=b'1',sort_order=VALUES(sort_order),updated_at=CURRENT_TIMESTAMP(6);".format(
                key=sql_quote(key),
                url=sql_quote(url),
                name=sql_quote(row["file_name"]),
                prefix_like=sql_quote(prefix_like),
                sequence=sequence,
                event=sql_quote(row["event_code"]),
                recipient=sql_quote(row["recipient_type"]),
            )
        )
    statements.append("COMMIT;")
    mysql("\n".join(statements) + "\n")


def verify_database(manifest: dict, prefix: str) -> None:
    for theme in manifest["themes"]:
        event_code = str(theme["event_code"])
        key_prefix = f"notification-media/{event_code.lower()}/{prefix}/%"
        count = mysql(
            "SELECT COUNT(*) FROM notification_media_assets a "
            "JOIN notification_media_rules r ON r.rule_id=a.rule_id "
            f"WHERE r.event_code={sql_quote(event_code)} AND a.active=b'1' "
            f"AND a.storage_key LIKE {sql_quote(key_prefix)};\n"
        )
        if count != "10":
            raise RuntimeError(f"{event_code}: expected 10 imported assets, got {count}")
        print(f"verified database: {event_code}=10", flush=True)


def verify_public_samples(manifest: dict, env: dict[str, str], prefix: str) -> None:
    for theme in manifest["themes"]:
        event_code = str(theme["event_code"])
        key = f"notification-media/{event_code.lower()}/{prefix}/01.png"
        request = urllib.request.Request(
            public_url(env["S3_PUBLIC_BASE_URL"], key), method="HEAD"
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise RuntimeError(f"Public URL returned {response.status}: {key}")
        print(f"verified public sample: {event_code}", flush=True)


def print_status() -> None:
    migration = mysql(
        "SELECT success FROM flyway_schema_history "
        "WHERE version='1.10.162' ORDER BY installed_rank DESC LIMIT 1;\n"
    )
    print(f"flyway:1.10.162={migration or 'MISSING'}", flush=True)
    output = mysql(
        "SELECT r.event_code, r.recipient_type, r.enabled, COUNT(a.asset_id) "
        "FROM notification_media_rules r "
        "LEFT JOIN notification_media_assets a ON a.rule_id=r.rule_id AND a.active=b'1' "
        "GROUP BY r.rule_id, r.event_code, r.recipient_type, r.enabled "
        "ORDER BY r.event_code;\n"
    )
    print(output, flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--env-file", type=Path, default=Path("/docker/.env"))
    parser.add_argument("--prefix", default="generated-20260731-v2")
    parser.add_argument("--status-only", action="store_true")
    args = parser.parse_args()

    if args.status_only:
        print_status()
        return 0
    if args.root is None or args.manifest is None:
        parser.error("--root and --manifest are required unless --status-only is used")

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    rows = validate_manifest(args.root, manifest)
    env = load_dotenv(args.env_file)
    missing_env = [key for key in REQUIRED_S3_ENV if not env.get(key)]
    if missing_env:
        raise RuntimeError(f"Missing S3 environment keys: {missing_env}")

    ensure_rules_exist(manifest)
    print("preflight passed: 15 rules, 150 images", flush=True)

    for index, row in enumerate(rows, start=1):
        key = (
            f"notification-media/{row['event_code'].lower()}/"
            f"{args.prefix}/{row['file_name']}"
        )
        data = Path(row["path"]).read_bytes()
        content_type = mimetypes.guess_type(row["file_name"])[0] or "image/png"
        upload_with_retry(data, key, content_type, env)
        print(f"uploaded {index}/150: {row['event_code']}/{row['file_name']}", flush=True)

    attach_assets(rows, env, args.prefix)
    verify_database(manifest, args.prefix)
    verify_public_samples(manifest, env, args.prefix)
    print("IMPORT_COMPLETE: 150 assets attached", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
        print(f"IMPORT_FAILED: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
