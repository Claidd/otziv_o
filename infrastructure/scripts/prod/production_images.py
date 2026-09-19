"""Record verified production image identities and rescan that deployed release daily."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

from ci_artifacts import Client
from ci_release import fetch_image, fetch_manifest, read, validate_release, write
from release_ci import REPOSITORY, require
from release_preflight import add_arguments, probe_args

SCHEMA = 'otziv-production-images-v1'


def inventory(release, observed):
    validate_release(release, release['revision'])
    result = {}
    for item in observed:
        require(re.fullmatch('sha256:[a-f0-9]{64}', item['configId']), 'Invalid running image identity')
        row = {'id': item['configId'][7:23], 'configId': item['configId'], 'reference': item['reference']}
        service = 'whatsapp' if item['service'].startswith('whatsapp_') else item['service']
        app = [image for image in release['images'] if image['service'] == service]
        if app:
            require(len(app) == 1 and app[0]['configId'] == item['configId'], 'Running application differs from tested CI image: ' + service)
            row['component'] = app[0]['component']
        else:
            require(any(image['reference'] == item['reference'] and image['configId'] == item['configId']
                        for image in release['runtimeCapacity']['images']), 'Running infrastructure image is outside the verified release: ' + service)
        result[item['configId']] = row
    require(result, 'No running images found')
    return sorted(result.values(), key=lambda row: row['id'])


def record(client, release, observed):
    images = inventory(release, observed)
    payload = {'schema': SCHEMA, 'revision': release['revision'], 'runId': release['runId'],
               'runAttempt': release['runAttempt'], 'images': images}
    deployment = client.get('/deployments', 'POST', {'ref': release['revision'], 'environment': 'production',
        'auto_merge': False, 'required_contexts': [], 'description': 'Verified CI images; database backup and post-install health checks passed',
        'payload': payload, 'production_environment': True})
    require(type(deployment.get('id')) is int, 'Production deployment record was not created')
    client.get(f"/deployments/{deployment['id']}/statuses", 'POST', {'state': 'success',
        'environment': 'production', 'environment_url': 'https://o-ogo.ru', 'auto_inactive': True,
        'description': 'Running image config digests match verified CI and reviewed infrastructure'})
    return {'deploymentId': deployment['id'], 'payload': payload}


def current(client):
    deployments = client.get('/deployments?environment=production&per_page=100')
    require(isinstance(deployments, list), 'Invalid production deployment listing')
    for deployment in deployments:
        payload = deployment.get('payload', {})
        if isinstance(payload, str):
            payload = json.loads(payload)
        if payload.get('schema') != SCHEMA:
            continue
        states = client.get(f"/deployments/{deployment['id']}/statuses?per_page=100")
        require(states and states[0]['state'] == 'success', 'Latest recorded production release is not verified healthy')
        require(deployment['sha'] == payload['revision'] and deployment['environment'] == 'production', 'Production source mismatch')
        run = client.get(f"/actions/runs/{int(payload['runId'])}")
        require(run['head_sha'] == payload['revision'] and run['head_branch'] == 'main'
                and run['event'] == 'push' and run['path'] == '.github/workflows/quality-gates.yml'
                and run['repository']['full_name'] == REPOSITORY and run['head_repository']['full_name'] == REPOSITORY
                and run['status'] == 'completed' and run['conclusion'] == 'success'
                and run['run_attempt'] >= payload['runAttempt'], 'Production CI source cannot be verified')
        return payload
    raise RuntimeError('No verified production image inventory; complete a normal deploy or record its verified inventory')


def load_release(client, payload, output):
    ci = {'result': 'PASS', 'revision': payload['revision'], 'runs': [
        {'workflow': '.github/workflows/quality-gates.yml', 'runId': payload['runId'], 'attempt': payload['runAttempt']}]}
    return fetch_manifest(client, ci, output)


def scan_one(client, payload, release, identity, directory):
    rows = [row for row in payload['images'] if row['id'] == identity]
    require(len(rows) == 1, 'Ambiguous production scan target')
    row = rows[0]
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if row.get('component'):
        matches = [image for image in release['images'] if image['component'] == row['component'] and image['configId'] == row['configId']]
        require(len(matches) == 1, 'Production application is missing from CI receipt')
        image = matches[0]
        archive = fetch_image(client, release, image, directory / 'image')
        subprocess.run(['docker', 'load', '--input', str(archive)], check=True)
        reference = f"otziv-ci-{image['component']}:{release['revision']}"
        # The checked OCI archive has already bound the configuration and layers.
    else:
        matches = [image for image in release['runtimeCapacity']['images']
                   if image['reference'] == row['reference'] and image['configId'] == row['configId']]
        require(len(matches) == 1, 'Production infrastructure is missing from CI receipt')
        reference = row['reference']
        subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', reference], check=True)
    installed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', reference]))[0]
    # Classic CI Docker IDs are config digests; OCI/containerd IDs may instead
    # name the manifest. The immutable archive/reference supplies that identity.
    require(installed['Os'] == 'linux' and installed['Architecture'] == 'amd64'
            and installed['RootFS']['Layers'] == [layer['diffId'] for layer in matches[0]['layers']], 'Loaded image layer chain changed')
    write(directory / 'scanned-identity.json', {'revision': release['revision'], **row})
    subprocess.run(['node', 'infrastructure/runtime-security/scan.mjs', 'image', reference,
                    str(directory / 'vulnerabilities.json')], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('record')
    add_arguments(p)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    for name in ('inventory', 'scan'):
        q = sub.add_parser(name)
        q.add_argument('--output', type=Path, required=True)
        if name == 'scan':
            q.add_argument('--identity', required=True)
            q.add_argument('--inventory', type=Path, required=True)
    args = parser.parse_args()
    client = Client(Path.cwd())
    if args.command == 'record':
        observed = probe_args(args)
        write(args.output, record(client, read(args.manifest), observed['images']))
    elif args.command == 'inventory':
        value = current(client)
        # Prove artifact availability now; an expired artifact fails visibly.
        load_release(client, value, args.output.parent / 'ci-release.json')
        require(0 < len(value['images']) <= 32 and len({row['id'] for row in value['images']}) == len(value['images']), 'Invalid scan inventory')
        write(args.output, value)
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
            stream.write('matrix=' + json.dumps({'include': [{'id': row['id']} for row in value['images']]}) + '\n')
    else:
        value = read(args.inventory)
        release = load_release(client, value, args.output / 'ci-release.json')
        scan_one(client, value, release, args.identity, args.output)


if __name__ == '__main__':
    main()
