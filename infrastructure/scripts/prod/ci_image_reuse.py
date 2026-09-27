"""Reuse a successfully tested main image only when every declared build input matches.

The current job still scans/exercises the image and emits a fresh, current-run OCI
receipt. No previous CI status substitutes for the final main release gates.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from ci_artifacts import Client
from ci_image_bundle import SERVICES
from release_ci import REPOSITORY, require

CONTEXTS = {
    'backend': ('backend/',),
    'web': ('frontend/', 'shared/client-common/', 'infrastructure/nginx/'),
    'whatsapp': ('whatsapp/', 'Dockerfile.whatsapp'),
    'observer': ('infrastructure/docker-observer/',),
    'worker': ('backend/external-review-worker/',),
    'publisher': ('infrastructure/monitoring/',),
}
POLICY = ('.github/', 'infrastructure/scripts/prod/', 'infrastructure/scripts/security/',
          'infrastructure/runtime-security/', '.gitattributes', '.dockerignore')


def fingerprint(repo, component, revision):
    require(component in SERVICES and re.fullmatch('[a-f0-9]{40}', revision), 'Invalid image input identity')
    raw = subprocess.check_output(['git', '-C', str(repo), 'ls-tree', '-rz', '--full-tree', revision], timeout=60)
    selected = []
    for entry in raw.split(b'\0'):
        if not entry:
            continue
        metadata, name = entry.split(b'\t', 1)
        path = name.decode('utf-8')
        if any(path.startswith(prefix) if prefix.endswith('/') else path == prefix
               for prefix in (*CONTEXTS[component], *POLICY)):
            require(metadata.split()[1] == b'blob', 'Unsupported image input type')
            selected.append(entry)
    require(selected, 'Image inputs are missing')
    return hashlib.sha256(b'otziv-image-inputs-v1\0' + component.encode() + b'\0' + b'\0'.join(sorted(selected))).hexdigest()


def candidate(client, repo, component, revision, input_digest):
    from ci_release import fetch_manifest
    result = client.get('/actions/workflows/quality-gates.yml/runs?branch=main&event=push&status=success&per_page=10')
    for run in result.get('workflow_runs', []):
        source = run.get('head_sha', '')
        if source == revision or not re.fullmatch('[a-f0-9]{40}', source):
            continue
        if not (run.get('head_branch') == 'main' and run.get('event') == 'push'
                and run.get('status') == 'completed' and run.get('conclusion') == 'success'
                and run.get('path') == '.github/workflows/quality-gates.yml'
                and run.get('repository', {}).get('full_name') == REPOSITORY
                and run.get('head_repository', {}).get('full_name') == REPOSITORY):
            continue
        ancestor = subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', source, revision], capture_output=True, timeout=60)
        if ancestor.returncode != 0 or fingerprint(repo, component, source) != input_digest:
            continue
        with tempfile.TemporaryDirectory(prefix='reuse-manifest-') as directory:
            value = fetch_manifest(client, {'result': 'PASS', 'revision': source, 'runs': [
                {'workflow': '.github/workflows/quality-gates.yml', 'runId': run['id'], 'attempt': run['run_attempt']}]}, Path(directory) / 'ci-release.json')
        image = next(row for row in value['images'] if row['component'] == component)
        return value, image
    return None


def prepare(client, repo, component, revision, output):
    from ci_release import fetch_image
    input_digest = fingerprint(repo, component, revision)
    result = {'reused': False, 'component': component, 'revision': revision, 'inputsDigest': input_digest}
    found = candidate(client, repo, component, revision, input_digest)
    if found:
        release, image = found
        with tempfile.TemporaryDirectory(prefix='reuse-image-') as directory:
            archive = fetch_image(client, release, image, directory)
            subprocess.run(['docker', 'load', '--input', str(archive)], check=True, capture_output=True, timeout=600)
        source = f"otziv-ci-{component}:{release['revision']}"
        installed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', source], timeout=60))[0]
        require(installed['Id'] == image['configId'] and installed['Os'] == 'linux' and installed['Architecture'] == 'amd64'
                and installed['RootFS']['Layers'] == [layer['diffId'] for layer in image['layers']], 'Reused image differs from verified OCI')
        subprocess.run(['docker', 'tag', source, f'otziv-{component}-ci'], check=True, timeout=60)
        result.update(reused=True, sourceRevision=release['revision'], sourceRunId=release['runId'],
                      sourceArtifactId=image['artifact']['id'], manifestDigest=image['manifestDigest'], configId=image['configId'])
    Path(output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=SERVICES, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--revision', default=os.environ.get('GITHUB_SHA'))
    args = parser.parse_args()
    try:
        result = prepare(Client(Path.cwd()), Path.cwd(), args.component, args.revision, args.output)
    except Exception as error:
        # A cache miss, expired artifact or unverifiable source always builds anew.
        # No security scan failure is caught here: scans execute later in the job.
        result = {'reused': False, 'reason': type(error).__name__}
        args.output.write_text(json.dumps(result) + '\n', encoding='utf-8')
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        stream.write('reused=' + str(result['reused']).lower() + '\n')
    print('Verified previous image reused.' if result['reused'] else 'Fresh image build required.')


if __name__ == '__main__':
    main()
