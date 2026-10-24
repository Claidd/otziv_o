"""Collect CI receipts and install only immutable artifacts from verified main CI."""
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import subprocess
import sys
import tarfile

from ci_artifacts import Client, extract_files, validate_artifact
from ci_image_bundle import SERVICES, canonical, verify_bundle
from deployment_capacity import freeze_image, validate_plan
from release_ci import GateError, require
from release_registry import upload_ci_image

SCHEMA = 'otziv-ci-release-v1'
RECEIPT_ARTIFACT = re.compile(r'^ci-receipt-([a-z0-9-]+)-attempt-([1-9][0-9]*)$')
CAPACITY_ARTIFACT = re.compile(r'^ci-capacity-([A-Za-z0-9_-]+)-attempt-([1-9][0-9]*)$')


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def image_reference(row):
    return 'ci.invalid/otziv/' + row['component'] + '@' + row['manifestDigest']


def validate_release(value, revision, run_id=None, attempt=None):
    require(value.get('schema') == SCHEMA and value.get('revision') == revision
            and re.fullmatch('[a-f0-9]{40}', revision), 'Release source mismatch')
    require(type(value.get('runId')) is int and value['runId'] > 0
            and type(value.get('runAttempt')) is int and value['runAttempt'] > 0, 'Invalid release run')
    require(run_id is None or value['runId'] == run_id, 'Release run mismatch')
    require(attempt is None or value['runAttempt'] == attempt, 'Release attempt mismatch')
    images = value.get('images', [])
    require(len(images) == len(SERVICES) and {row['component'] for row in images} == set(SERVICES), 'Incomplete CI image set')
    for row in images:
        require(row.get('schema') == 'otziv-ci-image-v1' and row['revision'] == revision
                and row['runId'] == value['runId'] and 0 < row['runAttempt'] <= value['runAttempt']
                and row['service'] == SERVICES[row['component']], 'Image source mismatch')
        require(row['archive'] == row['component'] + '.oci.tar'
                and re.fullmatch('[a-f0-9]{64}', row['archiveSha256'])
                and type(row['archiveBytes']) is int and 0 < row['archiveBytes'] <= 20 * 1024**3
                and re.fullmatch('sha256:[a-f0-9]{64}', row['manifestDigest']), 'Invalid image archive')
        artifact = row['artifact']
        require(type(artifact['id']) is int and artifact['id'] > 0
                and re.fullmatch('sha256:[a-f0-9]{64}', artifact['digest'])
                and artifact['name'] == f"ci-image-{row['component']}-attempt-{row['runAttempt']}", 'Invalid image artifact identity')
        validate_plan({'schema': 'otziv-deploy-capacity-v2', 'revision': revision,
                       'images': [{'reference': image_reference(row), 'configId': row['configId'], 'layers': row['layers']}]}, revision)
    validate_plan(value['runtimeCapacity'], revision)
    require(type(value.get('bundleBytes')) is int and 0 < value['bundleBytes'] <= 2 * 1024**3, 'Invalid bundle estimate')
    if 'testEvidence' in value:
        from ci_test_reuse import validate_decision
        validate_decision(value['testEvidence'], revision, value['runId'], value['runAttempt'])
    return value


