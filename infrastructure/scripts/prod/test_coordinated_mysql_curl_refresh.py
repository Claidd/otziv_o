"""Fail-closed tests for the reviewed C42 to C46 MySQL transition."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import coordinated_mysql_curl_refresh as cutover
from database_image_guard import GuardError


ROOT = Path(__file__).resolve().parents[3]
TEMP_ROOT = ROOT / ".codex-tmp"


def digest(data):
    return hashlib.sha256(data).hexdigest()


class CoordinatedMysqlCurlRefreshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.proof = json.loads((ROOT / cutover.REHEARSAL_PATH).read_bytes())

    def test_final_published_digest_rehearsal_is_exact(self):
        proof_bytes = (ROOT / cutover.REHEARSAL_PATH).read_bytes()
        self.assertEqual(digest(proof_bytes), cutover.REHEARSAL_SHA256)
        self.assertEqual(digest((ROOT / cutover.REHEARSAL_SCRIPT).read_bytes()),
                         self.proof["executedSourceSha256"])
        cutover.validate_rehearsal(self.proof)

    def test_preliminary_or_missing_rollback_is_rejected(self):
        bad = copy.deepcopy(self.proof)
        bad["preliminary"] = True
        with self.assertRaises(GuardError):
            cutover.validate_rehearsal(bad)
        bad = copy.deepcopy(self.proof)
        bad["checks"] = [item for item in bad["checks"]
                         if item["name"] != "same_volume_rollback_preserves_all_new_writes"]
        with self.assertRaises(GuardError):
            cutover.validate_rehearsal(bad)

    def test_source_compose_is_exact_c42_mysql_and_preserved_c36_postgres(self):
        config = {"name": cutover.PROJECT, "services": {
            "mysql": {"image": cutover.TARGET},
            "keycloak-postgres": {"image": cutover.TARGET_PG}}}
        original = copy.deepcopy(config)
        source = cutover.validate_resolved(config)
        self.assertEqual(config, original)
        self.assertEqual(source["services"]["mysql"]["image"], cutover.SOURCE)
        self.assertEqual(source["services"]["keycloak-postgres"]["image"], cutover.SOURCE_PG)
        self.assertEqual(cutover.SOURCE_PG, cutover.TARGET_PG)
        bad = copy.deepcopy(config)
        bad["services"]["keycloak-postgres"]["image"] = "postgres:unreviewed"
        with self.assertRaises(GuardError):
            cutover.validate_resolved(bad)
        config["services"]["mysql"]["ports"] = ["3306:3306"]
        with self.assertRaises(GuardError):
            cutover.validate_resolved(config)

    def test_mysql_identity_rejects_wrong_volume_or_image(self):
        good = {"Name": "/my-mysql", "Image": cutover.SOURCE_CONFIG,
                "Config": {"Image": cutover.SOURCE, "Labels": {
                    "com.docker.compose.project": cutover.PROJECT,
                    "com.docker.compose.service": "mysql"}},
                "Mounts": [{"Destination": "/var/lib/mysql", "Type": "volume",
                            "Name": cutover.VOLUME, "RW": True}],
                "HostConfig": {"PortBindings": None},
                "State": {"Status": "running", "Health": {"Status": "healthy"}}}
        cutover.validate_mysql_container(good, cutover.SOURCE, cutover.SOURCE_CONFIG)
        wrong = copy.deepcopy(good)
        wrong["Mounts"][0]["Name"] = "other-volume"
        with self.assertRaises(GuardError):
            cutover.validate_mysql_container(wrong, cutover.SOURCE, cutover.SOURCE_CONFIG)
        wrong = copy.deepcopy(good)
        wrong["Image"] = cutover.TARGET_CONFIG
        with self.assertRaises(GuardError):
            cutover.validate_mysql_container(wrong, cutover.SOURCE, cutover.SOURCE_CONFIG)

    def test_backup_receipt_requires_exact_private_artifact(self):
        TEMP_ROOT.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="mysql-c46-test-", dir=TEMP_ROOT) as temporary:
            directory = Path(temporary)
            artifact = directory / "pre-deploy-mysql-c46-checked.sql.gz.enc"
            artifact.write_bytes(b"encrypted-fixture")
            (directory / (artifact.name + ".manifest")).write_text("fixture")
            lines = {"OTZIV_PREDEPLOY_BACKUP_ARTIFACT": str(artifact),
                     "OTZIV_PREDEPLOY_BACKUP_MANIFEST": str(artifact) + ".manifest",
                     "OTZIV_PREDEPLOY_BACKUP_SHA256": digest(artifact.read_bytes()).upper(),
                     "OTZIV_PREDEPLOY_BACKUP_HMAC_SHA256": "A" * 64,
                     "OTZIV_PREDEPLOY_BACKUP_BYTES": str(artifact.stat().st_size),
                     "OTZIV_PREDEPLOY_BACKUP_FLYWAY_FINGERPRINT": "B" * 64}
            output = "\n".join(f"{key}={value}" for key, value in lines.items()).encode()
            self.assertEqual(cutover.validate_backup_receipt(output, directory)["artifact"], str(artifact))
            artifact.write_bytes(b"changed")
            with self.assertRaises(GuardError):
                cutover.validate_backup_receipt(output, directory)

    def test_snapshot_requires_row_checksums_for_every_table(self):
        tables = b"\n".join(f"table_{i}".encode() for i in range(101)) + b"\n"
        checksums = b"\n".join(f"db.table_{i}\t{i}".encode() for i in range(101)) + b"\n"
        responses = [tables, checksums, b"schema", b"flyway", b"accounts"]
        with patch.object(cutover, "sql", side_effect=responses) as query:
            result = cutover.snapshot()
        self.assertEqual(result["tableDataSha256"], digest(checksums))
        self.assertIn("EXTENDED", query.call_args_list[1].args[0])
        self.assertEqual(query.call_args_list[1].kwargs["timeout"], 900)
        responses[1] = b"db.table_0\tNULL\n"
        with patch.object(cutover, "sql", side_effect=responses):
            with self.assertRaises(GuardError):
                cutover.snapshot()

    def test_activation_requires_hash_bound_c46_evidence(self):
        expected = {
            "manifest": "infrastructure/runtime-security/reviewed-images-c46-mysql.json",
            "publication": "infrastructure/runtime-security/proofs/c46-mysql/publication/publication.json",
            "anonymous": "infrastructure/runtime-security/proofs/c46-mysql/anonymous/anonymous-download.json",
            "databaseTransition": cutover.REHEARSAL_PATH,
        }
        TEMP_ROOT.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="mysql-c46-test-", dir=TEMP_ROOT) as temporary:
            root = Path(temporary)
            entry = {"component": "mysql", "reference": cutover.TARGET}
            for key, path in expected.items():
                destination = root / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                data = (ROOT / path).read_bytes()
                destination.write_bytes(data)
                entry[key] = {"path": path, "sha256": digest(data)}
            index = root / "infrastructure/runtime-security/reviewed-image-activations.json"
            index.write_text(json.dumps({"images": [entry]}))
            cutover.validate_activation(root)
            (root / cutover.REHEARSAL_PATH).write_bytes(b"changed")
            with self.assertRaises(GuardError):
                cutover.validate_activation(root)


if __name__ == "__main__":
    unittest.main()
