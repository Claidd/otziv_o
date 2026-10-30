"""Explicit C42 -> C46 MySQL OS-package refresh under the production deploy lock.

The normal database image guard remains authoritative for ordinary deployments.
This procedure needs the exact reviewed C46 proof, a quiescent encrypted backup,
and a same-volume rollback if the new image or data checks fail. It never restores
the self-heal timer or releases the deploy lock; the calling release owns both.
"""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from database_image_guard import checked_override, DockerReadOnly, GuardError


PROJECT = "otziv-prod"
MYSQL = "my-mysql"
SOURCE = "ghcr.io/claidd/otziv-security@sha256:d8b2c5d02611016eee74b77caede5c57a16cd8a1f379bf5c74d322bfd1b5f75d"
SOURCE_CONFIG = "sha256:b8b1aab82af45b783263e514e2f9746959de78d3a963245cf386720a32b8564f"
TARGET = "ghcr.io/claidd/otziv-security@sha256:0ad40a668c292ebe70297cd4df66b2e9c5067fad3bfdb54179347a597e1ac48f"
TARGET_CONFIG = "sha256:d6202b12659d22367dfcf315852aaf87a967492ed7601c5f17e6425880c5a069"
SOURCE_PG = "ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5"
TARGET_PG = "ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5"
VOLUME = "docker_mysql_data"
REHEARSAL_PATH = "infrastructure/runtime-security/proofs/c46-mysql/rehearsal.json"
REHEARSAL_SCRIPT = "infrastructure/runtime-security/mysql-c46-rehearsal.py"
# Exact published-digest C46 rehearsal, not the preliminary local build.
REHEARSAL_SHA256 = "677a9cb24dbf1ccadc3cdee49c1de3d2a3593f0284b15a1205d7c38c364743f1"
BACKUP_SCRIPT = "infrastructure/scripts/prod/create-pre-deploy-db-backup.sh"
BACKUP_SCRIPT_SHA256 = "cd04431ad3afdae11176451e49cb7da09ae2ab5a8c8fe7cce05c93a83af6dc11"
REQUIRED_REHEARSAL_CHECKS = {
    "engine_version_source", "engine_version_candidate", "server_binary_unchanged",
    "curl_source_vulnerable", "brotli_preserved", "openssl_preserved", "only_curl_changed",
    "all_tables_schema_history_accounts_and_settings_exact", "existing_account_login",
    "restart_preserves_new_writes", "same_volume_rollback_preserves_all_new_writes",
    "fresh_restore_preserves_all_data_and_schema",
}
WRITERS = ("nginx", "whatsapp_lika", "whatsapp_vika", "external-review-worker", "phpmyadmin", "app")
REQUIRED_WRITERS = frozenset(("nginx", "whatsapp_lika", "whatsapp_vika", "app"))
START_ORDER = ("app", "whatsapp_lika", "whatsapp_vika", "external-review-worker", "phpmyadmin", "nginx")


def require(condition, code):
    if not condition:
        raise GuardError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def command(args, timeout=180):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, check=False)
    require(result.returncode == 0, "mysql_c46_command_failed_" + Path(args[0]).name)
    return result.stdout


def inspect(name, kind="container"):
    items = json.loads(command(["docker", kind, "inspect", name]))
    require(isinstance(items, list) and len(items) == 1, "mysql_c46_ambiguous_docker_identity")
    return items[0]


