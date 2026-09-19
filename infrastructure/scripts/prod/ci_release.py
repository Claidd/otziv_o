"""Collect CI receipts and install only immutable artifacts from verified main CI."""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import tempfile

from ci_artifacts import Client, extract_files, validate_artifact
from ci_image_bundle import SERVICES, verify_bundle
from deployment_capacity import freeze_image, validate_plan
from release_ci import require
from release_registry import upload_ci_image

SCHEMA = 'otziv-ci-release-v1'


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
    return value


def collect(repo, inputs, revision, run_id, attempt):
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
    return validate_release(result, revision, run_id, attempt)


def capacity_plan(value, references=None, worker=False):
    validate_release(value, value['revision'])
    plan = copy.deepcopy(value['runtimeCapacity'])
    for row in value['images']:
        service = row['service']
        if not service or (service == 'external-review-worker' and not worker):
            continue
        ref = references[row['component']] if references is not None else image_reference(row)
        plan['images'].append({'reference': ref, 'configId': row['configId'], 'layers': row['layers']})
        plan['releaseImages'][service] = ref
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
    write(output, value)
    return value


def fetch_image(client, value, row, output):
    validate_release(value, value['revision'])
    identity = row['artifact']
    item = client.get(f"/actions/artifacts/{identity['id']}")
    validate_artifact(item, value['runId'], value['revision'], identity['name'])
    require(item['digest'] == identity['digest'], 'Image artifact was substituted')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'download.zip'
    # ZIP adds small headers; compression-level 0 is intentional for OCI blobs.
    client.download(item, archive, row['archiveBytes'] + 16 * 1024**2)
    extract_files(archive, output, {row['archive']: row['archiveBytes'], row['component'] + '.json': 1024**2})
    require(read(output / (row['component'] + '.json')) == {k: v for k, v in row.items() if k != 'artifact'}, 'Image receipt differs from release manifest')
    verify_bundle(output / row['archive'], row)
    archive.unlink()  # Only our downloaded ZIP; keep the verified OCI artifact.
    return output / row['archive']


def import_release(client, value, record, directory, worker=False):
    references = {}
    for row in value['images']:
        if not row['service'] or (row['service'] == 'external-review-worker' and not worker):
            continue
        archive = fetch_image(client, value, row, Path(directory) / row['component'])
        references[row['component']] = upload_ci_image(record, archive, row)
    return capacity_plan(value, references, worker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['receipt', 'upstream', 'collect', 'fetch', 'import', 'validate-plan'])
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
    args = parser.parse_args()
    if args.command == 'receipt':
        value = read(args.input)
        value['artifact'] = {'id': args.artifact_id, 'digest': 'sha256:' + args.artifact_digest.removeprefix('sha256:'),
                             'name': f"ci-image-{value['component']}-attempt-{value['runAttempt']}"}
    elif args.command == 'upstream':
        value = {'revision': args.revision, 'runId': args.run_id, 'runAttempt': args.attempt, 'image': freeze_image(args.image)}
    elif args.command == 'collect':
        value = collect(args.repo, args.input, args.revision, args.run_id, args.attempt)
    elif args.command == 'fetch':
        fetch_manifest(Client(args.repo), read(args.input), args.output)
        return
    elif args.command == 'import':
        value = import_release(Client(args.repo), read(args.input), read(args.registry), args.output.parent / 'images', args.worker)
    else:
        value = read(args.input)
        validate_plan(value, args.revision)
        require(set(value['releaseImages']) == {'app', 'nginx', 'docker-observer', 'whatsapp', 'keycloak'} | ({'external-review-worker'} if args.worker else set()), 'Incomplete prepared release')
        release = validate_release(read(args.manifest), args.revision)
        from release_registry import owned
        registry = read(args.registry)
        owned(registry)
        require(registry['state'] == 'readonly', 'Prepared registry must reject writes')
        refs = {row['component']: f"127.0.0.1:{registry['port']}/otziv-prepared/ci-{row['component']}@{row['manifestDigest']}"
                for row in release['images']}
        require(value == capacity_plan(release, refs, args.worker), 'Prepared images differ from CI release')
    write(args.output, value)


if __name__ == '__main__':
    main()
