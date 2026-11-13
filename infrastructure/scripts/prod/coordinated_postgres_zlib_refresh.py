"""Explicit reviewed C51 -> C54 PostgreSQL 17.12 refresh under the production deploy lock.

The ordinary database_image_guard stays authoritative for every ordinary deploy.
This procedure runs only when the release caller selects it, after the mandatory
main DB backup and while the self-heal timer is paused. The active C48 Keycloak is
unchanged. It writes an additional encrypted PostgreSQL backup with the same key
and format as the SSL refresh, keeps every row on the VPS, recreates only
keycloak-postgres on the same volume, and restores C51 through a checked override
if any step fails. It never resumes self-heal or releases the deploy lock.
"""
import argparse
import contextlib
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
# The backup format, evidence reader and table fingerprints stay identical to the
# reviewed SSL procedure; that module's behaviour is reused, never changed here.
import coordinated_ssl_refresh as ssl_refresh


PROJECT = "otziv-prod"
PG = "keycloak-postgres"
KC = "otziv-prod-keycloak-1"
DATA_PATH = "/var/lib/postgresql/data"
VOLUME_KEY = "keycloak_pg_data"
SOURCE = "ghcr.io/claidd/otziv-security@sha256:13d2e1ba01b593f1f284d5d7869a9994f5251f036355e90064406ca4e7d809fc"
SOURCE_CONFIG = "sha256:ceffd72a35bc4a20a9588f71deaf98cc145ba6832104a8b539a63395ce341975"
TARGET = "ghcr.io/claidd/otziv-security@sha256:32adf590dacac8d07c6dcd5bfbad27d527ae2ddf18e9822a404ba52ce69524a2"
TARGET_CONFIG = "sha256:71222076c468b230cd512a77e23c71bf3910afc46f40b4f68806808dac291549"
ISSUER = "ghcr.io/claidd/otziv-security@sha256:2e935efdb594ee4a3f0bc8343b1e6a76def33d70a4981cbd730ce5942ff47009"
ISSUER_CONFIG = "sha256:09efb3a918669b335b30966deeea3027591e03c2e048e653c0cb7cad8a8ba773"
ISSUER_PARENT = "ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e"
PREFIX = "infrastructure/runtime-security/"
PARENT_PATH = PREFIX + "c54-parent-postgres.json"
PARENT_SHA256 = "aac6a5b31229098b7151a1f0b6bb2d6a46e8a18034a20d6645e5a59b2e34f954"
C51_MANIFEST = PREFIX + "reviewed-images-c51-postgres.json"
C54_MANIFEST = PREFIX + "reviewed-images-c54-postgres.json"
C48_MANIFEST = PREFIX + "reviewed-images-c48-keycloak.json"
PROOF = PREFIX + "proofs/c54-postgres/"
ACCEPTANCE_PATH = PROOF + "acceptance.json"
REHEARSAL_PATHS = {"capturePath": PROOF + "rehearsal/capture.json", "replayPath": PROOF + "rehearsal/replay.json",
                   "captureWindowPath": PROOF + "rehearsal/capture-window.json"}
EXECUTED_SOURCES = tuple(PREFIX + name for name in (
    "postgres-c54-inspection.py", "ssl-refresh-inspection.py", "postgres-c54-adjudication.mjs",
    "postgres-c54-rehearsal.py", "postgres-c54-capture-window.py", "builds/postgres-17.12/Dockerfile",
    "builds/postgres-17.12/build.sh", "builds/postgres-17.12/package.py", "builds/postgres-17.12/kerberos.py"))
REQUIRED_REHEARSAL_CHECKS = (
    "restored_critical_records_exact", "capture_stable_tables_restored_exact",
    "same_volume_all_tables_exact", "existing_admin_password_login",
    "existing_roles_and_credentials_preserved", "unauthenticated_provider_denied",
    "same_volume_rollback_preserves_all_post_upgrade_writes",
    "same_volume_old_issuer_login", "same_volume_old_issuer_credentials_preserved",
    "fresh_rollback_restore_preserves_all_post_upgrade_writes",
    "restored_existing_password_login", "restored_roles_and_credentials_preserved",
    "library_patch_no_schema_migration", "server_version_upgrade", "postgres_indexes_valid",
)
TABLE_COUNT = 102


