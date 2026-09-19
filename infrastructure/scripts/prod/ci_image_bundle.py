"""Package the image tested by CI as an immutable OCI archive; never rebuild it.

The deployment transport uploads these exact blobs and manifest to its private
registry. Configuration, layer digests and the registry manifest stay unchanged.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

from image_layer_capacity import measure_archive

SERVICES = {'backend': 'app', 'web': 'nginx', 'whatsapp': 'whatsapp',
            'observer': 'docker-observer', 'worker': 'external-review-worker', 'publisher': None}
OCI_MANIFEST = 'application/vnd.oci.image.manifest.v1+json'
OCI_CONFIG = 'application/vnd.oci.image.config.v1+json'
OCI_LAYER = 'application/vnd.oci.image.layer.v1.tar+gzip'
SHA = re.compile(r'^sha256:[a-f0-9]{64}$')


def canonical(value):
    return json.dumps(value, separators=(',', ':'), sort_keys=True).encode()


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def file_hash(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def saved_image(path):
    with tarfile.open(path, 'r:') as archive:
        rows = json.load(archive.extractfile('manifest.json'))
        if len(rows) != 1:
            raise ValueError('Expected one saved platform image')
        row = rows[0]
        config = archive.extractfile(row['Config']).read(8 * 1024 * 1024)
        value = json.loads(config)
        if value.get('os') != 'linux' or value.get('architecture') != 'amd64':
            raise ValueError('Release image must be linux/amd64')
        if not value.get('rootfs', {}).get('diff_ids') or len(row['Layers']) != len(value['rootfs']['diff_ids']):
            raise ValueError('Image layer inventory is incomplete')
        return row, config


def export_image(saved, component, revision, output, run_id=0, attempt=0):
    if component not in SERVICES or not re.fullmatch('[a-f0-9]{40}', revision):
        raise ValueError('Invalid release source')
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    target = output / (component + '.oci.tar')
    receipt_path = output / (component + '.json')
    if target.exists() or receipt_path.exists():
        raise ValueError('Refusing to overwrite an image bundle')
    row, config = saved_image(saved)
    config_id = digest(config)
    with tempfile.TemporaryDirectory(prefix='.oci-', dir=output) as temporary:
        temporary = Path(temporary).resolve()
        assert temporary.is_relative_to(output)
        blobs = {}

        def add_bytes(data):
            identity = digest(data)
            path = temporary / identity[7:]
            path.write_bytes(data)
            blobs[identity] = path
            return {'digest': identity, 'size': len(data)}

        configuration = dict(add_bytes(config), mediaType=OCI_CONFIG)
        layers = []
        with tarfile.open(saved, 'r:') as incoming:
            for number, name in enumerate(row['Layers']):
                member = incoming.getmember(name)
                if not member.isfile():
                    raise ValueError('Layer is not a regular archive member')
                source = incoming.extractfile(member)
                magic = source.read(2)
                source.seek(0)
                compressed = temporary / ('layer-' + str(number))
                with compressed.open('xb') as destination:
                    if magic == b'\x1f\x8b':
                        shutil.copyfileobj(source, destination, 1024 * 1024)
                    else:
                        with gzip.GzipFile(fileobj=destination, mode='wb', filename='', mtime=0, compresslevel=1) as stream:
                            shutil.copyfileobj(source, stream, 1024 * 1024)
                identity = 'sha256:' + file_hash(compressed)
                blobs[identity] = compressed
                layers.append({'mediaType': OCI_LAYER, 'digest': identity, 'size': compressed.stat().st_size})
        # Independently read/hash the uncompressed tar streams and verify every
        # diff ID against the configuration of the actual tested image.
        sizes = measure_archive(saved, config_id, layers)
        manifest = {'schemaVersion': 2, 'mediaType': OCI_MANIFEST,
                    'config': configuration, 'layers': layers}
        descriptor = dict(add_bytes(canonical(manifest)), mediaType=OCI_MANIFEST,
                          platform={'os': 'linux', 'architecture': 'amd64'},
                          annotations={'io.containerd.image.name': f'docker.io/library/otziv-ci-{component}:{revision}',
                                       'org.opencontainers.image.ref.name': revision})
        index = canonical({'schemaVersion': 2, 'manifests': [descriptor]})
        compatibility = canonical([{'Config': 'blobs/sha256/' + config_id[7:],
            'RepoTags': [f'otziv-ci-{component}:{revision}'],
            'Layers': ['blobs/sha256/' + layer['digest'][7:] for layer in layers]}])
        with tarfile.open(target, 'x:') as outgoing:
            for name, data in [('oci-layout', b'{"imageLayoutVersion":"1.0.0"}'),
                               ('index.json', index), ('manifest.json', compatibility)]:
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                entry.mode = 0o600
                outgoing.addfile(entry, io.BytesIO(data))
            for identity, path in sorted(blobs.items()):
                entry = tarfile.TarInfo('blobs/sha256/' + identity[7:])
                entry.size = path.stat().st_size
                entry.mode = 0o600
                with path.open('rb') as stream:
                    outgoing.addfile(entry, stream)
    receipt = {'schema': 'otziv-ci-image-v1', 'revision': revision, 'component': component,
               'service': SERVICES[component], 'runId': run_id, 'runAttempt': attempt,
               'archive': target.name, 'archiveBytes': target.stat().st_size,
               'archiveSha256': file_hash(target), 'manifestDigest': descriptor['digest'],
               'configId': config_id, 'layers': sizes}
    receipt_path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return receipt


def verify_bundle(path, receipt):
    path = Path(path)
    if (receipt.get('schema') != 'otziv-ci-image-v1' or receipt.get('component') not in SERVICES
            or not re.fullmatch('[a-f0-9]{40}', receipt.get('revision', ''))
            or receipt.get('service') != SERVICES[receipt['component']]
            or not SHA.fullmatch(receipt.get('manifestDigest', ''))
            or not SHA.fullmatch(receipt.get('configId', ''))
            or path.name != receipt.get('archive') or path.stat().st_size != receipt.get('archiveBytes')
            or file_hash(path) != receipt.get('archiveSha256')):
        raise ValueError('CI image archive identity mismatch')
    with tarfile.open(path, 'r:') as archive:
        names = [member.name for member in archive]
        if len(set(names)) != len(names) or any(not member.isfile() for member in archive):
            raise ValueError('Duplicate or non-file OCI member')
        if any(name not in ('oci-layout', 'index.json', 'manifest.json') and not re.fullmatch('blobs/sha256/[a-f0-9]{64}', name) for name in names):
            raise ValueError('Unexpected OCI member')

        def blob(descriptor, limit=None):
            identity = descriptor['digest']
            if not SHA.fullmatch(identity):
                raise ValueError('Invalid OCI digest')
            member = archive.getmember('blobs/sha256/' + identity[7:])
            if member.size != descriptor['size'] or (limit and member.size > limit):
                raise ValueError('OCI blob size mismatch')
            return archive.extractfile(member)

        def metadata(name):
            if archive.getmember(name).size > 1024 * 1024:
                raise ValueError('OCI metadata exceeds its bound')
            return json.load(archive.extractfile(name))

        if metadata('oci-layout') != {'imageLayoutVersion': '1.0.0'}:
            raise ValueError('Invalid OCI layout')
        index = metadata('index.json')
        descriptors = index.get('manifests', [])
        if (index.get('schemaVersion') != 2 or len(descriptors) != 1
                or descriptors[0]['digest'] != receipt['manifestDigest']
                or descriptors[0].get('mediaType') != OCI_MANIFEST
                or descriptors[0].get('platform') != {'os': 'linux', 'architecture': 'amd64'}):
            raise ValueError('OCI manifest identity mismatch')
        data = blob(descriptors[0], 1024 * 1024).read()
        if digest(data) != receipt['manifestDigest']:
            raise ValueError('OCI manifest content mismatch')
        manifest = json.loads(data)
        if (manifest.get('schemaVersion') != 2 or manifest['mediaType'] != OCI_MANIFEST
                or manifest['config']['digest'] != receipt['configId']
                or manifest['config']['mediaType'] != OCI_CONFIG
                or not 0 < len(manifest['layers']) <= 128
                or any(layer['mediaType'] != OCI_LAYER for layer in manifest['layers'])):
            raise ValueError('OCI config identity mismatch')
        for descriptor in [manifest['config'], *manifest['layers']]:
            value = hashlib.sha256()
            with blob(descriptor) as blob_stream:
                for chunk in iter(lambda: blob_stream.read(1024 * 1024), b''):
                    value.update(chunk)
            if 'sha256:' + value.hexdigest() != descriptor['digest']:
                raise ValueError('OCI blob content mismatch')
        config = json.load(blob(manifest['config'], 8 * 1024 * 1024))
        if config.get('os') != 'linux' or config.get('architecture') != 'amd64':
            raise ValueError('OCI platform mismatch')
        expected = [{'Config': 'blobs/sha256/' + receipt['configId'][7:],
            'RepoTags': [f"otziv-ci-{receipt['component']}:{receipt['revision']}"],
            'Layers': ['blobs/sha256/' + layer['digest'][7:] for layer in manifest['layers']]}]
        if metadata('manifest.json') != expected:
            raise ValueError('Docker compatibility manifest mismatch')
        referenced = {receipt['manifestDigest'], receipt['configId'], *[layer['digest'] for layer in manifest['layers']]}
        if set(names) != {'oci-layout', 'index.json', 'manifest.json', *['blobs/sha256/' + identity[7:] for identity in referenced]}:
            raise ValueError('Unreferenced OCI archive content')
        if config['rootfs']['diff_ids'] != [layer['diffId'] for layer in receipt['layers']]:
            raise ValueError('OCI layer chain differs from capacity evidence')
        if [item['size'] for item in manifest['layers']] != [item['compressedBytes'] for item in receipt['layers']]:
            raise ValueError('OCI layer sizes differ from capacity evidence')
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--component', choices=SERVICES, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--attempt', type=int, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.save-', dir=args.output) as temporary:
        assert Path(temporary).resolve().is_relative_to(args.output.resolve())
        saved = Path(temporary) / 'image.tar'
        subprocess.run(['docker', 'image', 'save', '--output', str(saved), args.image], check=True)
        result = export_image(saved, args.component, args.revision, args.output, args.run_id, args.attempt)
    verify_bundle(args.output / result['archive'], result)
    print(json.dumps(result))


if __name__ == '__main__':
    main()