def validate_rehearsal(data):
    require(data.get("schema") == "otziv-mysql-same-engine-refresh-v1"
            and data.get("result") == "PASS" and data.get("preliminary") is False,
            "mysql_c46_rehearsal_not_final")
    require(data.get("source") == SOURCE and data.get("candidate") == TARGET
            and data.get("candidateLocalId") in (TARGET_CONFIG, TARGET.split("@")[1]),
            "mysql_c46_rehearsal_image_mismatch")
    require(data.get("production") is False and data.get("sourceWrites") is False
            and data.get("publishedPorts") == 0 and data.get("network") == "none",
            "mysql_c46_rehearsal_not_isolated")
    require(data.get("sourceMysqldSha256") == data.get("candidateMysqldSha256")
            and re.fullmatch("[a-f0-9]{64}", data.get("candidateMysqldSha256", "")),
            "mysql_c46_engine_changed")
    require(data.get("candidateOpenSSL") == "openssl-libs-3.5.8-2.0.1.el9_8.x86_64"
            and data.get("sourceOpenSSL") == "openssl-libs-3.5.8-2.0.1.el9_8.x86_64"
            and data.get("sourceLibbrotli") == "libbrotli-1.0.9-10.0.1.el9_8.x86_64"
            and data.get("candidateLibbrotli") == "libbrotli-1.0.9-10.0.1.el9_8.x86_64"
            and sorted(data.get("sourceCurl", [])) == ["curl-7.76.1-40.el9_8.7.x86_64", "libcurl-7.76.1-40.el9_8.7.x86_64"]
            and sorted(data.get("candidateCurl", [])) == ["curl-7.76.1-40.el9_8.9.x86_64", "libcurl-7.76.1-40.el9_8.9.x86_64"]
            and data.get("sourceOtherPackagesSha256") == data.get("candidateOtherPackagesSha256"),
            "mysql_c46_package_scope_changed")
    require(data.get("before") == data.get("after")
            and data.get("before", {}).get("tableCount", 0) > 100
            and data.get("before", {}).get("rows", 0) > 0,
            "mysql_c46_rehearsal_data_mismatch")
    require(data.get("ownedResourcesRemaining") == {"containers": 0, "volumes": 0},
            "mysql_c46_rehearsal_cleanup_failed")
    checks = data.get("checks", [])
    require(isinstance(checks, list) and all(c.get("passed") is True for c in checks),
            "mysql_c46_rehearsal_check_failed")
    require(all(sum(c.get("name") == name for c in checks) == 1 for name in REQUIRED_REHEARSAL_CHECKS),
            "mysql_c46_rehearsal_check_missing")


def validate_activation(root):
    index = json.loads((root / "infrastructure/runtime-security/reviewed-image-activations.json").read_bytes())
    entries = [item for item in index.get("images", []) if item.get("component") == "mysql"]
    require(len(entries) == 1 and entries[0].get("reference") == TARGET,
            "mysql_c46_not_reviewed_default")
    entry = entries[0]
    expected = {"manifest": "infrastructure/runtime-security/reviewed-images-c46-mysql.json",
                "publication": "infrastructure/runtime-security/proofs/c46-mysql/publication/publication.json",
                "anonymous": "infrastructure/runtime-security/proofs/c46-mysql/anonymous/anonymous-download.json",
                "databaseTransition": REHEARSAL_PATH}
    for key, path in expected.items():
        reference = entry.get(key, {})
        require(reference.get("path") == path
                and re.fullmatch("[a-f0-9]{64}", reference.get("sha256", "")),
                "mysql_c46_activation_reference_changed")
        file = root / path
        require(file.is_file() and not file.is_symlink()
                and sha(file.read_bytes()) == reference["sha256"],
                "mysql_c46_activation_evidence_changed")
    require(entry["databaseTransition"]["sha256"] == REHEARSAL_SHA256,
            "mysql_c46_rehearsal_activation_mismatch")
    publication = json.loads((root / expected["publication"]).read_bytes())
    require(publication.get("reference") == TARGET and publication.get("imageId") == TARGET_CONFIG
            and publication.get("security", {}).get("result") == "PASS",
            "mysql_c46_publication_not_accepted")
    return entry


def validate_resolved(config):
    require(config.get("name") == PROJECT, "mysql_c46_wrong_project")
    services = config.get("services", {})
    require(services.get("mysql", {}).get("image") == TARGET, "mysql_c46_compose_target_changed")
    require(not services["mysql"].get("ports"), "mysql_c46_public_database_port")
    require(services.get("keycloak-postgres", {}).get("image") == TARGET_PG,
            "mysql_c46_expected_postgres_preservation_missing")
    old = copy.deepcopy(config)
    old["services"]["mysql"]["image"] = SOURCE
    old["services"]["keycloak-postgres"]["image"] = SOURCE_PG
    return old


def validate_mysql_container(meta, reference, local_id):
    labels = meta.get("Config", {}).get("Labels", {})
    mounts = [m for m in meta.get("Mounts", []) if m.get("Destination") == "/var/lib/mysql"]
    require(meta.get("Name") == "/" + MYSQL and meta.get("Image") == local_id
            and meta.get("Config", {}).get("Image") == reference
            and labels.get("com.docker.compose.project") == PROJECT
            and labels.get("com.docker.compose.service") == "mysql"
            and str(labels.get("com.docker.compose.oneoff", "False")).lower() == "false",
            "mysql_c46_container_identity_changed")
    require(len(mounts) == 1 and mounts[0].get("Type") == "volume"
            and mounts[0].get("Name") == VOLUME and mounts[0].get("RW") is True
            and not mounts[0].get("SubPath"), "mysql_c46_data_volume_changed")
    require(meta.get("State", {}).get("Status") == "running"
            and meta.get("State", {}).get("Health", {}).get("Status") == "healthy",
            "mysql_c46_database_not_healthy")
    require(not meta.get("HostConfig", {}).get("PortBindings"), "mysql_c46_public_database_port")


