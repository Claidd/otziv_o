#!/usr/bin/env python3
"""Upload the generated notification media set to S3 and attach it to rules.

Run this script on the production host. It reads S3 credentials from the
existing /docker/.env file, never prints them, uploads deterministic object
keys, and performs all database changes in one transaction with the
application database account.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import mimetypes
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path


REQUIRED_S3_ENV = (
    "S3_ACCESS_KEY",
    "S3_SECRET_KEY",
    "S3_BUCKET",
    "S3_ENDPOINT",
    "S3_REGION",
    "S3_PUBLIC_BASE_URL",
)


@dataclass(frozen=True)
class S3ObjectInfo:
    key: str
    sha256: str | None
    import_id: str | None
    etag: str | None
    size: int | None


class S3UploadError(RuntimeError):
    """An upload failed after an object may have been created by this run."""

    def __init__(
        self, message: str, newly_uploaded: S3ObjectInfo | None = None
    ) -> None:
        super().__init__(message)
        self.newly_uploaded = newly_uploaded


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
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B',
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


def s3_location(
    key: str, env: dict[str, str]
) -> tuple[urllib.parse.SplitResult, str, str]:
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
    return endpoint, canonical_uri, url


def signed_s3_request(
    method: str,
    key: str,
    env: dict[str, str],
    *,
    data: bytes | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 90,
) -> tuple[int, dict[str, str]]:
    endpoint, canonical_uri, url = s3_location(key, env)
    payload = data if data is not None else b""

    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(payload).hexdigest()
    request_headers = {
        name.lower(): " ".join(value.strip().split())
        for name, value in (headers or {}).items()
    }
    request_headers.update(
        {
            "host": endpoint.netloc,
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": amz_date,
        }
    )
    signed_header_names = sorted(request_headers)
    canonical_headers = "".join(
        f"{name}:{request_headers[name]}\n" for name in signed_header_names
    )
    signed_headers = ";".join(signed_header_names)
    canonical_request = "\n".join(
        (method, canonical_uri, "", canonical_headers, signed_headers, payload_hash)
    )
    credential_scope = f"{date_stamp}/{env['S3_REGION']}/s3/aws4_request"
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
    request_headers["authorization"] = authorization
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers=request_headers,
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, {
            name.lower(): value.strip() for name, value in response.headers.items()
        }


def response_sha256(headers: dict[str, str]) -> str | None:
    metadata_digest = headers.get("x-amz-meta-sha256", "").lower()
    if len(metadata_digest) == 64 and all(
        character in "0123456789abcdef" for character in metadata_digest
    ):
        return metadata_digest
    checksum = headers.get("x-amz-checksum-sha256")
    if checksum:
        try:
            decoded = base64.b64decode(checksum, validate=True)
        except ValueError:
            return None
        if len(decoded) == hashlib.sha256().digest_size:
            return decoded.hex()
    return None


def head_s3(key: str, env: dict[str, str]) -> S3ObjectInfo | None:
    try:
        status, headers = signed_s3_request("HEAD", key, env, timeout=30)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    if status != 200:
        raise RuntimeError(f"S3 returned HTTP {status} for HEAD {key}")
    content_length = headers.get("content-length")
    try:
        size = int(content_length) if content_length is not None else None
    except ValueError:
        size = None
    return S3ObjectInfo(
        key=key,
        sha256=response_sha256(headers),
        import_id=headers.get("x-amz-meta-import-id"),
        etag=headers.get("etag"),
        size=size,
    )


def object_matches(info: S3ObjectInfo, digest: str, size: int) -> bool:
    return hmac.compare_digest(info.sha256 or "", digest) and info.size == size


def assert_existing_object_matches(
    info: S3ObjectInfo, digest: str, size: int
) -> None:
    if not object_matches(info, digest, size):
        raise RuntimeError(
            f"Refusing to overwrite existing S3 object without matching "
            f"SHA-256 metadata and size: {info.key}"
        )


def upload_s3(
    data: bytes,
    key: str,
    content_type: str,
    env: dict[str, str],
    import_id: str,
) -> dict[str, str]:
    digest = hashlib.sha256(data).hexdigest()
    status, headers = signed_s3_request(
        "PUT",
        key,
        env,
        data=data,
        headers={
            "content-type": content_type,
            "if-none-match": "*",
            "x-amz-acl": "public-read",
            "x-amz-meta-import-id": import_id,
            "x-amz-meta-sha256": digest,
        },
    )
    if status not in (200, 201, 204):
        raise RuntimeError(f"S3 returned HTTP {status} for PUT {key}")
    return headers


def upload_with_retry(
    data: bytes,
    key: str,
    content_type: str,
    env: dict[str, str],
    import_id: str,
    on_created: Callable[[S3ObjectInfo], None] | None = None,
) -> S3ObjectInfo | None:
    digest = hashlib.sha256(data).hexdigest()
    created_reported = False

    def report_created(info: S3ObjectInfo) -> None:
        nonlocal created_reported
        if not created_reported and on_created is not None:
            on_created(info)
        created_reported = True

    existing = head_s3(key, env)
    if existing is not None:
        assert_existing_object_matches(existing, digest, len(data))
        return None

    last_error: Exception | None = None
    uploaded_hint: S3ObjectInfo | None = None
    for attempt in range(1, 4):
        try:
            response_headers = upload_s3(data, key, content_type, env, import_id)
            uploaded_hint = S3ObjectInfo(
                key=key,
                sha256=digest,
                import_id=import_id,
                etag=response_headers.get("etag"),
                size=len(data),
            )
            report_created(uploaded_hint)
            current = head_s3(key, env)
            if current is None:
                raise RuntimeError(f"Uploaded S3 object is not visible: {key}")
            assert_existing_object_matches(current, digest, len(data))
            if current.import_id != import_id:
                raise RuntimeError(
                    f"Uploaded S3 object has unexpected ownership metadata: {key}"
                )
            return current
        except urllib.error.HTTPError as error:
            last_error = error
            try:
                current = head_s3(key, env)
            except Exception:  # noqa: BLE001 - retry the original failed request
                current = None
            if current is not None:
                assert_existing_object_matches(current, digest, len(data))
                if current.import_id == import_id:
                    report_created(current)
                    return current
                return None
            if error.code == 412:
                raise RuntimeError(
                    f"S3 rejected conditional upload but object is absent: {key}"
                ) from error
            if error.code == 409 and attempt == 3:
                # AWS documents 409 as retryable for conditional PutObject.
                # On the final attempt there is no safe state to accept.
                raise S3UploadError(
                    f"S3 conditional upload conflict for {key}: {error}"
                ) from error
        except Exception as error:  # noqa: BLE001 - retry boundary
            last_error = error
            try:
                current = head_s3(key, env)
            except Exception:  # noqa: BLE001 - retain the original upload error
                current = None
            if current is not None and object_matches(current, digest, len(data)):
                if current.import_id == import_id:
                    report_created(current)
                    return current
                return None
        except BaseException:
            try:
                current = head_s3(key, env)
            except Exception:  # noqa: BLE001 - preserve interruption
                current = None
            if (
                current is not None
                and current.import_id == import_id
                and object_matches(current, digest, len(data))
            ):
                report_created(current)
            raise
        if isinstance(last_error, RuntimeError):
            # A verified ownership or digest conflict is not transient.
            if (
                "unexpected ownership" in str(last_error)
                or "Refusing to overwrite" in str(last_error)
            ):
                break
        if attempt < 3:
            time.sleep(attempt * 2)
    raise S3UploadError(
        f"S3 upload failed for {key}: {last_error}",
        newly_uploaded=uploaded_hint,
    )


def delete_s3_if_owned(info: S3ObjectInfo, env: dict[str, str]) -> None:
    current = head_s3(info.key, env)
    if current is None:
        return
    if (
        current.import_id != info.import_id
        or info.sha256 is None
        or info.size is None
        or not object_matches(current, info.sha256, info.size)
    ):
        raise RuntimeError(
            f"Refusing to delete S3 object whose ownership or digest changed: {info.key}"
        )
    if not current.etag:
        raise RuntimeError(
            f"Refusing to delete S3 object without an ETag precondition: {info.key}"
        )
    status, _ = signed_s3_request(
        "DELETE",
        info.key,
        env,
        headers={"if-match": current.etag},
        timeout=30,
    )
    if status not in (200, 204):
        raise RuntimeError(f"S3 returned HTTP {status} for DELETE {info.key}")


def cleanup_uploaded(objects: list[S3ObjectInfo], env: dict[str, str]) -> None:
    seen: set[str] = set()
    for info in reversed(objects):
        if info.key in seen:
            continue
        seen.add(info.key)
        try:
            delete_s3_if_owned(info, env)
            print(f"rolled back uploaded object: {info.key}", file=sys.stderr, flush=True)
        except Exception as error:  # noqa: BLE001 - best-effort rollback with warning
            print(
                f"WARNING: could not roll back uploaded object {info.key}: {error}",
                file=sys.stderr,
                flush=True,
            )


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def public_url(base_url: str, key: str) -> str:
    return base_url.rstrip("/") + "/" + urllib.parse.quote(key, safe="/-_.~")


def validate_manifest(root: Path, manifest: dict) -> list[dict[str, str]]:
    explicit_assets = manifest.get("assets")
    if isinstance(explicit_assets, list):
        expected_count = int(manifest.get("expected_unique_assets", len(explicit_assets)))
        if len(explicit_assets) != expected_count:
            raise ValueError(
                f"Manifest expected {expected_count} assets, got {len(explicit_assets)}"
            )
        rows: list[dict[str, str]] = []
        hashes: set[str] = set()
        storage_targets: set[tuple[str, str]] = set()
        for asset in explicit_assets:
            directory = str(asset["directory"])
            event_code = str(asset["event_code"])
            recipient_type = str(asset["recipient_type"])
            file_name = str(asset["file_name"])
            image = root / directory / file_name
            if not image.is_file() or image.stat().st_size == 0:
                raise FileNotFoundError(image)
            digest = hashlib.sha256(image.read_bytes()).hexdigest()
            expected_digest = str(asset.get("sha256", digest)).lower()
            if digest != expected_digest:
                raise ValueError(
                    f"SHA-256 mismatch for {image}: expected {expected_digest}, got {digest}"
                )
            target = (event_code, file_name)
            if digest in hashes:
                raise ValueError(f"Duplicate image content in manifest: {image}")
            if target in storage_targets:
                raise ValueError(f"Duplicate storage target in manifest: {target}")
            hashes.add(digest)
            storage_targets.add(target)
            rows.append(
                {
                    "directory": directory,
                    "event_code": event_code,
                    "recipient_type": recipient_type,
                    "file_name": file_name,
                    "original_filename": str(asset.get("original_filename", file_name)),
                    "content_type": str(asset.get("content_type", "image/jpeg")),
                    "sha256": digest,
                    "path": str(image),
                }
            )
        return rows

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
                    "original_filename": str(file_name),
                    "content_type": "image/png",
                    "path": str(image),
                }
            )
    if len(rows) != 150:
        raise ValueError(f"Expected 150 images, got {len(rows)}")
    return rows


def apply_database_changes(
    rows: list[dict[str, str]], env: dict[str, str], prefix: str
) -> None:
    expected = {
        (row["event_code"], row["recipient_type"])
        for row in rows
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
    for row in rows:
        key = f"notification-media/{row['event_code'].lower()}/{prefix}/{row['file_name']}"
        url = public_url(env["S3_PUBLIC_BASE_URL"], key)
        sequence_text = Path(row["file_name"]).stem.split("-", 1)[0]
        sequence = int(sequence_text)
        key_prefix = f"notification-media/{row['event_code'].lower()}/{prefix}/"
        statements.append(
            "INSERT INTO notification_media_assets "
            "(rule_id,storage_key,image_url,original_filename,content_type,active,sort_order,created_at,updated_at) "
            "SELECT r.rule_id,{key},{url},{name},{content_type},b'1',"
            "COALESCE((SELECT MAX(existing.sort_order) FROM notification_media_assets existing "
            "WHERE existing.rule_id=r.rule_id "
            "AND LEFT(existing.storage_key,CHAR_LENGTH({key_prefix}))<>{key_prefix}),-1)+{sequence},"
            "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) "
            "FROM notification_media_rules r "
            "WHERE r.event_code={event} AND r.recipient_type={recipient} "
            "ON DUPLICATE KEY UPDATE "
            "rule_id=VALUES(rule_id),image_url=VALUES(image_url),original_filename=VALUES(original_filename),"
            "content_type=VALUES(content_type),active=b'1',sort_order=VALUES(sort_order),updated_at=CURRENT_TIMESTAMP(6);".format(
                key=sql_quote(key),
                url=sql_quote(url),
                name=sql_quote(row["original_filename"]),
                content_type=sql_quote(row["content_type"]),
                key_prefix=sql_quote(key_prefix),
                sequence=sequence,
                event=sql_quote(row["event_code"]),
                recipient=sql_quote(row["recipient_type"]),
            )
        )
    statements.extend(
        (
            "CREATE TEMPORARY TABLE notification_import_assertions ("
            "assertion_name VARCHAR(255) NOT NULL,"
            "actual_count BIGINT NOT NULL,"
            "expected_count BIGINT NOT NULL,"
            "CONSTRAINT chk_notification_import_assertion "
            "CHECK (actual_count = expected_count)"
            ") ENGINE=InnoDB;",
        )
    )
    expected_rows: dict[tuple[str, str], list[dict[str, str]]] = {}
    for row in rows:
        pair = (row["event_code"], row["recipient_type"])
        expected_rows.setdefault(pair, []).append(row)
    for (event_code, recipient_type), pair_rows in sorted(expected_rows.items()):
        assertion_name = f"rule:{event_code}:{recipient_type}"
        statements.append(
            "INSERT INTO notification_import_assertions "
            "(assertion_name,actual_count,expected_count) "
            f"SELECT {sql_quote(assertion_name)},COUNT(*),1 "
            "FROM notification_media_rules "
            f"WHERE event_code={sql_quote(event_code)} "
            f"AND recipient_type={sql_quote(recipient_type)} AND enabled=b'1';"
        )
        key_prefix = f"notification-media/{event_code.lower()}/{prefix}/"
        count_assertion_name = f"asset-count:{event_code}:{recipient_type}"
        statements.append(
            "INSERT INTO notification_import_assertions "
            "(assertion_name,actual_count,expected_count) "
            f"SELECT {sql_quote(count_assertion_name)},COUNT(*),{len(pair_rows)} "
            "FROM notification_media_assets a "
            "JOIN notification_media_rules r ON r.rule_id=a.rule_id "
            f"WHERE r.event_code={sql_quote(event_code)} "
            f"AND r.recipient_type={sql_quote(recipient_type)} "
            "AND a.active=b'1' "
            f"AND LEFT(a.storage_key,CHAR_LENGTH({sql_quote(key_prefix)}))="
            f"{sql_quote(key_prefix)};"
        )
        for row in pair_rows:
            key = (
                f"notification-media/{event_code.lower()}/"
                f"{prefix}/{row['file_name']}"
            )
            url = public_url(env["S3_PUBLIC_BASE_URL"], key)
            row_assertion_name = f"asset:{event_code}:{recipient_type}:{row['file_name']}"
            statements.append(
                "INSERT INTO notification_import_assertions "
                "(assertion_name,actual_count,expected_count) "
                f"SELECT {sql_quote(row_assertion_name)},COUNT(*),1 "
                "FROM notification_media_assets a "
                "JOIN notification_media_rules r ON r.rule_id=a.rule_id "
                f"WHERE r.event_code={sql_quote(event_code)} "
                f"AND r.recipient_type={sql_quote(recipient_type)} "
                f"AND a.storage_key={sql_quote(key)} "
                f"AND a.image_url={sql_quote(url)} "
                f"AND a.original_filename={sql_quote(row['original_filename'])} "
                f"AND a.content_type={sql_quote(row['content_type'])} "
                "AND a.active=b'1';"
            )
    statements.extend(
        (
            "DROP TEMPORARY TABLE notification_import_assertions;",
            "COMMIT;",
            "SELECT 'IMPORT_COMMIT_OK';",
        )
    )
    output = mysql("\n".join(statements) + "\n")
    if "IMPORT_COMMIT_OK" not in output.splitlines():
        raise RuntimeError("MySQL did not confirm notification import COMMIT")


def verify_database(rows: list[dict[str, str]], prefix: str) -> None:
    expected_counts: dict[str, int] = {}
    for row in rows:
        event_code = row["event_code"]
        expected_counts[event_code] = expected_counts.get(event_code, 0) + 1
    for event_code, expected_count in sorted(expected_counts.items()):
        key_prefix = f"notification-media/{event_code.lower()}/{prefix}/"
        count = mysql(
            "SELECT COUNT(*) FROM notification_media_assets a "
            "JOIN notification_media_rules r ON r.rule_id=a.rule_id "
            f"WHERE r.event_code={sql_quote(event_code)} AND a.active=b'1' "
            f"AND LEFT(a.storage_key,CHAR_LENGTH({sql_quote(key_prefix)}))="
            f"{sql_quote(key_prefix)};\n"
        )
        if count != str(expected_count):
            raise RuntimeError(
                f"{event_code}: expected {expected_count} imported assets, got {count}"
            )
        print(f"verified database: {event_code}={expected_count}", flush=True)


def verify_public_assets(rows: list[dict[str, str]], env: dict[str, str], prefix: str) -> None:
    for row in rows:
        event_code = row["event_code"]
        key = f"notification-media/{event_code.lower()}/{prefix}/{row['file_name']}"
        request = urllib.request.Request(
            public_url(env["S3_PUBLIC_BASE_URL"], key), method="HEAD"
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise RuntimeError(f"Public URL returned {response.status}: {key}")
    print(f"verified public assets: {len(rows)}", flush=True)


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


def run_import(
    rows: list[dict[str, str]], env: dict[str, str], prefix: str
) -> None:
    total = len(rows)
    import_id = uuid.uuid4().hex
    newly_uploaded: list[S3ObjectInfo] = []
    newly_uploaded_keys: set[str] = set()
    committed = False

    def track_created(info: S3ObjectInfo) -> None:
        if info.import_id != import_id or info.key in newly_uploaded_keys:
            return
        newly_uploaded.append(info)
        newly_uploaded_keys.add(info.key)

    try:
        for index, row in enumerate(rows, start=1):
            key = (
                f"notification-media/{row['event_code'].lower()}/"
                f"{prefix}/{row['file_name']}"
            )
            data = Path(row["path"]).read_bytes()
            content_type = (
                row.get("content_type")
                or mimetypes.guess_type(row["file_name"])[0]
                or "image/png"
            )
            try:
                created = upload_with_retry(
                    data,
                    key,
                    content_type,
                    env,
                    import_id,
                    on_created=track_created,
                )
            except S3UploadError as error:
                if error.newly_uploaded is not None:
                    track_created(error.newly_uploaded)
                raise
            if created is not None:
                track_created(created)
                action = "uploaded"
            else:
                action = "reused"
            print(
                f"{action} {index}/{total}: "
                f"{row['event_code']}/{row['file_name']}",
                flush=True,
            )

        apply_database_changes(rows, env, prefix)
        committed = True

        # These are post-COMMIT health checks. Their failure must never remove
        # objects now referenced by committed database rows.
        verify_database(rows, prefix)
        verify_public_assets(rows, env, prefix)
    except BaseException:
        if not committed:
            cleanup_uploaded(newly_uploaded, env)
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--env-file", type=Path, default=Path("/docker/.env"))
    parser.add_argument("--prefix", default="generated-20260731-v2")
    parser.add_argument("--status-only", action="store_true")
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()

    if args.status_only:
        print_status()
        return 0
    if args.root is None or args.manifest is None:
        parser.error("--root and --manifest are required unless --status-only is used")

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    rows = validate_manifest(args.root, manifest)
    if args.validate_only:
        rule_count = len({(row["event_code"], row["recipient_type"]) for row in rows})
        print(f"VALIDATION_COMPLETE: {len(rows)} unique assets, {rule_count} rules")
        return 0
    env = load_dotenv(args.env_file)
    missing_env = [key for key in REQUIRED_S3_ENV if not env.get(key)]
    if missing_env:
        raise RuntimeError(f"Missing S3 environment keys: {missing_env}")

    rule_count = len({(row["event_code"], row["recipient_type"]) for row in rows})
    total = len(rows)
    print(f"manifest validation passed: {rule_count} rules, {total} images", flush=True)

    run_import(rows, env, args.prefix)
    print(f"IMPORT_COMPLETE: {total} assets attached", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
        print(f"IMPORT_FAILED: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
