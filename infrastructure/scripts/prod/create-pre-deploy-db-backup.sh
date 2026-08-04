#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly BACKUP_FORMAT="OTZIV-PREDEPLOY-DB-V2"
readonly OPENSSL_ITERATIONS="200000"
readonly HMAC_DERIVATION_LABEL="otziv-predeploy-backup-authentication-v1"

cleanup_paths=()
incomplete_artifacts=()
cleanup() {
  local path
  for path in "${cleanup_paths[@]:-}"; do
    if [ -n "$path" ] && [ "$path" != "/" ]; then
      rm -rf -- "$path"
    fi
  done
  for path in "${incomplete_artifacts[@]:-}"; do
    if [ -n "$path" ] && [ "$path" != "/" ]; then
      rm -f -- "$path"
    fi
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required pre-deploy backup command is unavailable: $1" >&2
    exit 1
  fi
}

get_env() {
  local env_file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    $0 !~ /^[[:space:]]*#/ && index($0, key "=") == 1 {
      sub(/^[^=]*=/, "", $0)
      print $0
    }
  ' "$env_file" | tail -n 1 | sed -e 's/\r$//' -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
}

read_manifest_value() {
  local manifest="$1"
  local key="$2"
  awk -F= -v key="$key" 'index($0, key "=") == 1 { sub(/^[^=]*=/, "", $0); print $0 }' "$manifest" | tail -n 1
}

load_and_validate_key() {
  local env_file="$1"
  if [ ! -f "$env_file" ]; then
    echo "Pre-deploy backup env file is missing: $env_file" >&2
    exit 1
  fi

  local key_base64
  key_base64="$(get_env "$env_file" DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64)"
  if [ -z "$key_base64" ]; then
    echo "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 is required for every production deployment." >&2
    exit 1
  fi
  if ! printf '%s' "$key_base64" | python3 -c '
import base64, binascii, sys
try:
    key = base64.b64decode(sys.stdin.read(), validate=True)
except (binascii.Error, ValueError):
    raise SystemExit(1)
raise SystemExit(0 if len(key) == 32 else 1)
'; then
    echo "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64 encoding exactly 32 bytes." >&2
    exit 1
  fi

  printf '%s' "$key_base64"
}

assert_distinct_backup_keys() {
  local env_file="$1"
  local deploy_key_base64="$2"
  local credential_key_base64 scheduled_key_base64 validation_status
  credential_key_base64="$(get_env "$env_file" OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64)"
  scheduled_key_base64="$(get_env "$env_file" BACKUP_ENCRYPTION_KEY_BASE64)"
  validation_status="0"

  printf '%s\n%s\n%s' "$deploy_key_base64" "$credential_key_base64" "$scheduled_key_base64" \
    | python3 -c '
import base64, binascii, hmac, sys

values = sys.stdin.buffer.read().splitlines()
while len(values) < 3:
    values.append(b"")

def decode(value, error_code):
    if not value:
        return None
    try:
        value += b"=" * (-len(value) % 4)
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        raise SystemExit(error_code)
    if len(decoded) != 32:
        raise SystemExit(error_code)
    return decoded

deploy = decode(values[0], 1)
credential = decode(values[1], 2)
scheduled = decode(values[2], 3)
if credential is not None and hmac.compare_digest(deploy, credential):
    raise SystemExit(4)
if scheduled is not None and hmac.compare_digest(deploy, scheduled):
    raise SystemExit(5)
' || validation_status="$?"

  case "$validation_status" in
    0)
      ;;
    1)
      echo "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64 encoding exactly 32 bytes." >&2
      exit 1
      ;;
    2)
      echo "OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must be valid Base64 encoding exactly 32 bytes before backup." >&2
      exit 1
      ;;
    3)
      echo "BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64 encoding exactly 32 bytes when configured." >&2
      exit 1
      ;;
    4)
      echo "Deploy DB-backup encryption and credential-field encryption must use different keys." >&2
      exit 1
      ;;
    5)
      echo "Pre-deploy and scheduled DB backups must use different encryption keys." >&2
      exit 1
      ;;
    *)
      echo "Unable to verify production backup-key separation." >&2
      exit 1
      ;;
  esac

  credential_key_base64=""
  scheduled_key_base64=""
}

