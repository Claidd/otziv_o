import copy
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from ci_image_bundle import digest, export_image, verify_bundle
from release_registry import upload_location


def add(archive, name, data):
    entry = tarfile.TarInfo(name)
    entry.size = len(data)
    archive.addfile(entry, io.BytesIO(data))


def fixture(directory):
    layer = io.BytesIO()
    with tarfile.open(fileobj=layer, mode='w') as archive:
        add(archive, 'hello.txt', b'the exact CI image\n')
    layer = layer.getvalue()
    config = json.dumps({'os': 'linux', 'architecture': 'amd64', 'config': {'User': '65534'},
        'rootfs': {'type': 'layers', 'diff_ids': [digest(layer)]}}).encode()
    saved = directory / 'saved.tar'
    with tarfile.open(saved, 'w') as archive:
        add(archive, 'config.json', config)
        add(archive, 'layer.tar', layer)
        add(archive, 'manifest.json', json.dumps([{'Config': 'config.json', 'RepoTags': ['fixture:ci'], 'Layers': ['layer.tar']}]).encode())
    return saved, config


class ImageBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.saved, self.config = fixture(self.directory)

    def export(self):
        return export_image(self.saved, 'backend', 'a' * 40, self.directory / 'output', 123, 1)

    def test_export_preserves_tested_config_and_measured_layer_chain(self):
        receipt = self.export()
        manifest = verify_bundle(self.directory / 'output' / receipt['archive'], receipt)
        self.assertEqual(digest(self.config), receipt['configId'])
        self.assertEqual(receipt['configId'], manifest['config']['digest'])
        self.assertEqual(json.loads(self.config)['rootfs']['diff_ids'], [x['diffId'] for x in receipt['layers']])
        self.assertGreater(receipt['layers'][0]['unpackedBytes'], 4096)

    def test_repeat_export_is_byte_identical(self):
        first = self.export()
        second = export_image(self.saved, 'backend', 'a' * 40, self.directory / 'second', 123, 1)
        self.assertEqual(first, second)

    def test_corruption_and_changed_identity_are_rejected(self):
        receipt = self.export()
        path = self.directory / 'output' / receipt['archive']
        for key, value in [('configId', 'sha256:' + 'f' * 64), ('manifestDigest', 'sha256:' + 'f' * 64),
                           ('archiveBytes', 1), ('archiveSha256', 'f' * 64), ('service', 'mysql')]:
            changed = copy.deepcopy(receipt)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_bundle(path, changed)
        with path.open('r+b') as stream:
            stream.seek(2000)
            stream.write(b'corrupt')
        with self.assertRaises(ValueError):
            verify_bundle(path, receipt)

    def test_registry_cannot_redirect_blob_upload_outside_owned_loopback_repository(self):
        record = {'port': 5001}
        base = '/v2/otziv-prepared/ci-backend/blobs/uploads/id?_state=opaque'
        sha = 'sha256:' + 'a' * 64
        self.assertEqual(base + '&digest=' + sha, upload_location(record, 'otziv-prepared/ci-backend', base, sha))
        for target in ['http://example.com' + base, 'http://127.0.0.1:5002' + base,
                       '/v2/other/blobs/uploads/id', base + '#fragment', '//example.com' + base]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                upload_location(record, 'otziv-prepared/ci-backend', target, sha)


if __name__ == '__main__':
    unittest.main()
