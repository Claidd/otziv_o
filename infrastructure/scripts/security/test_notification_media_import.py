from __future__ import annotations

import hashlib
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = (
    REPOSITORY_ROOT
    / "generated-assets"
    / "notification-media-v2"
    / "import_to_production.py"
)
SPEC = importlib.util.spec_from_file_location("notification_media_import", MODULE_PATH)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import bootstrap guard
    raise RuntimeError(f"Cannot load {MODULE_PATH}")
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


class NotificationMediaImportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.env = {
            "S3_ACCESS_KEY": "test-access",
            "S3_SECRET_KEY": "test-secret",
            "S3_BUCKET": "test-bucket",
            "S3_ENDPOINT": "https://s3.invalid",
            "S3_REGION": "test-region",
            "S3_PUBLIC_BASE_URL": "https://cdn.invalid",
        }

    @staticmethod
    def object_info(
        key: str,
        data: bytes,
        *,
        import_id: str | None = "another-run",
    ):
        return IMPORTER.S3ObjectInfo(
            key=key,
            sha256=hashlib.sha256(data).hexdigest(),
            import_id=import_id,
            etag='"etag"',
            size=len(data),
        )

    def test_matching_existing_object_is_reused_without_put(self) -> None:
        data = b"same-object"
        key = "notification-media/event/prefix/01.png"
        existing = self.object_info(key, data)
        with (
            mock.patch.object(IMPORTER, "head_s3", return_value=existing),
            mock.patch.object(IMPORTER, "upload_s3") as upload,
        ):
            result = IMPORTER.upload_with_retry(
                data, key, "image/png", self.env, "current-run"
            )
        self.assertIsNone(result)
        upload.assert_not_called()

    def test_existing_object_without_matching_digest_is_never_overwritten(self) -> None:
        data = b"wanted-object"
        key = "notification-media/event/prefix/01.png"
        existing = self.object_info(key, b"foreign-object")
        with (
            mock.patch.object(IMPORTER, "head_s3", return_value=existing),
            mock.patch.object(IMPORTER, "upload_s3") as upload,
        ):
            with self.assertRaisesRegex(RuntimeError, "Refusing to overwrite"):
                IMPORTER.upload_with_retry(
                    data, key, "image/png", self.env, "current-run"
                )
        upload.assert_not_called()

    def test_put_is_conditional_and_records_digest_and_owner(self) -> None:
        data = b"new-object"
        digest = hashlib.sha256(data).hexdigest()
        with mock.patch.object(
            IMPORTER,
            "signed_s3_request",
            return_value=(200, {"etag": '"etag"'}),
        ) as request:
            IMPORTER.upload_s3(
                data,
                "notification-media/event/prefix/01.png",
                "image/png",
                self.env,
                "current-run",
            )
        headers = request.call_args.kwargs["headers"]
        self.assertEqual("*", headers["if-none-match"])
        self.assertEqual(digest, headers["x-amz-meta-sha256"])
        self.assertEqual("current-run", headers["x-amz-meta-import-id"])

    def test_successful_put_is_reported_as_new_before_database_work(self) -> None:
        data = b"new-object"
        key = "notification-media/event/prefix/01.png"
        created = self.object_info(key, data, import_id="current-run")
        tracker = mock.Mock()
        with (
            mock.patch.object(IMPORTER, "head_s3", side_effect=[None, created]),
            mock.patch.object(
                IMPORTER, "upload_s3", return_value={"etag": '"etag"'}
            ),
        ):
            result = IMPORTER.upload_with_retry(
                data,
                key,
                "image/png",
                self.env,
                "current-run",
                on_created=tracker,
            )
        self.assertEqual(created, result)
        tracker.assert_called_once()
        self.assertEqual(key, tracker.call_args.args[0].key)

    def test_precommit_database_failure_rolls_back_only_new_uploads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "01.png"
            image.write_bytes(b"image")
            rows = [self.row(image)]
            created = self.object_info(
                "notification-media/event/prefix/01.png",
                b"image",
                import_id="current-run",
            )
            with (
                mock.patch.object(IMPORTER.uuid, "uuid4") as uuid4,
                mock.patch.object(
                    IMPORTER, "upload_with_retry", return_value=created
                ),
                mock.patch.object(
                    IMPORTER,
                    "apply_database_changes",
                    side_effect=RuntimeError("transaction failed"),
                ),
                mock.patch.object(IMPORTER, "cleanup_uploaded") as cleanup,
            ):
                uuid4.return_value.hex = "current-run"
                with self.assertRaisesRegex(RuntimeError, "transaction failed"):
                    IMPORTER.run_import(rows, self.env, "prefix")
        cleanup.assert_called_once_with([created], self.env)

    def test_postcommit_verification_failure_does_not_remove_objects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "01.png"
            image.write_bytes(b"image")
            rows = [self.row(image)]
            created = self.object_info(
                "notification-media/event/prefix/01.png",
                b"image",
                import_id="current-run",
            )
            with (
                mock.patch.object(IMPORTER.uuid, "uuid4") as uuid4,
                mock.patch.object(
                    IMPORTER, "upload_with_retry", return_value=created
                ),
                mock.patch.object(IMPORTER, "apply_database_changes"),
                mock.patch.object(
                    IMPORTER,
                    "verify_database",
                    side_effect=RuntimeError("post-commit check failed"),
                ),
                mock.patch.object(IMPORTER, "verify_public_assets"),
                mock.patch.object(IMPORTER, "cleanup_uploaded") as cleanup,
            ):
                uuid4.return_value.hex = "current-run"
                with self.assertRaisesRegex(RuntimeError, "post-commit check failed"):
                    IMPORTER.run_import(rows, self.env, "prefix")
        cleanup.assert_not_called()

    def test_database_assertions_run_before_commit_in_same_mysql_call(self) -> None:
        rows = [self.row(Path("unused.png"))]
        with mock.patch.object(
            IMPORTER, "mysql", return_value="IMPORT_COMMIT_OK"
        ) as mysql:
            IMPORTER.apply_database_changes(rows, self.env, "prefix")
        mysql.assert_called_once()
        sql = mysql.call_args.args[0]
        self.assertLess(sql.index("START TRANSACTION;"), sql.index("CREATE TEMPORARY TABLE"))
        self.assertLess(sql.index("asset-count:EVENT:MANAGER"), sql.index("COMMIT;"))
        self.assertLess(sql.index("asset:EVENT:MANAGER:01.png"), sql.index("COMMIT;"))
        self.assertLess(sql.index("COMMIT;"), sql.index("IMPORT_COMMIT_OK"))

    @staticmethod
    def row(path: Path) -> dict[str, str]:
        return {
            "directory": "event",
            "event_code": "EVENT",
            "recipient_type": "MANAGER",
            "file_name": "01.png",
            "original_filename": "01.png",
            "content_type": "image/png",
            "path": str(path),
        }


if __name__ == "__main__":
    unittest.main()