compute_hmac() {
  local key_base64="$1"
  local artifact="$2"
  printf '%s' "$key_base64" | python3 -c '
import base64, hashlib, hmac, sys
artifact = sys.argv[1]
derivation_label = sys.argv[2].encode("utf-8")
master = bytearray(base64.b64decode(sys.stdin.read(), validate=True))
try:
    mac_key = hmac.new(master, derivation_label, hashlib.sha256).digest()
    verifier = hmac.new(mac_key, digestmod=hashlib.sha256)
    with open(artifact, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            verifier.update(chunk)
    print(verifier.hexdigest().upper())
finally:
    for index in range(len(master)):
        master[index] = 0
' "$artifact" "$HMAC_DERIVATION_LABEL"
}

make_key_file() {
  local directory="$1"
  local key_base64="$2"
  local key_file
  key_file="$(mktemp "$directory/.deploy-backup-key.XXXXXXXX")"
  chmod 600 "$key_file"
  printf '%s' "$key_base64" > "$key_file"
  cleanup_paths+=("$key_file")
  printf '%s' "$key_file"
}

decrypt_artifact_to_stdout() {
  local artifact="$1"
  local key_file="$2"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter "$OPENSSL_ITERATIONS" -md sha256 \
    -in "$artifact" -pass "file:$key_file"
}

verify_artifact() {
  local artifact="$1"
  local manifest="$2"
  local env_file="$3"

  if [ ! -f "$artifact" ] || [ ! -s "$artifact" ]; then
    echo "Encrypted pre-deploy database backup is missing or empty: $artifact" >&2
    exit 1
  fi
  if [ ! -f "$manifest" ]; then
    echo "Pre-deploy database backup manifest is missing: $manifest" >&2
    exit 1
  fi

  local format expected_sha expected_hmac actual_sha actual_hmac key_base64
  format="$(read_manifest_value "$manifest" FORMAT)"
  expected_sha="$(read_manifest_value "$manifest" SHA256)"
  expected_hmac="$(read_manifest_value "$manifest" HMAC_SHA256)"
  if [ "$format" != "$BACKUP_FORMAT" ]; then
    echo "Unsupported pre-deploy database backup format: $format" >&2
    exit 1
  fi
  if ! printf '%s' "$expected_sha" | grep -Eq '^[0-9A-F]{64}$' \
      || ! printf '%s' "$expected_hmac" | grep -Eq '^[0-9A-F]{64}$'; then
    echo "Pre-deploy database backup manifest contains invalid integrity metadata." >&2
    exit 1
  fi

  key_base64="$(load_and_validate_key "$env_file")"
  actual_sha="$(sha256sum "$artifact" | awk '{print toupper($1)}')"
  actual_hmac="$(compute_hmac "$key_base64" "$artifact")"
  if [ "$actual_sha" != "$expected_sha" ] || [ "$actual_hmac" != "$expected_hmac" ]; then
    echo "Pre-deploy database backup checksum or HMAC verification failed." >&2
    exit 1
  fi

  local verify_dir key_file
  verify_dir="$(mktemp -d "$(dirname "$artifact")/.verify.XXXXXXXX")"
  cleanup_paths+=("$verify_dir")
  key_file="$(make_key_file "$verify_dir" "$key_base64")"
  if ! decrypt_artifact_to_stdout "$artifact" "$key_file" | gzip -t; then
    echo "Decrypted pre-deploy backup is not a valid gzip stream." >&2
    exit 1
  fi
  if ! decrypt_artifact_to_stdout "$artifact" "$key_file" | gzip -dc | awk '
    /CREATE TABLE|INSERT INTO|Flyway|flyway_schema_history/ { valid = 1 }
    END { exit(valid ? 0 : 1) }
  '; then
    echo "Decrypted pre-deploy backup does not contain an expected SQL marker." >&2
    exit 1
  fi

  key_base64=""
  echo "Verified encrypted pre-deploy database backup: $artifact"
}

flyway_fingerprint() {
  local mysql_container="$1"
  local table_present
  table_present="$(docker exec "$mysql_container" sh -lc \
    'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B -e "SHOW TABLES LIKE '\''flyway_schema_history'\''"' 2>/dev/null || true)"
  if [ -z "$table_present" ]; then
    printf 'ABSENT'
    return 0
  fi

  docker exec "$mysql_container" sh -lc \
    'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B -e "SELECT installed_rank, COALESCE(version, '\'''\''), success, COALESCE(checksum, 0) FROM flyway_schema_history ORDER BY installed_rank"' \
    | sha256sum | awk '{print toupper($1)}'
}

create_backup() {
  local mysql_container="$1"
  local backup_dir="$2"
  local env_file="$3"
  local deploy_tag="$4"
  local key_base64 run_id work_dir key_file artifact manifest artifact_tmp manifest_tmp
  local schema_defaults schema_charset schema_collation

  if ! printf '%s' "$deploy_tag" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'; then
    echo "Deploy tag contains unsupported characters." >&2
    exit 1
  fi
  key_base64="$(load_and_validate_key "$env_file")"
  assert_distinct_backup_keys "$env_file" "$key_base64"
  if ! docker inspect "$mysql_container" >/dev/null 2>&1; then
    echo "MySQL container is unavailable for the mandatory pre-deploy backup: $mysql_container" >&2
    exit 1
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$mysql_container")" != "true" ]; then
    docker start "$mysql_container" >/dev/null
  fi
  mysql_ready="0"
  _attempt="1"
  while [ "$_attempt" -le 120 ]; do
    if docker exec "$mysql_container" sh -lc \
        'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B -e "SELECT 1"' >/dev/null 2>&1; then
      mysql_ready="1"
      break
    fi
    sleep 5
    _attempt="$((_attempt + 1))"
  done
  if [ "$mysql_ready" != "1" ]; then
    echo "MySQL did not become ready for the mandatory pre-deploy backup within 600 seconds." >&2
    exit 1
  fi

  mkdir -p "$backup_dir"
  chmod 700 "$backup_dir"
  run_id="$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 8)"
  work_dir="$(mktemp -d "$backup_dir/.create.XXXXXXXX")"
  cleanup_paths+=("$work_dir")
  key_file="$(make_key_file "$work_dir" "$key_base64")"
  artifact="$backup_dir/pre-deploy-$deploy_tag-$run_id.sql.gz.enc"
  manifest="$artifact.manifest"
  incomplete_artifacts+=("$artifact" "$manifest")
  artifact_tmp="$work_dir/database.sql.gz.enc"
  manifest_tmp="$work_dir/manifest"

  schema_defaults="$(docker exec "$mysql_container" sh -lc '
    exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B -e \
      "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
         FROM information_schema.SCHEMATA
        WHERE SCHEMA_NAME = DATABASE()" \
      "$MYSQL_DATABASE"
  ')"
  IFS=$'\t' read -r schema_charset schema_collation <<< "$schema_defaults"
  if ! printf '%s' "$schema_charset" | grep -Eq '^[A-Za-z0-9_]+$' \
      || ! printf '%s' "$schema_collation" | grep -Eq '^[A-Za-z0-9_]+$'; then
    echo "Unable to capture safe database charset/collation metadata for recovery." >&2
    exit 1
  fi

  echo "Creating mandatory pre-deploy database backup..."
  {
    # This recovery metadata is authenticated and encrypted together with the
    # SQL stream, so restore-clean never has to trust editable manifest fields.
    printf -- '-- OTZIV_SCHEMA_DEFAULTS:%s:%s\n' "$schema_charset" "$schema_collation"
    docker exec "$mysql_container" sh -lc '
      MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump \
        -u"$MYSQL_USER" \
        --single-transaction --quick --routines --events --triggers \
        --hex-blob --no-tablespaces --set-gtid-purged=OFF \
        "$MYSQL_DATABASE"
    '
  } | gzip -9 | openssl enc -aes-256-cbc -salt -pbkdf2 -iter "$OPENSSL_ITERATIONS" -md sha256 \
    -out "$artifact_tmp" -pass "file:$key_file"
  if [ ! -s "$artifact_tmp" ]; then
    echo "mysqldump/gzip/encryption produced an empty artifact." >&2
    exit 1
  fi

  local sha256 hmac_sha256 bytes created_at flyway_sha
  sha256="$(sha256sum "$artifact_tmp" | awk '{print toupper($1)}')"
  hmac_sha256="$(compute_hmac "$key_base64" "$artifact_tmp")"
  bytes="$(wc -c < "$artifact_tmp" | tr -d ' ')"
  created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  flyway_sha="$(flyway_fingerprint "$mysql_container")"
  cat > "$manifest_tmp" <<EOF
FORMAT=$BACKUP_FORMAT
ENCRYPTION=OPENSSL-AES-256-CBC-PBKDF2-SHA256
PBKDF2_ITERATIONS=$OPENSSL_ITERATIONS
ARTIFACT=$(basename "$artifact")
SHA256=$sha256
HMAC_SHA256=$hmac_sha256
BYTES=$bytes
CREATED_AT=$created_at
DEPLOY_TAG=$deploy_tag
FLYWAY_FINGERPRINT=$flyway_sha
EOF
  chmod 600 "$artifact_tmp" "$manifest_tmp"
  mv "$artifact_tmp" "$artifact"
  mv "$manifest_tmp" "$manifest"

  verify_artifact "$artifact" "$manifest" "$env_file"
  incomplete_artifacts=()
  key_base64=""
  printf 'OTZIV_PREDEPLOY_BACKUP_ARTIFACT=%s\n' "$artifact"
  printf 'OTZIV_PREDEPLOY_BACKUP_MANIFEST=%s\n' "$manifest"
  printf 'OTZIV_PREDEPLOY_BACKUP_SHA256=%s\n' "$sha256"
  printf 'OTZIV_PREDEPLOY_BACKUP_HMAC_SHA256=%s\n' "$hmac_sha256"
  printf 'OTZIV_PREDEPLOY_BACKUP_BYTES=%s\n' "$bytes"
  printf 'OTZIV_PREDEPLOY_BACKUP_FLYWAY_FINGERPRINT=%s\n' "$flyway_sha"
}

decrypt_backup() {
  local artifact="$1"
  local manifest="$2"
  local env_file="$3"
  local output_gzip="$4"
  if [ -e "$output_gzip" ]; then
    echo "Refusing to overwrite decrypted output: $output_gzip" >&2
    exit 1
  fi
  verify_artifact "$artifact" "$manifest" "$env_file"
  local key_base64 key_dir key_file output_tmp
  key_base64="$(load_and_validate_key "$env_file")"
  key_dir="$(mktemp -d "$(dirname "$output_gzip")/.decrypt.XXXXXXXX")"
  cleanup_paths+=("$key_dir")
  key_file="$(make_key_file "$key_dir" "$key_base64")"
  output_tmp="$key_dir/database.sql.gz"
  decrypt_artifact_to_stdout "$artifact" "$key_file" > "$output_tmp"
  gzip -t "$output_tmp"
  mv "$output_tmp" "$output_gzip"
  chmod 600 "$output_gzip"
  key_base64=""
  echo "Decrypted verified database backup to: $output_gzip"
}

restore_clean() {
  local artifact="$1"
  local manifest="$2"
  local env_file="$3"
  local mysql_container="$4"
  local confirmation="$5"

  if [ "$confirmation" != "I_UNDERSTAND_THIS_REPLACES_PRODUCTION_DATABASE" ]; then
    echo "Clean restore requires the exact confirmation I_UNDERSTAND_THIS_REPLACES_PRODUCTION_DATABASE." >&2
    exit 2
  fi
  if command -v systemctl >/dev/null 2>&1; then
    local unit unit_state timer_enablement
    timer_enablement="$(systemctl is-enabled otziv-prod-up.timer 2>/dev/null || true)"
    case "$timer_enablement" in
      disabled|masked|masked-runtime)
        ;;
      *)
        echo "Refusing restore while otziv-prod-up.timer is enabled for automatic startup ($timer_enablement). Disable it before clean restore." >&2
        exit 1
        ;;
    esac
    for unit in otziv-prod-up.timer otziv-prod-up.service; do
      if ! unit_state="$(systemctl show "$unit" --property=ActiveState --value 2>/dev/null)"; then
        echo "Refusing restore because self-heal unit state cannot be verified: $unit" >&2
        exit 1
      fi
      case "$unit_state" in
        inactive|failed)
          ;;
        *)
          echo "Refusing restore while $unit is $unit_state." >&2
          exit 1
          ;;
      esac
    done
  fi

  local running_writers
  running_writers="$(docker ps --format '{{.Label "com.docker.compose.service"}}' \
    | grep -E '^(app|nginx|whatsapp_lika|whatsapp_vika|external-review-worker)$' || true)"
  if [ -n "$running_writers" ]; then
    echo "Refusing restore while write-path services are running:" >&2
    printf '%s\n' "$running_writers" >&2
    exit 1
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$mysql_container" 2>/dev/null || true)" != "true" ]; then
    echo "MySQL container is not running for clean restore: $mysql_container" >&2
    exit 1
  fi

  verify_artifact "$artifact" "$manifest" "$env_file"
  local key_base64 restore_dir key_file decrypted_gzip schema_header schema_charset schema_collation compatibility_sql
  key_base64="$(load_and_validate_key "$env_file")"
  restore_dir="$(mktemp -d "$(dirname "$artifact")/.restore.XXXXXXXX")"
  cleanup_paths+=("$restore_dir")
  key_file="$(make_key_file "$restore_dir" "$key_base64")"
  decrypted_gzip="$restore_dir/database.sql.gz"
  decrypt_artifact_to_stdout "$artifact" "$key_file" > "$decrypted_gzip"
  gzip -t "$decrypted_gzip"

  schema_header="$(gzip -dc "$decrypted_gzip" | {
    IFS= read -r first_line
    printf '%s' "$first_line"
    cat >/dev/null
  })"
  if [[ "$schema_header" =~ ^--\ OTZIV_SCHEMA_DEFAULTS:([A-Za-z0-9_]+):([A-Za-z0-9_]+)$ ]]; then
    schema_charset="${BASH_REMATCH[1]}"
    schema_collation="${BASH_REMATCH[2]}"
  else
    echo "Encrypted backup is missing authenticated schema charset/collation metadata." >&2
    exit 1
  fi
  compatibility_sql="SELECT COUNT(*) FROM information_schema.COLLATIONS WHERE CHARACTER_SET_NAME = '$schema_charset' AND COLLATION_NAME = '$schema_collation'"

  docker exec \
    -e OTZIV_RESTORE_CHARSET="$schema_charset" \
    -e OTZIV_RESTORE_COLLATION="$schema_collation" \
    -e OTZIV_RESTORE_COMPATIBILITY_SQL="$compatibility_sql" \
    "$mysql_container" sh -lc '
    set -eu
    case "$MYSQL_DATABASE" in
      ""|*[!A-Za-z0-9_]*)
        echo "MYSQL_DATABASE contains unsafe identifier characters." >&2
        exit 2
        ;;
    esac
    case "$OTZIV_RESTORE_CHARSET:$OTZIV_RESTORE_COLLATION" in
      *[!A-Za-z0-9_:]*)
        echo "Restore charset/collation contains unsafe characters." >&2
        exit 2
        ;;
    esac
    compatible_count="$(MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "$OTZIV_RESTORE_COMPATIBILITY_SQL")"
    if [ "$compatible_count" != "1" ]; then
      echo "Backup schema charset/collation is unsupported by this MySQL server." >&2
      exit 2
    fi
    quoted_database="\`$MYSQL_DATABASE\`"
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e \
      "DROP DATABASE IF EXISTS $quoted_database; CREATE DATABASE $quoted_database CHARACTER SET $OTZIV_RESTORE_CHARSET COLLATE $OTZIV_RESTORE_COLLATION"
  '
  gzip -dc "$decrypted_gzip" | docker exec -i "$mysql_container" sh -lc \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE"'
  docker exec "$mysql_container" sh -lc \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE" -N -B -e "SELECT COUNT(*) FROM flyway_schema_history"' \
    >/dev/null
  key_base64=""
  echo "Clean database restore completed. Writers and self-heal remain stopped for manual validation."
}

for required in awk base64 docker gzip openssl python3 sed sha256sum; do
  require_command "$required"
done

mode="${1:-}"
case "$mode" in
  create)
    [ "$#" -eq 5 ] || { echo "Usage: $0 create <mysql-container> <backup-dir> <env-file> <deploy-tag>" >&2; exit 2; }
    create_backup "$2" "$3" "$4" "$5"
    ;;
  verify)
    [ "$#" -eq 4 ] || { echo "Usage: $0 verify <artifact> <manifest> <env-file>" >&2; exit 2; }
    verify_artifact "$2" "$3" "$4"
    ;;
  decrypt)
    [ "$#" -eq 5 ] || { echo "Usage: $0 decrypt <artifact> <manifest> <env-file> <output.sql.gz>" >&2; exit 2; }
    decrypt_backup "$2" "$3" "$4" "$5"
    ;;
  restore-clean)
    [ "$#" -eq 6 ] || { echo "Usage: $0 restore-clean <artifact> <manifest> <env-file> <mysql-container> I_UNDERSTAND_THIS_REPLACES_PRODUCTION_DATABASE" >&2; exit 2; }
    restore_clean "$2" "$3" "$4" "$5" "$6"
    ;;
  *)
    echo "Usage: $0 {create|verify|decrypt|restore-clean} ..." >&2
    exit 2
    ;;
esac