def require(condition, code):
    if not condition:
        raise GuardError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def native(reference):
    return "sha256:" + reference.split("@sha256:", 1)[1]


@contextlib.contextmanager
def translated():
    # Reused SSL helpers report coordinated_ssl_* codes; keep this procedure's own prefix.
    try:
        yield
    except GuardError as error:
        raise GuardError("postgres_c54_" + str(error).removeprefix("coordinated_ssl_")) from error


def run(args, timeout=180):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, check=False)
    require(result.returncode == 0, "postgres_c54_command_failed_" + Path(args[0]).name)
    return result.stdout


def inspect(name, kind="container"):
    values = json.loads(run(["docker", kind, "inspect", name]))
    require(isinstance(values, list) and len(values) == 1, "postgres_c54_ambiguous_docker_identity")
    return values[0]


def ensure_image_present(reference):
    try:
        inspect(reference, "image")
    except GuardError:
        run(["docker", "pull", "--platform", "linux/amd64", reference], timeout=600)


def healthy(name, attempts=150):
    for _ in range(attempts):
        meta = inspect(name)
        if meta.get("State", {}).get("Health", {}).get("Status") == "healthy":
            return meta
        require(meta.get("State", {}).get("Status") == "running", "postgres_c54_container_stopped")
        time.sleep(4)
    raise GuardError("postgres_c54_health_timeout")


def server_version():
    with translated():
        return ssl_refresh.sql("SHOW server_version_num").decode().strip()


def fingerprints():
    with translated():
        return ssl_refresh.table_fingerprints()


def encrypted_backup(root, directory, env_file):
    with translated():
        return ssl_refresh.encrypted_backup(root, directory, env_file)


def validate_evidence(root, path, sha):
    with translated():
        ssl_refresh.validate_evidence(root, path, sha)


def proof(root, reference):
    with translated():
        return ssl_refresh.proof(root, reference)


