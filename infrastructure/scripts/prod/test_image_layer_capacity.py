import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from image_layer_capacity import measure_archive


def tar_bytes(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as tar:
        for name, value in files.items():
            entry = tarfile.TarInfo(name); entry.size = len(value)
            tar.addfile(entry, io.BytesIO(value))
    return output.getvalue()


class LayerMeasurements(unittest.TestCase):
    def fixture(self, directory, compressed=True, corrupted=False):
        # An intentionally tiny compressed image expands to several MiB. A size
        # from `image inspect` would under-budget it by orders of magnitude.
        layer = tar_bytes({'large.txt': b'x' * (3 * 1024 ** 2), '../never-extracted': b'x'})
        diff = 'sha256:' + hashlib.sha256(layer).hexdigest()
        blob = gzip.compress(layer) if compressed else layer
        config = json.dumps({'rootfs': {'type': 'layers', 'diff_ids': [diff]}}).encode()
        config_id = 'sha256:' + hashlib.sha256(config).hexdigest()
        if corrupted: blob = gzip.compress(tar_bytes({'wrong': b'y'}))
        manifest = json.dumps([{'Config':'config.json', 'Layers':['layer.tar']}]).encode()
        path = Path(directory)/'image.tar'
        path.write_bytes(tar_bytes({'manifest.json':manifest, 'config.json':config, 'layer.tar':blob}))
        return path, config_id, [{'size':len(blob)}], len(layer)

    def test_gzip_and_legacy_uncompressed_measure_real_tar_not_compressed_size(self):
        for compressed in [True, False]:
            with self.subTest(compressed=compressed), tempfile.TemporaryDirectory() as directory:
                path, config_id, published, unpacked = self.fixture(directory, compressed)
                result = measure_archive(path, config_id, published)[0]
                self.assertGreaterEqual(result['unpackedBytes'], unpacked)
                self.assertEqual(result['compressedBytes'], published[0]['size'])
                self.assertEqual(list(Path(directory).iterdir()), [path])
                if compressed: self.assertGreater(result['unpackedBytes'], 100 * result['compressedBytes'])

    def test_config_and_layer_substitution_and_inventory_mismatch_fail_closed(self):
        for mode in ['config','layer','inventory','size']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                path, config_id, published, _ = self.fixture(directory, corrupted=mode=='layer')
                if mode=='config': config_id = 'sha256:'+'a'*64
                if mode=='inventory': published=[]
                if mode=='size': published=[{'size':True}]
                with self.assertRaises(ValueError): measure_archive(path, config_id, published)


if __name__ == '__main__': unittest.main()
