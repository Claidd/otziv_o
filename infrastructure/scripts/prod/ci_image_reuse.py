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
from release_ci import REPOSITORY, GateError, require

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
BUILD_COMMON = ('.gitattributes', '.dockerignore')
TRANSPORT = ('deploy.ps1', 'infrastructure/scripts/prod/')


def build_recipe(repo, revision):
    """Hash the actual builder/matrix recipe, not unrelated workflow gates."""
    path = '.github/workflows/quality-gates.yml'
    result = subprocess.run(['git', '-C', str(repo), 'show', f'{revision}:{path}'],
                            capture_output=True, timeout=60)
    if result.returncode:
        return b''  # Minimal test repositories have no workflow.
    workflow = result.stdout.replace(b'\r\n', b'\n')
    job = re.search(rb'(?m)^  integration-images:\n(.*?)(?=^  [a-z][\w-]+:|\Z)', workflow, re.S)
    require(job, 'Image build job missing')
    matrix = re.search(rb'    strategy:\n(.*?)    steps:\n', job[1], re.S)
    recipe = re.search(rb'      - name: Set up cached image builder\n(.*?)      - name: Exercise sandbox', job[1], re.S)
    require(matrix and recipe, 'Image build recipe changed; update its fingerprint contract')
    return matrix[1] + recipe[1]


def fingerprint(repo, component, revision, kind='build'):
    require(component in SERVICES and re.fullmatch('[a-f0-9]{40}', revision), 'Invalid image input identity')
    raw = subprocess.check_output(['git', '-C', str(repo), 'ls-tree', '-rz', '--full-tree', revision], timeout=60)
    require(kind in ('build', 'policy', 'transport'), 'Unknown input fingerprint')
    prefixes = (*CONTEXTS[component], *BUILD_COMMON) if kind == 'build' else POLICY if kind == 'policy' else TRANSPORT
    selected = []
    for entry in raw.split(b'\0'):
        if not entry:
            continue
        metadata, name = entry.split(b'\t', 1)
        path = name.decode('utf-8')
        if any(path.startswith(prefix) if prefix.endswith('/') else path == prefix
               for prefix in prefixes):
            require(metadata.split()[1] == b'blob', 'Unsupported image input type')
            selected.append(entry)
    require(selected, 'Image inputs are missing')
    recipe = build_recipe(repo, revision) if kind == 'build' else b''
    return hashlib.sha256(b'otziv-image-inputs-v2\0' + kind.encode() + b'\0' + component.encode()
                          + b'\0' + b'\0'.join(sorted(selected)) + b'\0' + recipe).hexdigest()


def candidate(client, repo, component, revision, input_digest):
    from ci_release import fetch_manifest
    # Inspect latest runs, including failures. A rerun failure may not be hidden
    # by requesting only historical successes from the API.
    result = client.get('/actions/workflows/quality-gates.yml/runs?branch=main&event=push&per_page=10')
    seen = set()
    for run in result.get('workflow_runs', []):
        source = run.get('head_sha', '')
        if source == revision or not re.fullmatch('[a-f0-9]{40}', source):
            continue
        if source in seen:
            continue
        seen.add(source)
        if not (run.get('head_branch') == 'main' and run.get('event') == 'push'
                and run.get('status') == 'completed' and run.get('conclusion') == 'success'
                and run.get('path') == '.github/workflows/quality-gates.yml'
                and run.get('repository', {}).get('full_name') == REPOSITORY
                and run.get('head_repository', {}).get('full_name') == REPOSITORY):
            continue
        ancestor = subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', source, revision], capture_output=True, timeout=60)
        if ancestor.returncode != 0 or fingerprint(repo, component, source) != input_digest:
            continue
        from datetime import datetime, timezone, timedelta
        updated = run.get('updated_at')
        if not updated or not timedelta(0) <= datetime.now(timezone.utc) - datetime.fromisoformat(updated.replace('Z', '+00:00')) <= timedelta(days=7):
            continue
        current = client.get(f"/actions/runs/{run['id']}")
        require(current.get('run_attempt') == run['run_attempt'] and current.get('conclusion') == 'success'
                and current.get('status') == 'completed', 'Source image CI was rerun or failed')
        with tempfile.TemporaryDirectory(prefix='reuse-manifest-') as directory:
            value = fetch_manifest(client, {'result': 'PASS', 'revision': source, 'runs': [
                {'workflow': '.github/workflows/quality-gates.yml', 'runId': run['id'], 'attempt': run['run_attempt']}]}, Path(directory) / 'ci-release.json')
        image = next(row for row in value['images'] if row['component'] == component)
        return value, image
    return None


def validate_installed(installed, image):
    # Classic Docker identifies an image by config; containerd's image store
    # reports the OCI manifest digest. Both must bind to the verified bundle.
    identity_matches = installed['Id'] == image['configId'] or (
        installed['Id'] == image['manifestDigest']
        and (installed.get('Descriptor') or {}).get('digest') == image['manifestDigest'])
    require(identity_matches and installed['Os'] == 'linux' and installed['Architecture'] == 'amd64'
            and installed['RootFS']['Layers'] == [layer['diffId'] for layer in image['layers']],
            'Reused image differs from verified OCI')


def prepare(client, repo, component, revision, output):
    from ci_release import fetch_image
    input_digest = fingerprint(repo, component, revision)
    result = {'schema': 'otziv-image-reuse-v2', 'reused': False, 'component': component,
              'revision': revision, 'inputsDigest': input_digest,
              'policyDigest': fingerprint(repo, component, revision, 'policy'),
              'transportDigest': fingerprint(repo, component, revision, 'transport'),
              'reason': 'No recent successful ancestor with identical build inputs and available artifacts'}
    found = candidate(client, repo, component, revision, input_digest)
    if found:
        release, image = found
        with tempfile.TemporaryDirectory(prefix='reuse-image-') as directory:
            archive = fetch_image(client, release, image, directory)
            subprocess.run(['docker', 'load', '--input', str(archive)], check=True, capture_output=True, timeout=600)
        source = f"otziv-ci-{component}:{release['revision']}"
        installed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', source], timeout=60))[0]
        validate_installed(installed, image)
        subprocess.run(['docker', 'tag', source, f'otziv-{component}-ci'], check=True, timeout=60)
        result.update(reused=True, reason='Identical build inputs; current policy scans and smoke checks still required',
                      sourceRevision=release['revision'], sourceRunId=release['runId'],
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
        result = {'reused': False, 'reason': str(error) if isinstance(error, GateError)
                  else 'Verified source unavailable (' + type(error).__name__ + '); rebuilding with current scans'}
        args.output.write_text(json.dumps(result) + '\n', encoding='utf-8')
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        stream.write('reused=' + str(result['reused']).lower() + '\n')
    print('Verified previous image reused.' if result['reused'] else 'Fresh image build required: ' + result['reason'])


if __name__ == '__main__':
    main()
