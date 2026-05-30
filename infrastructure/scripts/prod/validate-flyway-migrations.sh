#!/usr/bin/env bash
set -Eeuo pipefail

app_image="${1:?Pass app image, for example claid38/otziv-app:2.86}"
mysql_container="${2:-my-mysql}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required for Flyway migration validation." >&2
  exit 1
fi

if ! docker inspect "$mysql_container" >/dev/null 2>&1; then
  echo "MySQL container is not available for Flyway validation: $mysql_container" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
container_id=""
cleanup() {
  if [ -n "$container_id" ]; then
    docker rm "$container_id" >/dev/null 2>&1 || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

container_id="$(docker create --entrypoint sh "$app_image" -c true)"
docker cp "$container_id:/app/app.jar" "$work_dir/app.jar" >/dev/null
docker rm "$container_id" >/dev/null
container_id=""

python3 - "$work_dir/app.jar" > "$work_dir/image-migrations.tsv" <<'PY'
import re
import sys
import zipfile
import zlib

jar_path = sys.argv[1]
versioned_sql = re.compile(r"^V(.+)__.+\.sql$")

with zipfile.ZipFile(jar_path) as jar:
    for name in sorted(jar.namelist()):
        filename = name.rsplit("/", 1)[-1]
        match = versioned_sql.match(filename)
        if not match:
            continue
        if "/db/migration/" not in name and not name.startswith("db/migration/"):
            continue

        text = jar.read(name).decode("utf-8-sig")
        checksum = 0
        for line in text.splitlines():
            checksum = zlib.crc32(line.encode("utf-8"), checksum)
        if checksum >= 2**31:
            checksum -= 2**32

        version = match.group(1).replace("_", ".")
        print(f"{version}\t{checksum}\t{filename}")
PY

docker exec "$mysql_container" sh -lc \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B -e "SELECT version, checksum FROM flyway_schema_history WHERE success = 1 AND checksum IS NOT NULL"' \
  > "$work_dir/db-migrations.tsv"

python3 - "$work_dir/image-migrations.tsv" "$work_dir/db-migrations.tsv" <<'PY'
import sys

image_path, db_path = sys.argv[1], sys.argv[2]

image = {}
with open(image_path, encoding="utf-8") as handle:
    for line in handle:
        version, checksum, filename = line.rstrip("\n").split("\t", 2)
        image[version] = (checksum, filename)

mismatches = []
with open(db_path, encoding="utf-8") as handle:
    for line in handle:
        if not line.strip():
            continue
        version, applied_checksum = line.rstrip("\n").split("\t", 1)
        resolved = image.get(version)
        if resolved is None:
            mismatches.append(
                f"version {version}: applied in database with checksum {applied_checksum}, but missing in app image"
            )
            continue

        resolved_checksum, filename = resolved
        if applied_checksum != resolved_checksum:
            mismatches.append(
                f"{filename}: applied checksum {applied_checksum}, image checksum {resolved_checksum}"
            )

if mismatches:
    print("Flyway migration validation failed before deployment.", file=sys.stderr)
    print("Already-applied versioned migrations must never be edited.", file=sys.stderr)
    print("Create a new V__ migration for follow-up changes instead.", file=sys.stderr)
    for mismatch in mismatches:
        print(f"  - {mismatch}", file=sys.stderr)
    sys.exit(1)

print("Flyway migration validation passed.")
PY