def write_private(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
    os.chmod(path, 0o600)


def reviewed_plan(root, resolved):
    parent_path = root / PARENT_PATH
    require(parent_path.is_file() and not parent_path.is_symlink()
            and digest(parent_path.read_bytes()) == PARENT_SHA256, "postgres_c54_parent_changed")
    parent = json.loads(parent_path.read_bytes())
    require(parent.get("component") == "postgres" and parent.get("reference") == SOURCE
            and parent.get("manifest", {}).get("path") == C51_MANIFEST, "postgres_c54_parent_identity")
    index = json.loads((root / PREFIX / "reviewed-image-activations.json").read_bytes())
    entries = {}
    for entry in index.get("images", []):
        require(entry.get("component") not in entries, "postgres_c54_activation_ambiguous")
        entries[entry.get("component")] = entry
    pg, kc = entries.get("postgres", {}), entries.get("keycloak", {})
    require(pg.get("manifest", {}).get("path") == C54_MANIFEST and pg.get("reference") == TARGET
            and kc.get("manifest", {}).get("path") == C48_MANIFEST and kc.get("reference") == ISSUER,
            "postgres_c54_unreviewed_generation")
    for entry in (pg, kc):
        manifest = (root / entry["manifest"]["path"]).resolve()
        require(manifest.is_relative_to(root) and manifest.is_file()
                and digest(manifest.read_bytes()) == entry["manifest"].get("sha256"), "postgres_c54_manifest_changed")
    require(pg.get("postgresC54Acceptance") and pg.get("databaseTransition") == pg.get("postgresC54Acceptance")
            and pg["postgresC54Acceptance"].get("path") == ACCEPTANCE_PATH
            and kc.get("aptRefreshAcceptance") and kc.get("migrationAcceptance") == kc.get("aptRefreshAcceptance"),
            "postgres_c54_transition_binding")
    accepted = proof(root, pg["postgresC54Acceptance"])
    issuer = proof(root, kc["aptRefreshAcceptance"])
    require(accepted.get("schema") == "otziv-postgres-c54-acceptance-v1" and accepted.get("result") == "PASS"
            and accepted.get("reference") == TARGET and accepted.get("imageConfigId") == TARGET_CONFIG
            and accepted.get("parentReference") == SOURCE and accepted.get("parentConfigId") == SOURCE_CONFIG
            and all(accepted.get(key) == pg.get(key) for key in ("commit", "run", "attempt")),
            "postgres_c54_acceptance_identity")
    require(accepted.get("pair") == {"postgres": {"reference": TARGET, "imageConfigId": TARGET_CONFIG},
                                     "keycloak": {"reference": ISSUER, "imageConfigId": ISSUER_CONFIG}},
            "postgres_c54_pair_references")
    require(issuer.get("schema") == "otziv-apt-c48-acceptance-v1" and issuer.get("result") == "PASS"
            and issuer.get("component") == "keycloak" and issuer.get("reference") == ISSUER
            and issuer.get("imageConfigId") == ISSUER_CONFIG and issuer.get("parentReference") == ISSUER_PARENT
            and all(issuer.get(key) == kc.get(key) for key in ("commit", "run", "attempt")),
            "postgres_c54_issuer_identity")
    files, sources = accepted.get("files"), accepted.get("executedSources")
    require(isinstance(files, dict) and len(files) >= 8 and isinstance(sources, dict)
            and all(path in sources for path in EXECUTED_SOURCES)
            and all(accepted.get(key) == path and path in files for key, path in REHEARSAL_PATHS.items()),
            "postgres_c54_acceptance_incomplete")
    require(isinstance(issuer.get("files"), dict) and issuer["files"], "postgres_c54_issuer_incomplete")
    for path, sha in {**files, **sources, **issuer["files"]}.items():
        validate_evidence(root, path, sha)
    capture = json.loads((root / accepted["capturePath"]).read_bytes())
    replay = json.loads((root / accepted["replayPath"]).read_bytes())
    for record in (capture, replay):
        require(record.get("schema") == "otziv-postgres-c54-rehearsal-v1" and record.get("result") == "PASS"
                and record.get("sourcePostgres") == SOURCE and record.get("sourcePostgresConfig") == SOURCE_CONFIG
                and record.get("keycloak") == ISSUER and record.get("keycloakConfig") == ISSUER_CONFIG
                and record.get("productionWrites") is False and record.get("publishedPorts") == 0
                and record.get("privateRowsPublished") is False
                and record.get("sourceScope") == "LOCAL_PROD_LIKE_KEYCLOAK"
                and record.get("executedScriptSha256") == sources[PREFIX + "postgres-c54-rehearsal.py"],
                "postgres_c54_rehearsal_identity")
    require(replay.get("candidatePostgres") == TARGET
            and replay.get("candidatePostgresLocalId") in (TARGET_CONFIG, native(TARGET))
            and replay.get("candidate") == ISSUER and replay.get("candidateLocalId") in (ISSUER_CONFIG, native(ISSUER))
            and replay.get("sourceDatabase", {}).get("serverVersionNumber") == "170011"
            and replay.get("candidateDatabase", {}).get("serverVersionNumber") == "170012"
            and replay.get("tableCount") == TABLE_COUNT and replay.get("dumpSha256") == capture.get("dumpSha256")
            and replay.get("ownedResourcesRemaining") == {"container": 0, "volume": 0, "network": 0}
            and not replay.get("cleanupErrors"), "postgres_c54_rehearsal_incomplete")
    checks = replay.get("checks", [])
    require(isinstance(checks, list) and checks and all(check.get("passed") is True for check in checks)
            and all(sum(check.get("name") == name for check in checks) == 1 for name in REQUIRED_REHEARSAL_CHECKS),
            "postgres_c54_rehearsal_checks_missing")
    services = resolved.get("services", {})
    require(services.get(PG, {}).get("image") == TARGET and services.get("keycloak", {}).get("image") == ISSUER,
            "postgres_c54_compose_reference_changed")
    return pg, kc, accepted, parent


def validate_postgres_container(meta, reference, image_id, volume_name):
    labels = meta.get("Config", {}).get("Labels", {})
    mounts = [mount for mount in meta.get("Mounts", []) if mount.get("Destination") == DATA_PATH]
    require(meta.get("Name") == "/" + PG and meta.get("Image") == image_id
            and meta.get("Config", {}).get("Image") == reference
            and labels.get("com.docker.compose.project") == PROJECT
            and labels.get("com.docker.compose.service") == PG
            and str(labels.get("com.docker.compose.oneoff", "False")).lower() == "false",
            "postgres_c54_container_identity_changed")
    require(len(mounts) == 1 and mounts[0].get("Type") == "volume" and mounts[0].get("Name") == volume_name
            and mounts[0].get("RW") is True and not mounts[0].get("SubPath"), "postgres_c54_data_volume_changed")
    require(meta.get("State", {}).get("Status") == "running"
            and meta.get("State", {}).get("Health", {}).get("Status") == "healthy", "postgres_c54_database_not_healthy")
    require(not meta.get("HostConfig", {}).get("PortBindings"), "postgres_c54_public_database_port")


def validate_issuer(meta, image_id):
    labels = meta.get("Config", {}).get("Labels", {})
    require(meta.get("Name") == "/" + KC and meta.get("Image") == image_id
            and labels.get("com.docker.compose.project") == PROJECT
            and labels.get("com.docker.compose.service") == "keycloak", "postgres_c54_issuer_changed")


def self_heal_paused():
    for unit in ("otziv-prod-up.timer", "otziv-prod-up.service"):
        state = run(["sudo", "-n", "systemctl", "show", unit, "--property=ActiveState", "--value"]).decode().strip()
        require(state == "inactive", "postgres_c54_self_heal_not_paused")


def lock_owned(lock, token):
    require(lock.is_dir() and not lock.is_symlink() and (lock / "owner").read_text().strip() == token,
            "postgres_c54_deploy_lock_changed")


def run_cutover(args):
    root = Path(args.root).resolve(strict=True)
    env_file = Path(args.env_file).resolve(strict=True)
    require(re.fullmatch("[a-f0-9]{40}", args.main_revision) and re.fullmatch("[a-f0-9]{32}", args.lock_token),
            "postgres_c54_invalid_release_identity")
    require(env_file.is_relative_to(root), "postgres_c54_env_path")
    lock = root / ".deploy.lock.d"
    lock_owned(lock, args.lock_token)
    self_heal_paused()
    compose = ["docker", "compose", "--project-name", PROJECT, "--project-directory", str(root),
               "-f", str(root / "docker-compose.yaml"), "--env-file", str(env_file)]
    resolved = json.loads(run([*compose, "config", "--format", "json"]))
    require(resolved.get("name") == PROJECT, "postgres_c54_wrong_project")
    reviewed_plan(root, resolved)
    volume = resolved.get("volumes", {}).get(VOLUME_KEY, {}).get("name")
    require(isinstance(volume, str) and volume, "postgres_c54_volume_unresolved")
    # Download the reviewed immutable image before any service stops.
    ensure_image_present(TARGET)
    target = inspect(TARGET, "image")
    source = inspect(SOURCE, "image")
    issuer_image = inspect(ISSUER, "image")
    require(target.get("Id") in (TARGET_CONFIG, native(TARGET)) and TARGET in target.get("RepoDigests", [])
            and target.get("Os") == "linux" and target.get("Architecture") == "amd64",
            "postgres_c54_candidate_image_not_pulled_or_wrong")
    require(source.get("Id") in (SOURCE_CONFIG, native(SOURCE)), "postgres_c54_source_image_changed")
    require(issuer_image.get("Id") in (ISSUER_CONFIG, native(ISSUER)), "postgres_c54_issuer_image_changed")
    issuer = inspect(KC)
    validate_issuer(issuer, issuer_image["Id"])
    require(issuer.get("State", {}).get("Status") == "running"
            and issuer.get("State", {}).get("Health", {}).get("Status") == "healthy", "postgres_c54_issuer_not_healthy")
    old = inspect(PG)
    if old.get("Image") == target["Id"]:
        validate_postgres_container(old, TARGET, target["Id"], volume)
        checked_override(resolved, DockerReadOnly())
        print(json.dumps({"result": "ALREADY_INSTALLED", "productionRowsExported": False}))
        return
    validate_postgres_container(old, SOURCE, source["Id"], volume)
    source_config = copy.deepcopy(resolved)
    source_config["services"][PG]["image"] = SOURCE
    # The ordinary guard verifies the existing C51 launch, project and volume first.
    checked_override(source_config, DockerReadOnly())
    base = root / ".deploy-backups"
    require(base.is_dir() and not base.is_symlink(), "postgres_c54_backup_directory_missing")
    stage = base / ("postgres-c54-refresh-" + args.lock_token)
    require(not stage.exists(), "postgres_c54_refresh_already_attempted")
    stage.mkdir(mode=0o700)
    override = stage / "rollback-compose.json"
    write_private(override, {"services": {PG: {"image": SOURCE, "pull_policy": "never"}}})
    before = backup = None
    changed = issuer_started = False
    try:
        run(["docker", "stop", "--time", "30", KC])
        require(inspect(KC).get("State", {}).get("Running") is False, "postgres_c54_issuer_fence_failed")
        healthy(PG)
        require(server_version() == "170011", "postgres_c54_source_version")
        before = fingerprints()
        backup = encrypted_backup(root, stage, env_file)
        require(fingerprints() == before, "postgres_c54_source_changed_during_backup")
        lock_owned(lock, args.lock_token)
        changed = True
        run([*compose, "up", "-d", "--no-deps", "--pull", "never", "--force-recreate", PG], timeout=300)
        validate_postgres_container(healthy(PG), TARGET, target["Id"], volume)
        require(server_version() == "170012", "postgres_c54_target_version")
        require(fingerprints() == before, "postgres_c54_records_changed")
        checked_override(resolved, DockerReadOnly())
        issuer_started = True
        run(["docker", "start", KC])
        validate_issuer(healthy(KC), issuer_image["Id"])
        record = {"schema": "otziv-coordinated-postgres-c54-refresh-v1", "result": "PASS",
                  "mainRevision": args.main_revision, "source": SOURCE, "target": TARGET,
                  "sourceConfigId": source["Id"], "targetConfigId": target["Id"], "keycloak": ISSUER,
                  "volumeName": volume, "tableCount": len(before), "recordsPreserved": True,
                  "recordsDigest": digest(json.dumps(before, sort_keys=True).encode()),
                  "productionRowsExported": False, "backup": backup}
        write_private(stage / "cutover.json", record)
        print(json.dumps({"result": "PASS", "tablesPreserved": len(before), "productionRowsExported": False,
                          "backupDirectory": str(stage)}))
    except BaseException:
        try:
            # No issuer may write while the database image is rolled back.
            if inspect(KC).get("State", {}).get("Running") is True:
                run(["docker", "stop", "--time", "30", KC])
            if changed:
                # A restarted issuer may have written valid sessions. Compare right
                # before and after the rollback, never against the older snapshot.
                try:
                    rollback_before = fingerprints()
                except (GuardError, subprocess.SubprocessError):
                    require(not issuer_started and before is not None, "postgres_c54_cannot_fingerprint_after_issuer_restart")
                    rollback_before = before
                run([*compose, "-f", str(override), "up", "-d", "--no-deps", "--pull", "never", "--force-recreate", PG],
                    timeout=300)
                validate_postgres_container(healthy(PG), SOURCE, source["Id"], volume)
                require(fingerprints() == rollback_before, "postgres_c54_rollback_records_changed")
            run(["docker", "start", KC])
            validate_issuer(healthy(KC), issuer_image["Id"])
            write_private(stage / "rollback.json", {"result": "SOURCE_RESTORED", "source": SOURCE, "target": TARGET,
                                                     "volumeName": volume, "backup": backup})
        except BaseException:
            write_private(stage / "manual-recovery-required", {"reason": "automatic_rollback_incomplete",
                                                                "source": SOURCE, "target": TARGET, "volumeName": volume})
            raise GuardError("postgres_c54_manual_recovery_required_keep_lock_and_timer_paused")
        raise


def main():
    parser = argparse.ArgumentParser()
    for name in ("root", "env-file", "lock-token", "main-revision"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--explicit-reviewed-postgres-refresh", action="store_true", required=True)
    run_cutover(parser.parse_args())


if __name__ == "__main__":
    try:
        main()
    except GuardError as exc:
        print("PostgreSQL C54 coordinated refresh failed: " + str(exc), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        print("PostgreSQL C54 coordinated refresh failed; inspect the protected server evidence.", file=sys.stderr)
        raise SystemExit(1)
