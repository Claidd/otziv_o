"""Fail-closed tests for the reviewed C51 -> C54 PostgreSQL zlib refresh."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import coordinated_postgres_zlib_refresh as refresh  # noqa: E402
from database_image_guard import GuardError  # noqa: E402

ROOT = HERE.parents[2]
PREFIX = refresh.PREFIX
TOKEN = "a" * 32
REVISION = "d" * 40
VOLUME = "otziv-prod_keycloak_pg_data"
C45_KEYCLOAK = "ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def resolved_config():
    return {"name": refresh.PROJECT,
            "services": {refresh.PG: {"image": refresh.TARGET}, "keycloak": {"image": refresh.ISSUER}},
            "volumes": {refresh.VOLUME_KEY: {"name": VOLUME}}}


class Production:
    """Docker/Compose double for the protected production host; rows never leave it."""

    def __init__(self, root):
        self.root = root
        self.calls = []
        self.pg_image = refresh.SOURCE_CONFIG
        self.pg_reference = refresh.SOURCE
        self.pg_volume = VOLUME
        self.kc_running = True
        self.kc_image = refresh.ISSUER_CONFIG
        self.target_present = True
        self.timer = "inactive"
        self.records = {f"table_{n}": "a" * 64 for n in range(refresh.TABLE_COUNT)}
        self.records_after_target = None
        self.fail_compose = set()

    def run(self, args, timeout=180):
        self.calls.append(tuple(args))
        if args[:2] == ["sudo", "-n"]:
            return (self.timer + "\n").encode()
        if args[:2] == ["docker", "compose"]:
            if "config" in args:
                return json.dumps(resolved_config()).encode()
            rollback = args.count("-f") == 2
            if ("rollback" if rollback else "upgrade") in self.fail_compose:
                raise GuardError("postgres_c54_command_failed_docker")
            assert args[-1] == refresh.PG and "--no-deps" in args and args[args.index("--pull") + 1] == "never"
            if rollback:
                override = json.loads(Path(args[args.index("-f", args.index("-f") + 1) + 1]).read_text())
                assert override == {"services": {refresh.PG: {"image": refresh.SOURCE, "pull_policy": "never"}}}
                self.pg_image, self.pg_reference = refresh.SOURCE_CONFIG, refresh.SOURCE
            else:
                self.pg_image, self.pg_reference = refresh.TARGET_CONFIG, refresh.TARGET
                if self.records_after_target is not None:
                    self.records = self.records_after_target
            return b""
        if args[:2] == ["docker", "stop"]:
            assert args[-1] == refresh.KC
            self.kc_running = False
            return b""
        if args[:2] == ["docker", "start"]:
            assert args[-1] == refresh.KC
            self.kc_running = True
            return b""
        if args[:2] == ["docker", "pull"]:
            self.target_present = True
            return b""
        raise AssertionError("unexpected command: " + repr(args))

    def inspect(self, name, kind="container"):
        if kind == "image":
            identities = {refresh.TARGET: refresh.TARGET_CONFIG, refresh.SOURCE: refresh.SOURCE_CONFIG,
                          refresh.ISSUER: refresh.ISSUER_CONFIG}
            if name == refresh.TARGET and not self.target_present:
                raise GuardError("postgres_c54_command_failed_docker")
            return {"Id": identities[name], "RepoDigests": [name], "Os": "linux", "Architecture": "amd64"}
        if name == refresh.PG:
            return {"Name": "/" + refresh.PG, "Image": self.pg_image,
                    "Config": {"Image": self.pg_reference, "Labels": {
                        "com.docker.compose.project": refresh.PROJECT, "com.docker.compose.service": refresh.PG}},
                    "Mounts": [{"Type": "volume", "Name": self.pg_volume, "Destination": refresh.DATA_PATH, "RW": True}],
                    "HostConfig": {"PortBindings": {}},
                    "State": {"Status": "running", "Running": True, "Health": {"Status": "healthy"}}}
        assert name == refresh.KC
        status = "running" if self.kc_running else "exited"
        return {"Name": "/" + refresh.KC, "Image": self.kc_image,
                "Config": {"Labels": {"com.docker.compose.project": refresh.PROJECT,
                                      "com.docker.compose.service": "keycloak"}},
                "State": {"Status": status, "Running": self.kc_running,
                          "Health": {"Status": "healthy" if self.kc_running else "unhealthy"}}}

    def encrypted_backup(self, root, directory, env_file):
        self.calls.append(("encrypted_backup",))
        (directory / "keycloak.tar.gz.enc").write_bytes(b"cipher")
        return {"decryptVerified": True, "cipherSha256": digest(b"cipher")}

    def fingerprints(self):
        self.calls.append(("fingerprints", self.pg_image))
        return dict(self.records)

    def index(self, prefix):
        return next(i for i, call in enumerate(self.calls) if tuple(call[:len(prefix)]) == tuple(prefix))


class CoordinatedPostgresMinorRefreshTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / ".deploy.lock.d").mkdir()
        (self.root / ".deploy.lock.d/owner").write_text(TOKEN)
        (self.root / ".deploy-backups").mkdir()
        self.env = self.root / ".env"
        self.env.write_text("")
        self.host = Production(self.root)

    def tearDown(self):
        self.temporary.cleanup()

    def arguments(self):
        return type("Arguments", (), {"root": str(self.root), "env_file": str(self.env),
                                      "lock_token": TOKEN, "main_revision": REVISION})()

    def cutover(self):
        with patch.object(refresh, "run", side_effect=self.host.run), \
             patch.object(refresh, "inspect", side_effect=self.host.inspect), \
             patch.object(refresh, "healthy", side_effect=lambda name, attempts=150: self.host.inspect(name)), \
             patch.object(refresh, "server_version", side_effect=lambda: "170011" if self.host.pg_image == refresh.SOURCE_CONFIG else "170012"), \
             patch.object(refresh, "fingerprints", side_effect=self.host.fingerprints), \
             patch.object(refresh, "encrypted_backup", side_effect=self.host.encrypted_backup), \
             patch.object(refresh, "reviewed_plan") as plan, \
             patch.object(refresh, "checked_override") as guard:
            try:
                refresh.run_cutover(self.arguments())
            finally:
                self.plan, self.guard = plan, guard

    def stage(self):
        return self.root / ".deploy-backups" / ("postgres-c54-refresh-" + TOKEN)

    def stopped(self):
        return any(call[:2] == ("docker", "stop") for call in self.host.calls)

    def test_no_implicit_refresh(self):
        result = subprocess.run([sys.executable, "-B", str(HERE / "coordinated_postgres_zlib_refresh.py")],
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--explicit-reviewed-postgres-refresh", result.stderr)

    def test_guard_failure_reports_code_without_lock_token(self):
        (self.root / ".deploy.lock.d/owner").write_text("b" * 32)
        command = [sys.executable, "-B", str(HERE / "coordinated_postgres_zlib_refresh.py"), "--root", str(self.root),
                   "--env-file", str(self.env), "--lock-token", TOKEN, "--main-revision", REVISION,
                   "--explicit-reviewed-postgres-refresh"]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("postgres_c54_deploy_lock_changed", result.stderr)
        self.assertNotIn(TOKEN, result.stderr)
        self.assertNotIn("b" * 32, result.stderr)

    def test_repository_plan_binds_the_exact_c54_c48_evidence(self):
        entries = {entry["component"]: entry for entry in json.loads(
            (ROOT / PREFIX / "reviewed-image-activations.json").read_bytes())["images"]}
        publication = json.loads((ROOT / entries["postgres"]["publication"]["path"]).read_bytes())
        self.assertEqual((publication["reference"], publication["imageId"]), (refresh.TARGET, refresh.TARGET_CONFIG))
        self.assertEqual(digest((ROOT / refresh.PARENT_PATH).read_bytes()), refresh.PARENT_SHA256)
        with patch.object(refresh, "run") as docker:
            pg, kc, accepted, parent = refresh.reviewed_plan(ROOT, resolved_config())
            docker.assert_not_called()
        self.assertEqual((pg["reference"], kc["reference"], parent["reference"]),
                         (refresh.TARGET, refresh.ISSUER, refresh.SOURCE))
        self.assertEqual(accepted["pair"]["keycloak"]["imageConfigId"], refresh.ISSUER_CONFIG)

    def copied_evidence(self):
        root = self.root / "repository"
        paths = {PREFIX + "reviewed-image-activations.json", refresh.PARENT_PATH}
        entries = {entry["component"]: entry for entry in json.loads(
            (ROOT / PREFIX / "reviewed-image-activations.json").read_bytes())["images"]}
        for entry, key in ((entries["postgres"], "postgresC54Acceptance"), (entries["keycloak"], "aptRefreshAcceptance")):
            paths |= {entry["manifest"]["path"], entry[key]["path"]}
            record = json.loads((ROOT / entry[key]["path"]).read_bytes())
            paths |= set(record["files"]) | set(record.get("executedSources", {}))
        for path in paths:
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, root / path)
        self.assertEqual(refresh.reviewed_plan(root, resolved_config())[0]["reference"], refresh.TARGET)
        return root, entries

    def rejected(self, root, code, resolved=None):
        with patch.object(refresh, "run") as docker:
            with self.assertRaisesRegex(GuardError, "^" + code + "$"):
                refresh.reviewed_plan(root, resolved or resolved_config())
            docker.assert_not_called()

    def rewrite_index(self, root, component, change):
        path = root / PREFIX / "reviewed-image-activations.json"
        index = json.loads(path.read_bytes())
        change(next(entry for entry in index["images"] if entry["component"] == component))
        path.write_text(json.dumps(index))

    def test_earlier_or_other_generation_is_rejected_before_docker(self):
        root, entries = self.copied_evidence()
        parent = json.loads((ROOT / refresh.PARENT_PATH).read_bytes())
        self.rewrite_index(root, "postgres", lambda entry: (entry.clear(), entry.update(parent)))
        self.rejected(root, "postgres_c54_unreviewed_generation")
        self.rewrite_index(root, "postgres", lambda entry: (entry.clear(), entry.update(entries["postgres"])))
        self.rewrite_index(root, "keycloak", lambda entry: entry.update(reference=C45_KEYCLOAK))
        self.rejected(root, "postgres_c54_unreviewed_generation")

    def test_changed_evidence_parent_or_compose_is_rejected(self):
        root, _ = self.copied_evidence()
        resolved = resolved_config()
        resolved["services"]["keycloak"]["image"] = C45_KEYCLOAK
        self.rejected(root, "postgres_c54_compose_reference_changed", resolved)
        replay = root / refresh.REHEARSAL_PATHS["replayPath"]
        original = replay.read_bytes()
        replay.write_bytes(original + b" ")
        self.rejected(root, "postgres_c54_evidence_changed")
        replay.write_bytes(original)
        script = root / PREFIX / "postgres-c54-rehearsal.py"
        script.write_bytes(script.read_bytes() + b"\n")
        self.rejected(root, "postgres_c54_evidence_changed")
        shutil.copyfile(ROOT / PREFIX / "postgres-c54-rehearsal.py", script)
        (root / refresh.PARENT_PATH).write_text("{}")
        self.rejected(root, "postgres_c54_parent_changed")

    def test_unbound_transition_or_issuer_acceptance_is_rejected(self):
        root, _ = self.copied_evidence()
        self.rewrite_index(root, "postgres", lambda entry: entry.update(databaseTransition={"path": "x", "sha256": "0" * 64}))
        self.rejected(root, "postgres_c54_transition_binding")
        root, _ = self.copied_evidence()
        self.rewrite_index(root, "keycloak", lambda entry: entry.update(migrationAcceptance={"path": "x", "sha256": "0" * 64}))
        self.rejected(root, "postgres_c54_transition_binding")

    def test_refresh_backs_up_then_recreates_only_postgres_on_the_same_volume(self):
        self.cutover()
        calls = self.host.calls
        stop = self.host.index(("docker", "stop"))
        backup = self.host.index(("encrypted_backup",))
        upgrade = next(i for i, call in enumerate(calls) if call[:2] == ("docker", "compose") and "up" in call)
        start = self.host.index(("docker", "start"))
        self.assertLess(stop, backup)
        self.assertLess(backup, upgrade)
        self.assertLess(upgrade, start)
        self.assertEqual(calls[upgrade][-7:], ("up", "-d", "--no-deps", "--pull", "never", "--force-recreate", refresh.PG))
        self.assertFalse(any(call[:2] == ("docker", "pull") for call in calls))
        self.assertTrue(any(call == ("fingerprints", refresh.TARGET_CONFIG) for call in calls))
        self.assertTrue(self.host.kc_running)
        self.assertEqual(self.host.pg_image, refresh.TARGET_CONFIG)
        self.assertEqual(self.guard.call_args_list[0].args[0]["services"][refresh.PG]["image"], refresh.SOURCE)
        self.assertEqual(self.guard.call_args_list[-1].args[0]["services"][refresh.PG]["image"], refresh.TARGET)
        record = json.loads((self.stage() / "cutover.json").read_text())
        self.assertEqual((record["result"], record["tableCount"], record["volumeName"], record["productionRowsExported"]),
                         ("PASS", refresh.TABLE_COUNT, VOLUME, False))
        self.assertFalse((self.stage() / "rollback.json").exists())

    def test_missing_target_is_pulled_before_any_service_stops(self):
        self.host.target_present = False
        self.cutover()
        self.assertLess(self.host.index(("docker", "pull")), self.host.index(("docker", "stop")))

    def test_changed_records_restore_c51_on_the_same_volume(self):
        self.host.records_after_target = {**self.host.records, "table_0": "b" * 64}
        with self.assertRaisesRegex(GuardError, "^postgres_c54_records_changed$"):
            self.cutover()
        rollback = [call for call in self.host.calls if call[:2] == ("docker", "compose") and call.count("-f") == 2]
        self.assertEqual(len(rollback), 1)
        self.assertEqual(self.host.pg_image, refresh.SOURCE_CONFIG)
        self.assertTrue(self.host.kc_running)
        self.assertEqual(json.loads((self.stage() / "rollback.json").read_text())["result"], "SOURCE_RESTORED")
        self.assertFalse((self.stage() / "cutover.json").exists())

    def test_failed_issuer_restart_rolls_back_and_compares_current_rows(self):
        original = self.host.run

        def run(args, timeout=180):
            if args[:2] == ["docker", "start"] and self.host.pg_image == refresh.TARGET_CONFIG:
                self.host.kc_running = True
                self.host.records = {**self.host.records, "table_1": "c" * 64}  # a valid issuer write
                raise GuardError("postgres_c54_command_failed_docker")
            return original(args, timeout)
        self.host.run = run
        with self.assertRaisesRegex(GuardError, "^postgres_c54_command_failed_docker$"):
            self.cutover()
        self.assertEqual(self.host.pg_image, refresh.SOURCE_CONFIG)
        self.assertTrue(self.host.kc_running)
        stops = [i for i, call in enumerate(self.host.calls) if call[:2] == ("docker", "stop")]
        rollback = next(i for i, call in enumerate(self.host.calls) if call[:2] == ("docker", "compose") and call.count("-f") == 2)
        self.assertEqual(len(stops), 2)
        self.assertLess(stops[1], rollback)

    def test_failed_rollback_requires_manual_recovery_and_keeps_lock(self):
        self.host.fail_compose = {"upgrade", "rollback"}
        with self.assertRaisesRegex(GuardError, "^postgres_c54_manual_recovery_required_keep_lock_and_timer_paused$"):
            self.cutover()
        self.assertTrue((self.stage() / "manual-recovery-required").exists())
        self.assertEqual((self.root / ".deploy.lock.d/owner").read_text(), TOKEN)

    def test_failed_backup_restarts_issuer_without_touching_postgres(self):
        def broken(root, directory, env_file):
            raise GuardError("postgres_c54_backup_key_not_ready")
        self.host.encrypted_backup = broken
        with self.assertRaisesRegex(GuardError, "^postgres_c54_backup_key_not_ready$"):
            self.cutover()
        self.assertFalse(any(call[:2] == ("docker", "compose") and "up" in call for call in self.host.calls))
        self.assertTrue(self.host.kc_running)
        self.assertEqual(self.host.pg_image, refresh.SOURCE_CONFIG)

    def test_already_installed_target_is_verified_without_stopping_the_issuer(self):
        self.host.pg_image, self.host.pg_reference = refresh.TARGET_CONFIG, refresh.TARGET
        self.cutover()
        self.assertFalse(self.stopped())
        self.assertFalse(self.stage().exists())
        self.guard.assert_called_once()

    def test_wrong_issuer_volume_or_paused_state_is_refused_before_any_stop(self):
        for name, change, code in [
            ("issuer", lambda host: setattr(host, "kc_image", "sha256:" + "e" * 64), "postgres_c54_issuer_changed"),
            ("volume", lambda host: setattr(host, "pg_volume", "other"), "postgres_c54_data_volume_changed"),
            ("database", lambda host: setattr(host, "pg_image", "sha256:" + "f" * 64), "postgres_c54_container_identity_changed"),
            ("timer", lambda host: setattr(host, "timer", "active"), "postgres_c54_self_heal_not_paused"),
        ]:
            with self.subTest(name=name):
                self.host = Production(self.root)
                change(self.host)
                with self.assertRaisesRegex(GuardError, "^" + code + "$"):
                    self.cutover()
                self.assertFalse(self.stopped())
                self.assertFalse(self.stage().exists())

    def test_second_attempt_under_the_same_lock_is_refused(self):
        self.stage().mkdir()
        with self.assertRaisesRegex(GuardError, "^postgres_c54_refresh_already_attempted$"):
            self.cutover()
        self.assertFalse(self.stopped())

    def test_reused_backup_helpers_keep_their_format_with_this_procedure_prefix(self):
        with patch.object(refresh.ssl_refresh, "backup_key", side_effect=GuardError("coordinated_ssl_backup_key_not_ready")):
            with self.assertRaisesRegex(GuardError, "^postgres_c54_backup_key_not_ready$"):
                refresh.encrypted_backup(self.root, self.root, self.env)
        self.assertEqual(refresh.ssl_refresh.encrypted_backup.__module__, "coordinated_ssl_refresh")


if __name__ == "__main__":
    unittest.main()