def collect(repo, inputs, revision, run_id, attempt, test_reuse=None):
    rows = [read(path) for path in Path(inputs).rglob('*.json')]
    def latest(matches):
        require(matches, 'Missing CI receipt')
        require(all(row['revision'] == revision and row['runId'] == run_id
                    and 0 < row['runAttempt'] <= attempt for row in matches), 'CI receipt source mismatch')
        selected = max(matches, key=lambda row: row['runAttempt'])
        require(sum(row['runAttempt'] == selected['runAttempt'] for row in matches) == 1, 'Ambiguous CI receipt')
        return selected
    images = [latest([row for row in rows if row.get('component') == component]) for component in SERVICES]
    source = (Path(repo) / 'docker-compose.yaml').read_text(encoding='utf-8')
    refs = set(re.findall(r'(?m)^\s+image:\s+(?:\$\{[^}]*:-)?([a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64})', source))
    require(refs, 'Reviewed runtime defaults missing')
    capacity = [latest([row for row in rows if row.get('image', {}).get('reference') == ref])['image'] for ref in sorted(refs)]
    keycloak = re.search(r'\$\{OTZIV_KEYCLOAK_IMAGE:-([^}]+)\}', source)
    require(keycloak and keycloak[1] in refs, 'Reviewed Keycloak missing')
    # Conservative early estimate: tracked deploy inputs plus 64 MiB metadata.
    # Mandatory later checks use the actual deployment bundle, including any APK.
    import subprocess
    paths = subprocess.check_output(['git', '-C', str(repo), 'ls-files', '-z']).decode().split('\0')
    deploy = (Path(repo) / 'infrastructure/scripts/prod/deploy-prod.ps1').read_text(encoding='utf-8')
    block = re.search(r'\$deployBundlePaths = @\((.*?)\n\)', deploy, re.S)
    require(block, 'Deployment bundle inventory missing')
    prefixes = [p.replace('\\', '/') for p in re.findall(r'^\s*"([^"]+)"', block[1], re.M)]
    require('docker-compose.yaml' in prefixes, 'Deployment inventory is incomplete')
    bundle_bytes = 64 * 1024**2 + sum((Path(repo) / path).stat().st_size for path in paths
        if path and any(path == prefix or path.startswith(prefix + '/') for prefix in prefixes) and (Path(repo) / path).is_file())
    result = {'schema': SCHEMA, 'revision': revision, 'runId': run_id, 'runAttempt': attempt,
              'images': images, 'bundleBytes': bundle_bytes,
              'runtimeCapacity': {'schema': 'otziv-deploy-capacity-v2', 'revision': revision,
                                  'images': capacity, 'releaseImages': {'keycloak': keycloak[1]}}}
    if test_reuse is not None:
        result['testEvidence'] = read(test_reuse)
    return validate_release(result, revision, run_id, attempt)


def select_receipt_artifacts(artifacts, revision, run_id, attempt):
    """Pick the newest receipt for each identity before downloading ZIP bytes.

    A failed-job rerun may retain successful receipts from an earlier attempt.
    Older superseded artifacts must not be fetched: GitHub may no longer be able
    to serve one even though a newer verified receipt exists for that component.
    """
    require(re.fullmatch(r'[a-f0-9]{40}', revision or '') and type(run_id) is int and run_id > 0
            and type(attempt) is int and attempt > 0, 'Invalid CI artifact source')
    grouped = {}
    for item in artifacts:
        name = item.get('name', '')
        if not isinstance(name, str):
            continue
        match = RECEIPT_ARTIFACT.fullmatch(name)
        if match and match[1] in SERVICES:
            kind, identity = 'receipt', match[1]
        else:
            match = CAPACITY_ARTIFACT.fullmatch(name)
            if not match:
                continue
            kind, identity = 'capacity', match[1]
        row_attempt = int(match[2])
        require(row_attempt <= attempt, 'CI artifact is from a future attempt')
        grouped.setdefault((kind, identity), []).append((row_attempt, item))
    require({identity for kind, identity in grouped if kind == 'receipt'} == set(SERVICES),
            'Incomplete CI image receipts')
    require(any(kind == 'capacity' for kind, _ in grouped), 'Missing runtime capacity receipts')
    selected = []
    for (kind, identity), matches in sorted(grouped.items()):
        newest = max(row_attempt for row_attempt, _ in matches)
        current = [item for row_attempt, item in matches if row_attempt == newest]
        require(len(current) == 1, 'Ambiguous CI artifact: ' + kind + '/' + identity)
        item = validate_artifact(current[0], run_id, revision, current[0]['name'])
        selected.append((kind, identity, newest, item))
    return selected


