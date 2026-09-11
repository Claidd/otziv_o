"""Measure saved Docker layers without extracting or executing image contents.

Docker's image inspect Size can be compressed content size with containerd, so it
is not an unpack budget. Bind each measured tar stream to the OCI config diff ID.
Only an identical complete chain of diff IDs may reuse an existing overlay2 layer.
"""
import gzip
import hashlib
import json
import re
import tarfile

DIGEST = re.compile(r"^sha256:[a-f0-9]{64}$")
BLOCK = 4096
MAX_LAYER_BYTES = 30 * 1024 ** 3


def chain_ids(diff_ids):
    parent = None
    for diff_id in diff_ids:
        if not DIGEST.fullmatch(diff_id):
            raise ValueError("Invalid layer diff ID")
        parent = diff_id if parent is None else 'sha256:' + hashlib.sha256((parent + ' ' + diff_id).encode()).hexdigest()
        yield parent


class MeasuredStream:
    def __init__(self, source):
        self.source = source
        self.digest = hashlib.sha256()
        self.size = 0

    def read(self, size=-1):
        # tarfile's streaming reader always requests bounded chunks.
        if size < 0: raise ValueError("Unbounded layer read")
        data = self.source.read(size)
        self.size += len(data)
        if self.size > MAX_LAYER_BYTES: raise ValueError("Layer exceeds supported size")
        self.digest.update(data)
        return data


def measure_archive(path, config_id, registry_layers):
    with tarfile.open(path, 'r:') as archive:
        manifest = json.load(archive.extractfile('manifest.json'))
        if len(manifest) != 1: raise ValueError("Expected exactly one saved platform image")
        row = manifest[0]
        config = archive.extractfile(row['Config']).read()
        if 'sha256:' + hashlib.sha256(config).hexdigest() != config_id:
            raise ValueError("Saved image config differs from published platform")
        diff_ids = json.loads(config)['rootfs']['diff_ids']
        if not len(diff_ids) == len(row['Layers']) == len(registry_layers) or not diff_ids:
            raise ValueError("Saved and published layer inventories differ")
        result = []
        for name, diff_id, published in zip(row['Layers'], diff_ids, registry_layers):
            if not DIGEST.fullmatch(diff_id): raise ValueError("Invalid saved diff ID")
            compressed = published['size']
            if type(compressed) is not int or not 0 < compressed <= MAX_LAYER_BYTES:
                raise ValueError("Invalid published layer size")
            member = archive.getmember(name)
            if not member.isfile(): raise ValueError("Saved layer must be a regular archive member")
            blob = archive.extractfile(member)
            magic = blob.read(2); blob.seek(0)
            source = gzip.GzipFile(fileobj=blob) if magic == b'\x1f\x8b' else blob
            measured = MeasuredStream(source)
            allocated = 0
            # No paths are extracted. Hardlinks and tiny files are over-budgeted
            # independently; each entry also gets one block of filesystem metadata.
            with tarfile.open(fileobj=measured, mode='r|') as layer:
                for entry in layer:
                    if entry.size < 0: raise ValueError("Negative layer file size")
                    allocated += max(BLOCK, ((entry.size + BLOCK - 1) // BLOCK) * BLOCK) + BLOCK
            while measured.read(1024 * 1024): pass
            if 'sha256:' + measured.digest.hexdigest() != diff_id:
                raise ValueError("Layer contents do not match published config diff ID")
            result.append({'diffId': diff_id, 'compressedBytes': compressed,
                           'unpackedBytes': max(measured.size, allocated) + 4 * 1024 ** 2})
        return result