def validate_backup_receipt(output, directory):
    values = dict(line.split("=", 1) for line in output.decode().splitlines()
                  if line.startswith("OTZIV_PREDEPLOY_BACKUP_") and "=" in line)
    artifact = Path(values.get("OTZIV_PREDEPLOY_BACKUP_ARTIFACT", ""))
    manifest = Path(values.get("OTZIV_PREDEPLOY_BACKUP_MANIFEST", ""))
    require(artifact.parent == directory and manifest == Path(str(artifact) + ".manifest")
            and artifact.is_file() and manifest.is_file()
            and not artifact.is_symlink() and not manifest.is_symlink(),
            "mysql_c46_backup_artifact_invalid")
    require(values.get("OTZIV_PREDEPLOY_BACKUP_SHA256", "").lower() == sha(artifact.read_bytes())
            and re.fullmatch("[0-9A-F]{64}", values.get("OTZIV_PREDEPLOY_BACKUP_HMAC_SHA256", ""))
            and int(values.get("OTZIV_PREDEPLOY_BACKUP_BYTES", "0")) == artifact.stat().st_size
            and re.fullmatch("[0-9A-F]{64}", values.get("OTZIV_PREDEPLOY_BACKUP_FLYWAY_FINGERPRINT", "")),
            "mysql_c46_backup_receipt_invalid")
    return {"artifact": str(artifact), "manifest": str(manifest),
            "sha256": values["OTZIV_PREDEPLOY_BACKUP_SHA256"],
            "hmacSha256": values["OTZIV_PREDEPLOY_BACKUP_HMAC_SHA256"],
            "flywaySha256": values["OTZIV_PREDEPLOY_BACKUP_FLYWAY_FINGERPRINT"]}


def sql(query, root=False, timeout=120):
    user = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot' if root else \
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
    shell = user + ' -N -B -e "$OTZIV_READ_QUERY"'
    result = subprocess.run(["docker", "exec", "-e", "OTZIV_READ_QUERY=" + query,
                             MYSQL, "sh", "-lc", shell],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, check=False)
    require(result.returncode == 0, "mysql_c46_sql_failed")
    return result.stdout


def snapshot():
    # Values remain on the VPS. Only hashes and a table count enter the record.
    tables = sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")
    table_count = len(tables.splitlines())
    require(table_count > 100, "mysql_c46_schema_unexpectedly_empty")
    names = [name.decode("utf-8") for name in tables.splitlines()]
    require(all(re.fullmatch(r"[A-Za-z0-9_]+", name) for name in names),
            "mysql_c46_unexpected_table_name")
    checksum_query = "CHECKSUM TABLE " + ",".join("`" + name + "`" for name in names) + " EXTENDED;"
    checksums = sql(checksum_query, timeout=900)
    checksum_rows = [line.split(b"\t") for line in checksums.splitlines()]
    require(len(checksum_rows) == table_count
            and all(len(row) == 2 and re.fullmatch(rb"[0-9]+", row[1]) for row in checksum_rows),
            "mysql_c46_table_data_checksum_unavailable")
    schema = sql("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,'NULL'),EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION;")
    flyway = sql("SELECT * FROM flyway_schema_history ORDER BY installed_rank;")
    accounts = sql("SELECT User,Host,plugin,authentication_string FROM mysql.user ORDER BY User,Host;", root=True)
    return {"tableCount": table_count, "tablesSha256": sha(tables),
            "tableDataSha256": sha(checksums), "schemaSha256": sha(schema),
            "flywaySha256": sha(flyway), "accountsSha256": sha(accounts)}


def mysql_health():
    for _ in range(90):
        meta = inspect(MYSQL)
        if meta.get("State", {}).get("Health", {}).get("Status") == "healthy":
            return meta
        require(meta.get("State", {}).get("Status") == "running", "mysql_c46_database_exited")
        time.sleep(3)
    raise GuardError("mysql_c46_health_timeout")


def writer_inventory():
    entries = {}
    for service in WRITERS:
        raw = command(["docker", "container", "ls", "--all", "--quiet", "--no-trunc", "--filter",
                       "label=com.docker.compose.project=" + PROJECT, "--filter",
                       "label=com.docker.compose.service=" + service]).decode().splitlines()
        require(len(raw) <= 1 and (service not in REQUIRED_WRITERS or len(raw) == 1),
                "mysql_c46_writer_inventory_ambiguous")
        if raw:
            meta = inspect(raw[0])
            require(meta.get("Id") == raw[0] and meta.get("Config", {}).get("Labels", {}).get("com.docker.compose.service") == service,
                    "mysql_c46_writer_identity_changed")
            if service in REQUIRED_WRITERS:
                require(meta.get("State", {}).get("Status") == "running", "mysql_c46_required_writer_not_running")
            if meta.get("State", {}).get("Status") == "running":
                entries[service] = raw[0]
    return entries