def download_receipts(client, output, revision, run_id, attempt):
    """Download only selected immutable ZIPs, then bind each JSON to its name."""
    output = Path(output)
    require(not output.exists(), 'CI receipt output already exists')
    selected = select_receipt_artifacts(client.artifacts(run_id), revision, run_id, attempt)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ci-receipts-', dir=output.parent) as scratch:
        scratch = Path(scratch)
        for kind, identity, row_attempt, item in selected:
            filename = ('receipt-' if kind == 'receipt' else 'upstream-') + identity + '.json'
            archive = client.download(item, scratch / (str(item['id']) + '.zip'), 4 * 1024**2)
            destination = scratch / item['name']
            extract_files(archive, destination, {filename: 1024**2})
            row = read(destination / filename)
            require(isinstance(row, dict) and row.get('revision') == revision and row.get('runId') == run_id
                    and row.get('runAttempt') == row_attempt, 'CI receipt does not match its artifact attempt')
            if kind == 'receipt':
                require(row.get('component') == identity, 'CI image receipt has a different component')
            else:
                image = row.get('image')
                require(isinstance(image, dict), 'CI capacity receipt has no image')
                reference = image.get('reference', '')
                require(isinstance(reference, str) and re.fullmatch(r'[A-Za-z0-9._/:+-]+@sha256:[a-f0-9]{64}', reference),
                        'CI capacity receipt has an invalid reference')
                repository = reference.split('@', 1)[0]
                expected_id = re.sub(r'[^A-Za-z0-9_-]', '-', repository) + '-' + hashlib.sha256(reference.encode()).hexdigest()[:10]
                require(identity == expected_id, 'CI capacity receipt has a different image identity')
            archive.unlink()
        scratch.replace(output)
    return len(selected)


def validate_reused_references(value, reused, worker=False):
    """Only an exact current CI digest may keep a previous loopback reference."""
    require(isinstance(reused, dict), 'Invalid installed image reuse proof')
    rows = {row['component']: row for row in value['images']
            if row['service'] and (row['service'] != 'external-review-worker' or worker)}
    require(set(reused) <= set(rows), 'Unexpected reused image component')
    for component, reference in reused.items():
        require(isinstance(reference, str), 'Invalid reused image reference')
        match = re.fullmatch(r'127\.0\.0\.1:([1-9][0-9]*)/otziv-prepared/ci-'
                             + re.escape(component) + r'@(sha256:[a-f0-9]{64})', reference)
        require(match and 1024 <= int(match[1]) <= 65535
                and match[2] == rows[component]['manifestDigest'],
                'Reused image differs from exact CI manifest')
    return dict(reused)


def reused_references(value, preflight, worker=False):
    """Retain a component only when every expected service uses the exact CI image."""
    require(preflight.get('schema') == 'otziv-server-preflight-v1'
            and preflight.get('ready') is True and isinstance(preflight.get('images'), list),
            'Healthy production inventory required for image reuse')
    result = {}
    for row in value['images']:
        service = row['service']
        if not service or (service == 'external-review-worker' and not worker):
            continue
        names = {'whatsapp_lika', 'whatsapp_vika'} if service == 'whatsapp' else {service}
        candidates = [item for item in preflight['images'] if item.get('service') in names]
        if len(candidates) != len(names) or {item['service'] for item in candidates} != names:
            continue
        if any(item.get('healthy') is not True or item.get('configId') != row['configId']
               for item in candidates):
            continue
        references = {item.get('reference') for item in candidates}
        if len(references) != 1:
            continue
        candidate = {row['component']: references.pop()}
        try:
            result.update(validate_reused_references(value, candidate, worker))
        except (GateError, ValueError, TypeError):
            # Legacy tags, other repositories or different manifests require transfer.
            continue
    return result


def transport_references(value, port, reused=None, worker=False):
    require(type(port) is int and 1024 <= port <= 65535, 'Invalid transport port')
    refs = {row['component']: f"127.0.0.1:{port}/otziv-prepared/ci-{row['component']}@{row['manifestDigest']}"
            for row in value['images']}
    refs.update(validate_reused_references(value, reused or {}, worker))
    return refs


def capacity_plan(value, references=None, worker=False, reused=None):
    validate_release(value, value['revision'])
    plan = copy.deepcopy(value['runtimeCapacity'])
    for row in value['images']:
        service = row['service']
        if not service or (service == 'external-review-worker' and not worker):
            continue
        ref = references[row['component']] if references is not None else image_reference(row)
        plan['images'].append({'reference': ref, 'configId': row['configId'], 'layers': row['layers']})
        plan['releaseImages'][service] = ref
    if reused:
        reused = validate_reused_references(value, reused, worker)
        require(references is not None and all(references.get(key) == ref for key, ref in reused.items()),
                'Reused image not included in the release plan')
        plan['reusedImages'] = sorted(reused.values())
    validate_plan(plan, value['revision'])
    return plan


def fetch_manifest(client, ci, output):
    require(ci.get('result') == 'PASS', 'Verified main CI is required')
    runs = [row for row in ci['runs'] if row['workflow'] == '.github/workflows/quality-gates.yml']
    require(len(runs) == 1, 'Quality CI run missing')
    run = runs[0]
    name = f"ci-release-manifest-attempt-{run['attempt']}"
    matches = [row for row in client.artifacts(run['runId']) if row['name'] == name]
    require(len(matches) == 1, 'Release manifest missing; rerun main quality CI')
    item = validate_artifact(matches[0], run['runId'], ci['revision'], name)
    with tempfile.TemporaryDirectory(prefix='manifest-') as temporary:
        archive = client.download(item, Path(temporary) / 'manifest.zip', 2 * 1024**2)
        directory = Path(temporary) / 'files'
        extract_files(archive, directory, {'ci-release.json': 2 * 1024**2})
        value = validate_release(read(directory / 'ci-release.json'), ci['revision'], run['runId'], run['attempt'])
    if 'testEvidence' in value:
        from ci_test_reuse import verify_live_sources
        verify_live_sources(client, value['testEvidence'])
    write(output, value)
    return value


def restore_cached_image(cache_root, row, output):
    """Recreate exact current CI bytes from locally verified, identical OCI blobs.

    A reused image has the same OCI manifest and blobs, but its Docker tag and
    OCI index annotations name the current revision. The final CI archive SHA
    and full OCI verification are mandatory; any cache miss falls back to the
    signed GitHub artifact.
    """
    def redirected(path):
        return path.is_symlink() or getattr(path, 'is_junction', lambda: False)()

    if cache_root is None:
        return None
    cache_root = Path(cache_root)
    if not cache_root.is_absolute() or redirected(cache_root):
        return None
    root = cache_root.resolve()
    if not root.is_dir():
        return None
    output = Path(output)
    target = output / row['archive']
    if target.exists():
        return None
    component = row['component']
    candidates = []
    for source in root.glob(f'*/images/{component}/{component}.oci.tar'):
        try:
            candidates.append((source.stat().st_mtime, source))
        except OSError:
            continue
    for _, source in sorted(candidates, key=lambda item: item[0], reverse=True):
        try:
            if not source.resolve().is_relative_to(root) or source.resolve() == target.resolve():
                continue
            if any(redirected(path) for path in (source, *source.parents)
                   if path == root or path.is_relative_to(root)):
                continue
            receipt = read(source.with_name(component + '.json'))
            if (receipt.get('component') != component
                    or receipt.get('manifestDigest') != row['manifestDigest']
                    or receipt.get('configId') != row['configId']
                    or receipt.get('layers') != row['layers']
                    or source.stat().st_size != row['archiveBytes']):
                continue
            verify_bundle(source, receipt)
            with tempfile.TemporaryDirectory(prefix='.cached-oci-', dir=output) as temporary:
                rebuilt = Path(temporary) / row['archive']
                with tarfile.open(source, 'r:') as incoming, tarfile.open(rebuilt, 'x:') as outgoing:
                    index = json.load(incoming.extractfile('index.json'))
                    index['manifests'][0]['annotations'] = {
                        'io.containerd.image.name': f"docker.io/library/otziv-ci-{component}:{row['revision']}",
                        'org.opencontainers.image.ref.name': row['revision']}
                    compatibility = json.load(incoming.extractfile('manifest.json'))
                    compatibility[0]['RepoTags'] = [f"otziv-ci-{component}:{row['revision']}"]
                    for name, data in (
                        ('oci-layout', b'{"imageLayoutVersion":"1.0.0"}'),
                        ('index.json', canonical(index)),
                        ('manifest.json', canonical(compatibility))):
                        member = tarfile.TarInfo(name)
                        member.size = len(data)
                        member.mode = 0o600
                        outgoing.addfile(member, io.BytesIO(data))
                    blobs = sorted((member for member in incoming if member.name.startswith('blobs/sha256/')),
                                   key=lambda member: member.name)
                    for original in blobs:
                        member = tarfile.TarInfo(original.name)
                        member.size = original.size
                        member.mode = 0o600
                        with incoming.extractfile(original) as stream:
                            outgoing.addfile(member, stream)
                verify_bundle(rebuilt, row)
                os.replace(rebuilt, target)
            return target
        except (OSError, ValueError, KeyError, TypeError, IndexError, EOFError, tarfile.TarError):
            # Local evidence can be absent or damaged; the normal signed
            # artifact download and all of its checks remain the fallback.
            continue
    return None