def wait_writer(meta_id, timeout=600):
    for _ in range(timeout // 5):
        meta = inspect(meta_id)
        state = meta.get("State", {})
        require(state.get("Status") == "running", "mysql_c46_writer_exited")
        health = state.get("Health", {}).get("Status")
        if health in (None, "healthy"):
            return
        time.sleep(5)
    raise GuardError("mysql_c46_writer_health_timeout")


def write_private(path, data):
    with path.open("x", encoding="utf-8") as stream:
        os.fchmod(stream.fileno(), 0o600)
        json.dump(data, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def run_cutover(args):
    root = Path(args.root).resolve(strict=True)
    env_file = Path(args.env_file).resolve(strict=True)
    require(re.fullmatch("[a-f0-9]{40}", args.main_revision)
            and re.fullmatch("[a-f0-9]{32}", args.lock_token), "mysql_c46_invalid_release_identity")
    require(env_file.is_relative_to(root) and not (root / ".env").is_symlink(), "mysql_c46_env_path_changed")
    lock = root / ".deploy.lock.d"
    require(lock.is_dir() and not lock.is_symlink()
            and (lock / "owner").read_text().strip() == args.lock_token,
            "mysql_c46_deploy_lock_missing")
    require(command(["sudo", "-n", "systemctl", "show", "otziv-prod-up.timer", "--property=ActiveState", "--value"]).decode().strip() == "inactive",
            "mysql_c46_self_heal_timer_not_paused")
    require(command(["sudo", "-n", "systemctl", "show", "otziv-prod-up.service", "--property=ActiveState", "--value"]).decode().strip() == "inactive",
            "mysql_c46_self_heal_service_not_paused")
    proof_path = root / REHEARSAL_PATH
    require(re.fullmatch("[a-f0-9]{64}", REHEARSAL_SHA256 or "")
            and proof_path.is_file() and not proof_path.is_symlink()
            and sha(proof_path.read_bytes()) == REHEARSAL_SHA256,
            "mysql_c46_published_rehearsal_changed")
    validate_rehearsal(json.loads(proof_path.read_bytes()))
    rehearsal_script = root / REHEARSAL_SCRIPT
    require(rehearsal_script.is_file() and not rehearsal_script.is_symlink()
            and sha(rehearsal_script.read_bytes()) == json.loads(proof_path.read_bytes()).get("executedSourceSha256"),
            "mysql_c46_rehearsal_source_changed")
    validate_activation(root)
    backup_script = root / BACKUP_SCRIPT
    require(backup_script.is_file() and not backup_script.is_symlink()
            and sha(backup_script.read_bytes()) == BACKUP_SCRIPT_SHA256,
            "mysql_c46_backup_program_changed")
    compose = ["docker", "compose", "--project-name", PROJECT, "--project-directory", str(root),
               "-f", str(root / "docker-compose.yaml"), "--env-file", str(env_file)]
    resolved = json.loads(command([*compose, "config", "--format", "json"]))
    source_resolved = validate_resolved(resolved)
    checked_override(source_resolved, DockerReadOnly())
    # Download only the reviewed immutable index, before stopping any writer.
    command(["docker", "pull", "--platform", "linux/amd64", TARGET], timeout=600)
    target_image = inspect(TARGET, "image")
    source_image = inspect(SOURCE, "image")
    require(target_image.get("Id") in (TARGET_CONFIG, TARGET.split("@")[1])
            and TARGET in target_image.get("RepoDigests", [])
            and target_image.get("Os") == "linux" and target_image.get("Architecture") == "amd64",
            "mysql_c46_candidate_image_not_pulled_or_wrong")
    require(source_image.get("Id") in (SOURCE_CONFIG, SOURCE.split("@")[1])
            and SOURCE in source_image.get("RepoDigests", []),
            "mysql_c46_source_image_changed")
    old = inspect(MYSQL)
    validate_mysql_container(old, SOURCE, source_image["Id"])
    writers = writer_inventory()
    # Only named running services can reach the database as writers. Stop them
    # before the extra backup, and block even root/events with super_read_only.
    stage_base = root / ".deploy-backups"
    require(stage_base.is_dir() and not stage_base.is_symlink(), "mysql_c46_backup_directory_missing")
    stage = stage_base / ("mysql-c46-cutover-" + args.lock_token)
    require(not stage.exists(), "mysql_c46_cutover_already_attempted")
    stage.mkdir(mode=0o700)
    rollback_file = stage / "source-compose.json"
    write_private(rollback_file, {"services": {"mysql": {"image": SOURCE, "pull_policy": "never"}}})
    backup = None
    before = None
    stopped = []
    changed = False
    readonly = False
    writers_restarted = False
    try:
        for service in WRITERS:
            if service in writers:
                command(["docker", "stop", "--time", "400", writers[service]], timeout=430)
                stopped.append(service)
        require(all(inspect(writers[service]).get("State", {}).get("Running") is False for service in stopped),
                "mysql_c46_writer_fence_failed")
        sql("SET GLOBAL super_read_only=ON;", root=True)
        readonly = True
        require(sql("SELECT @@global.super_read_only;").strip() == b"1", "mysql_c46_readonly_fence_failed")
        before = snapshot()
        backup_output = command(["bash", str(backup_script), "create", MYSQL, str(stage), str(env_file), "mysql-c46"], timeout=900)
        backup = validate_backup_receipt(backup_output, stage)
        command(["bash", str(backup_script), "verify", backup["artifact"], backup["manifest"], str(env_file)], timeout=900)
        require(snapshot() == before, "mysql_c46_source_changed_during_backup")
        require((lock / "owner").read_text().strip() == args.lock_token, "mysql_c46_deploy_lock_changed")
        changed = True
        command([*compose, "up", "-d", "--no-deps", "--pull", "never", "--force-recreate", "mysql"], timeout=300)
        validate_mysql_container(mysql_health(), TARGET, target_image["Id"])
        require(snapshot() == before, "mysql_c46_data_or_flyway_changed")
        after = snapshot()
        for service in START_ORDER:
            if service in writers:
                writers_restarted = True
                command(["docker", "start", writers[service]])
                wait_writer(writers[service])
        record = {"schema": "otziv-mysql-c46-cutover-v1", "result": "PASS",
                  "mainRevision": args.main_revision, "source": SOURCE, "target": TARGET,
                  "sourceConfigId": source_image["Id"], "targetConfigId": target_image["Id"],
                  "volume": VOLUME, "before": before, "after": after, "backup": backup,
                  "publishedRehearsalSha256": REHEARSAL_SHA256, "productionRowsExported": False}
        write_private(stage / "cutover.json", record)
        print(json.dumps({"result": "PASS", "backupDirectory": str(stage), "tableCount": before["tableCount"],
                          "productionRowsExported": False}))
    except BaseException:
        try:
            # No writer may run while the database image is rolled back.
            for service in WRITERS:
                if service in writers and inspect(writers[service]).get("State", {}).get("Running") is True:
                    command(["docker", "stop", "--time", "400", writers[service]], timeout=430)
            if changed:
                # A resumed writer may have committed valid new work. Compare
                # immediately before/after rollback, never to the older backup.
                try:
                    rollback_before = snapshot()
                except (GuardError, subprocess.SubprocessError):
                    require(not writers_restarted and before is not None,
                            "mysql_c46_cannot_fingerprint_after_writer_restart")
                    rollback_before = before
                command([*compose, "-f", str(rollback_file), "up", "-d", "--no-deps", "--pull", "never", "--force-recreate", "mysql"], timeout=300)
                validate_mysql_container(mysql_health(), SOURCE, source_image["Id"])
                require(snapshot() == rollback_before, "mysql_c46_rollback_data_changed")
            elif readonly:
                sql("SET GLOBAL super_read_only=OFF;", root=True)
            for service in START_ORDER:
                if service in writers:
                    command(["docker", "start", writers[service]])
                    wait_writer(writers[service])
            write_private(stage / "rollback.json", {"result": "SOURCE_RESTORED", "source": SOURCE,
                                                    "volume": VOLUME, "backup": backup})
        except BaseException:
            write_private(stage / "manual-recovery-required", {"reason": "automatic_rollback_incomplete",
                                                              "source": SOURCE, "target": TARGET, "volume": VOLUME})
            raise GuardError("mysql_c46_manual_recovery_required_keep_lock_and_timer_paused")
        raise


def main():
    parser = argparse.ArgumentParser()
    for name in ("root", "env-file", "lock-token", "main-revision"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--explicit-reviewed-mysql-cutover", action="store_true", required=True)
    args = parser.parse_args()
    run_cutover(args)


if __name__ == "__main__":
    try:
        main()
    except (GuardError, OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        print("MySQL C46 coordinated cutover failed: " + str(exc), file=sys.stderr)
        raise SystemExit(1)