def fetch_image(client, value, row, output, cache_root=None):
    validate_release(value, value['revision'])
    identity = row['artifact']
    item = client.get(f"/actions/artifacts/{identity['id']}")
    validate_artifact(item, value['runId'], value['revision'], identity['name'])
    require(item['digest'] == identity['digest'], 'Image artifact was substituted')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    cached = restore_cached_image(cache_root, row, output)
    if cached is not None:
        print('Exact CI image archive restored from local verified cache: ' + row['component'])
        return cached
    archive = output / 'download.zip'
    # ZIP adds small headers; compression-level 0 is intentional for OCI blobs.
    client.download(item, archive, row['archiveBytes'] + 16 * 1024**2)
    extract_files(archive, output, {row['archive']: row['archiveBytes'], row['component'] + '.json': 1024**2})
    require(read(output / (row['component'] + '.json')) == {k: v for k, v in row.items() if k != 'artifact'}, 'Image receipt differs from release manifest')
    verify_bundle(output / row['archive'], row)
    archive.unlink()  # Only our downloaded ZIP; keep the verified OCI artifact.
    return output / row['archive']


def import_release(client, value, record, directory, worker=False, cache_root=None, reused=None):
    validate_release(value, value['revision'])
    reused = validate_reused_references(value, reused or {}, worker)
    references = dict(reused)
    for row in value['images']:
        if not row['service'] or (row['service'] == 'external-review-worker' and not worker):
            continue
        if row['component'] in reused:
            print('Reusing exact CI image already installed on VPS: ' + row['component'], file=sys.stderr)
            continue
        archive = fetch_image(client, value, row, Path(directory) / row['component'], cache_root)
        references[row['component']] = upload_ci_image(record, archive, row)
    return capacity_plan(value, references, worker, reused)


def load_backend(inputs, revision, run_id, attempt):
    """Reuse the tested application in recovery, including failed-job reruns."""
    receipts = [(path, read(path)) for path in Path(inputs).rglob('backend.json')]
    require(receipts, 'Tested backend artifact is missing')
    for path, row in receipts:
        require(row.get('schema') == 'otziv-ci-image-v1' and row.get('component') == 'backend'
                and row.get('service') == 'app' and row.get('revision') == revision
                and row.get('runId') == run_id and 0 < row.get('runAttempt', 0) <= attempt
                and row.get('archive') == 'backend.oci.tar', 'Recovery image is from another source or run')
    selected = max(row['runAttempt'] for _, row in receipts)
    matches = [(path, row) for path, row in receipts if row['runAttempt'] == selected]
    require(len(matches) == 1, 'Ambiguous recovery image attempt')
    path, row = matches[0]
    archive = path.parent / row['archive']
    verify_bundle(archive, row)
    subprocess.run(['docker', 'load', '--input', str(archive)], check=True)
    reference = 'otziv-ci-backend:' + revision
    installed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', reference]))[0]
    require(installed['Os'] == 'linux' and installed['Architecture'] == 'amd64'
            and installed['RootFS']['Layers'] == [layer['diffId'] for layer in row['layers']], 'Loaded recovery image differs from CI')
    subprocess.run(['docker', 'tag', reference, 'otziv-recovery-app-ci'], check=True)
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['receipt', 'upstream', 'download-receipts', 'collect', 'fetch', 'import', 'validate-plan', 'load-backend'])
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--input', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--revision', default=os.environ.get('GITHUB_SHA'))
    parser.add_argument('--run-id', type=int, default=int(os.environ.get('GITHUB_RUN_ID', '0')))
    parser.add_argument('--attempt', type=int, default=int(os.environ.get('GITHUB_RUN_ATTEMPT', '0')))
    parser.add_argument('--image')
    parser.add_argument('--artifact-id', type=int)
    parser.add_argument('--artifact-digest')
    parser.add_argument('--registry', type=Path)
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--worker', action='store_true')
    parser.add_argument('--cache-root', type=Path)
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--test-reuse', type=Path)
    args = parser.parse_args()
    if args.command == 'receipt':
        value = read(args.input)
        value['artifact'] = {'id': args.artifact_id, 'digest': 'sha256:' + args.artifact_digest.removeprefix('sha256:'),
                             'name': f"ci-image-{value['component']}-attempt-{value['runAttempt']}"}
    elif args.command == 'upstream':
        value = {'revision': args.revision, 'runId': args.run_id, 'runAttempt': args.attempt, 'image': freeze_image(args.image)}
    elif args.command == 'collect':
        value = collect(args.repo, args.input, args.revision, args.run_id, args.attempt, args.test_reuse)
    elif args.command == 'download-receipts':
        count = download_receipts(Client(args.repo), args.output, args.revision, args.run_id, args.attempt)
        print(f'Verified and downloaded {count} selected CI receipt artifacts')
        return
    elif args.command == 'fetch':
        fetch_manifest(Client(args.repo), read(args.input), args.output)
        return
    elif args.command == 'import':
        release = read(args.input)
        registry = read(args.registry)
        reused = reused_references(release, read(args.preflight), args.worker) if args.preflight else {}
        registry['reusedImages'] = reused
        write(args.registry, registry)
        value = import_release(Client(args.repo), release, registry, args.output.parent / 'images',
                               args.worker, args.cache_root, reused)
    elif args.command == 'load-backend':
        value = load_backend(args.input, args.revision, args.run_id, args.attempt)
    else:
        value = read(args.input)
        validate_plan(value, args.revision)
        require(set(value['releaseImages']) == {'app', 'nginx', 'docker-observer', 'whatsapp', 'keycloak'} | ({'external-review-worker'} if args.worker else set()), 'Incomplete prepared release')
        release = validate_release(read(args.manifest), args.revision)
        from release_registry import owned
        registry = read(args.registry)
        if registry.get('transport') == 'vps-artifact':
            from remote_release_transport import validate_remote_plan
            validate_remote_plan(registry, release, value, args.worker)
        else:
            owned(registry)
            require(registry['state'] == 'readonly', 'Prepared registry must reject writes')
            reused = validate_reused_references(release, registry.get('reusedImages', {}), args.worker)
            refs = transport_references(release, registry['port'], reused, args.worker)
            require(value == capacity_plan(release, refs, args.worker, reused),
                    'Prepared images differ from CI release')
    write(args.output, value)


if __name__ == '__main__':
    main()
