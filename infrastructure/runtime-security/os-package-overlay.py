"""Template for an official OS package overlay over the accepted Keycloak/Alloy image.

One reviewed JSON spec (os-overlay-<generation>-<component>.json) drives every step
of the C45/C48 procedure. See OS_PACKAGE_OVERLAY_RUNBOOK.md.

  pin            signed-index lookup, exact .deb download and dpkg-deb inventory;
                 writes parent snapshot, package inventory, held spec, recipe, manifest
  render         regenerate the recipe and publication manifest from the spec
  wire           register the spec, add the workflow option and line-ending rules
  compress       store scanner-matching or oversized public proofs compressed
  inspect        stopped-image comparison of parent and published candidate (no execution)
  verify-runtime offline re-verification of a recorded inspection inventory
  capture-window internal-only capture window around the unchanged rehearsal capture
  accept         bind proofs into acceptance.json and the activation entry
  activate       switch the activation index and the three Compose files

Only the local Docker engine is used. Nothing here publishes or deploys.
"""
import argparse, copy, datetime, fnmatch, gzip, hashlib, io, json, pathlib, re, shutil, subprocess, sys, tarfile, tempfile, time
import urllib.request, uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREFIX = 'infrastructure/runtime-security/'
SPEC_SCHEMA = 'otziv-os-package-overlay-spec-v1'
REGISTRY = PREFIX + 'os-package-overlays.json'
WORKFLOW = '.github/workflows/quality-gates.yml'
COMPOSE = ['compose.yaml', 'compose.prod-local.yaml', 'docker-compose.yaml']
METADATA = {'etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/dpkg/status',
            'var/lib/dpkg/status-old', 'var/log/dpkg.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend'}
GENERATED = {'etc/hosts', 'etc/hostname', 'etc/resolv.conf', '.dockerenv'}
FIELDS = ['User', 'WorkingDir', 'Entrypoint', 'Cmd', 'Env', 'Healthcheck', 'ExposedPorts', 'Volumes', 'StopSignal', 'Labels']
PROTECTED = {'keycloak': ['opt/keycloak/providers/otziv-security-generation.jar', 'opt/keycloak/bin/kc.sh'],
             'alloy': ['usr/bin/alloy', 'usr/share/otziv-build/alloy.packages.txt']}
# Historical C35 issuer paired with the accepted C36 PostgreSQL acceptance (see guava-c43-activation.mjs).
HISTORICAL_PAIR = ('ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9',
                   'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b')
ISOLATION = ['--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
             '--memory', '512m', '--pids-limit', '64', '--user', '65534:65534']
REFERENCE = re.compile(r'ghcr\.io/claidd/otziv-security@sha256:[a-f0-9]{64}')
sha = lambda value: hashlib.sha256(value).hexdigest()
now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
dumps = lambda value: (json.dumps(value, indent=2) + '\n').encode()


# ---------------------------------------------------------------- spec

def load_spec(path, root=ROOT):
    raw = json.loads(pathlib.Path(path).read_bytes())
    if raw.get('schema') != SPEC_SCHEMA: raise SystemExit('overlay_spec_schema')
    if not re.fullmatch(r'c[1-9][0-9]*', raw.get('generation', '')) or raw.get('component') not in PROTECTED:
        raise SystemExit('overlay_spec_identity')
    spec = copy.deepcopy(raw)
    spec['set'] = raw['generation'] + '-' + raw['component']
    spec['namePrefix'] = raw.get('namePrefix') or 'os-' + raw['generation']
    spec['schemaPrefix'] = 'otziv-' + spec['namePrefix']
    spec['acceptanceKey'] = raw.get('acceptanceKey') or 'packageRefreshAcceptance'
    spec['packageInventory'].setdefault('schema', 'otziv-official-os-packages-v1')
    for item in spec['packages']: item.setdefault('staging', '/tmp/otziv-' + item['name'] + '.deb')
    spec['proofRoot'] = PREFIX + 'proofs/' + spec['set'] + '/'
    spec['manifestPath'] = PREFIX + 'reviewed-images-' + spec['set'] + '.json'
    spec['context'] = PREFIX + 'builds/' + spec['set']
    spec['dockerfile'] = spec['context'] + '/Dockerfile'
    proofs = ['inspection/inspection.json', 'inspection/runtime.json.gz', 'executed-inspection.py.txt',
              'executed-runtime-inventory.py.txt']
    if raw.get('rehearsal'): proofs += ['rehearsal/capture.json', 'rehearsal/replay.json', 'executed-rehearsal.py.txt']
    if (raw.get('rehearsal') or {}).get('captureWindow'): proofs += ['rehearsal/capture-window.json', 'executed-capture-window.py.txt']
    spec['proofs'] = proofs
    spec['specPath'] = PREFIX + 'os-overlay-' + spec['set'] + '.json'
    return spec


def schema(spec, kind): return spec['schemaPrefix'] + '-' + kind + '-v1'


def docker(*args, timeout=300, check=True, network_input=None):
    result = subprocess.run(['docker', *args], capture_output=True, timeout=timeout, input=network_input)
    if check and result.returncode: raise RuntimeError('docker_failed:' + ' '.join(args[:3]) + ':' + result.stderr.decode(errors='replace')[-400:])
    return result.stdout


def control_fields(text):
    values, key = {}, None
    for line in text.splitlines():
        if line[:1] in (' ', '\t'):
            values[key] += '\n' + line
        elif line:
            key, value = line.split(':', 1)
            values[key] = value.strip()
    return values


# ---------------------------------------------------------------- recipe and manifest

def render_dockerfile(spec):
    names = sorted(spec['packages'], key=lambda item: item['name'])
    stagings = ' '.join(item['staging'] for item in spec['packages'])
    query = lambda item, version: "    test \"$(dpkg-query -W -f='${Version}' %s)\" = '%s'; \\" % (item['name'], version)
    lines = ['# ' + spec['recipeComment'], 'FROM ' + spec['parent']['reference']]
    if spec['runtimeUser']: lines.append('USER 0')
    for item in spec['packages']:
        lines += ['ADD --checksum=sha256:%s \\' % item['sha256'], '    %s %s' % (item['url'], item['staging'])]
    lines.append('RUN --network=none set -eu; \\')
    lines += [query(item, item['previousVersion']) for item in names]
    lines.append('    dpkg -i %s; \\' % stagings)
    lines += [query(item, item['version']) for item in names]
    lines.append('    dpkg --verify %s; \\' % ' '.join(item['name'] for item in names))
    lines.append('    rm ' + stagings)
    if spec['runtimeUser']: lines.append('USER ' + spec['runtimeUser'])
    lines.append('LABEL ' + ' '.join('%s="%s"' % pair for pair in spec['labels'].items()))
    return ('\n'.join(lines) + '\n').encode()


def render_manifest(spec, root, recipe):
    parent = json.loads((root / spec['parent']['activation']['path']).read_bytes())
    manifest = json.loads((root / parent['manifest']['path']).read_bytes())
    image = manifest['images'][0]
    if image['component'] != spec['component'] or len(manifest['images']) != 1: raise SystemExit('overlay_parent_manifest')
    manifest['publicationSet'] = spec['set']
    image['context'], image['dockerfile'] = spec['context'], spec['dockerfile']
    image['candidateBaseRef'] = spec['parent']['reference']
    image['dockerfileSha256'] = sha(recipe)
    return dumps(manifest)


def render(spec, root, check=False):
    recipe = render_dockerfile(spec)
    outputs = {spec['dockerfile']: recipe, spec['manifestPath']: render_manifest(spec, root, recipe)}
    for path, data in outputs.items():
        target = root / path
        if check:
            actual = target.read_bytes().replace(b'\r\n', b'\n') if target.exists() else None
            if actual != data: raise SystemExit('overlay_render_mismatch:' + path)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    return {path: sha(data) for path, data in outputs.items()}


# ---------------------------------------------------------------- pin

def archive_tar(stream):
    rows, texts = {}, {}
    with tarfile.open(fileobj=io.BytesIO(stream), mode='r:') as archive:
        for member in archive:
            name = member.name.removeprefix('./').rstrip('/')
            if name in ('', '.'): continue
            if name.startswith('/') or '..' in name.split('/'): raise ValueError('overlay_archive_path')
            row = {'mode': member.mode, 'uid': member.uid, 'gid': member.gid}
            if member.isfile():
                content = archive.extractfile(member).read()
                row.update(kind='file', sha256=sha(content)); texts[name] = content
            elif member.issym(): row.update(kind='symlink', target=member.linkname)
            elif member.islnk(): row.update(kind='hardlink', target=member.linkname.removeprefix('./'))
            elif member.isdir(): row['kind'] = 'directory'
            else: raise ValueError('overlay_unexpected_archive_member')
            if name in rows: raise ValueError('overlay_duplicate_archive_member')
            rows[name] = row
    return rows, texts


def current_activation(root, component):
    index = json.loads((root / PREFIX / 'reviewed-image-activations.json').read_bytes())
    entries = [entry for entry in index['images'] if entry['component'] == component]
    if len(entries) != 1: raise SystemExit('overlay_activation_ambiguous')
    return entries[0]


def pin(args):
    root, component, generation = ROOT, args.component, args.generation
    set_name = generation + '-' + component
    paths = {'spec': PREFIX + 'os-overlay-' + set_name + '.json', 'inventory': PREFIX + set_name + '-os-packages.json',
             'parent': PREFIX + set_name + '-parent.json'}
    for path in paths.values():
        if (root / path).exists(): raise SystemExit('overlay_output_exists:' + path)
    parent = current_activation(root, component)
    parent_bytes = dumps(parent)
    publication = json.loads((root / parent['publication']['path']).read_bytes())
    reference = parent['reference']
    docker('pull', '--quiet', reference, timeout=900)
    config = json.loads(docker('image', 'inspect', reference))[0]['Config']
    wanted = [item.split('=', 1) for item in args.package]
    if any(len(item) != 2 for item in wanted): raise SystemExit('overlay_package_argument')
    # 1. Ubuntu/Debian archive index, authenticated by the parent's own keyring.
    script = ('set -eu; apt-get -qq update >/dev/null; apt-get download --print-uris "$@"; echo "--"; '
              'for p in "$@"; do apt-cache show --no-all-versions "$p"; echo; done')
    printed = docker('run', '--rm', '--user', '0', '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--cap-add', 'DAC_OVERRIDE',
                     '--cap-add', 'FOWNER', '--cap-add', 'SETUID', '--cap-add', 'SETGID', '--security-opt',
                     'no-new-privileges:true', '--entrypoint', 'sh', reference, '-c', script, 'pin',
                     *[name + '=' + version for name, version in wanted], timeout=900).decode()
    printed_uris, _, records_text = printed.partition('\n--\n')
    index = {}
    for block in records_text.strip().split('\n\n'):
        fields = control_fields(block)
        if fields.get('Filename'): index[fields['Filename'].rsplit('/', 1)[-1]] = fields
    uris = {}
    for line in printed_uris.splitlines():
        match = re.fullmatch(r"'(https?://[^']+)' (\S+) ([0-9]+) SHA512:([a-f0-9]{128})", line.strip())
        if not match: continue
        url = match.group(1).replace('http://', 'https://', 1)
        record = index.get(url.rsplit('/', 1)[-1])
        if not record or int(record['Size']) != int(match.group(3)) or not re.fullmatch(r'[a-f0-9]{64}', record.get('SHA256', '')):
            raise SystemExit('overlay_signed_index_inconsistent:' + url)
        uris[url.rsplit('/', 1)[-1]] = {'url': url, 'size': int(match.group(3)), 'sha512': match.group(4),
                                        'sha256': record['SHA256'], 'filename': record['Filename']}
    # 2. Installed versions and dpkg configuration of the exact parent, without network.
    inspect_script = ('for p in "$@"; do dpkg-query -W -f=\'${Package}\\t${Version}\\n\' "$p"; done; '
                      'sha256sum /etc/dpkg/dpkg.cfg /etc/dpkg/dpkg.cfg.d/*')
    observed = docker('run', '--rm', *ISOLATION, '--entrypoint', 'sh', reference, '-c', inspect_script, 'state',
                      *[name for name, _ in wanted]).decode().splitlines()
    installed = dict(line.split('\t') for line in observed if '\t' in line)
    dpkg_config = {line.split()[1].lstrip('/'): line.split()[0] for line in observed if re.match(r'[a-f0-9]{64}  /etc/dpkg/', line)}
    directory = pathlib.Path(args.deb_directory).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    records, packages = [], []
    for name, version in wanted:
        file_version = version.split(':', 1)[-1]
        filename = '%s_%s_amd64.deb' % (name, file_version)
        signed = uris.get(filename)
        if not signed or not signed['url'].endswith('/' + filename): raise SystemExit('overlay_signed_index_missing:' + name)
        with urllib.request.urlopen(signed['url'], timeout=180) as response: data = response.read()
        if len(data) != signed['size'] or sha(data) != signed['sha256'] or hashlib.sha512(data).hexdigest() != signed['sha512']:
            raise SystemExit('overlay_download_mismatch:' + name)
        (directory / filename).write_bytes(data)
        mount = ['--mount', 'type=bind,source=' + str(directory) + ',target=/debs,readonly']
        run = ['run', '--rm', *ISOLATION, *mount, '--entrypoint', 'dpkg-deb', reference]
        payload, _ = archive_tar(docker(*run, '--fsys-tarfile', '/debs/' + filename))
        control, texts = archive_tar(docker(*run, '--ctrl-tarfile', '/debs/' + filename))
        fields = control_fields(texts['control'].decode())
        if fields['Package'] != name or fields['Version'] != version or fields['Architecture'] != 'amd64':
            raise SystemExit('overlay_control_identity:' + name)
        info = name + (':amd64' if fields.get('Multi-Arch') == 'same' else '')
        previous = installed.get(name)
        if not previous or previous == version: raise SystemExit('overlay_not_an_update:' + name)
        records.append({'package': name, 'version': version, 'previousVersion': previous, 'architecture': 'amd64',
                        'dpkgInfoName': info, 'url': signed['url'], 'sha256': signed['sha256'], 'bytes': len(data),
                        'controlFields': {key: fields.get(key) for key in ('Package', 'Version', 'Architecture', 'Depends')},
                        'conffiles': sorted(texts.get('conffiles', b'').decode().split()), 'control': control, 'payload': payload,
                        'signedArchiveIndex': {'verifiedWith': 'apt-get update, apt-get download --print-uris and apt-cache show in the disposable parent image',
                                               'filename': signed['filename'], 'size': signed['size'], 'sha256': signed['sha256'],
                                               'sha512': signed['sha512']}})
        changed = next((path for path, row in payload.items() if row['kind'] == 'file' and path.startswith('usr/lib/')), None) \
            or next(path for path, row in payload.items() if row['kind'] == 'file')
        packages.append({'name': name, 'dpkgInfoName': info, 'version': version, 'previousVersion': previous,
                         'sha256': signed['sha256'], 'url': signed['url'], 'staging': '/tmp/otziv-' + name + '.deb', '_changed': changed})
    distribution = args.distribution
    inventory = {'schema': 'otziv-official-os-packages-v1', 'component': component, 'advisory': args.advisory,
                 'distribution': distribution, 'parent': reference, 'packages': records}
    inventory_bytes = (json.dumps(inventory, indent=2, sort_keys=True) + '\n').encode()
    versions = {item['version'] for item in packages}
    label_value = versions.pop() if len(versions) == 1 else ','.join(item['name'] + '=' + item['version'] for item in packages)
    parent_generation = re.search(r'reviewed-images-(c[0-9]+)-', parent['manifest']['path']).group(1).upper()
    comment = ('Preserve the accepted %s issuer and all Java/provider bytes; update only official OS packages.' if component == 'keycloak'
               else 'Preserve the accepted %s Alloy binary and runtime; update only official OS packages.') % parent_generation
    spec = {'schema': SPEC_SCHEMA, 'generation': generation, 'component': component, 'status': 'held', 'advisory': args.advisory,
            'distribution': distribution,
            'parent': {'reference': reference, 'configId': publication['imageId'],
                       'activation': {'path': paths['parent'], 'sha256': sha(parent_bytes)}},
            'recipeComment': comment, 'runtimeUser': config.get('User') or None,
            'labels': {'otziv.' + args.label_name: label_value, 'otziv.' + args.label_name + '-advisory': args.advisory},
            'packages': [{key: value for key, value in item.items() if not key.startswith('_')} for item in packages],
            'packageInventory': {'path': paths['inventory'], 'sha256': sha(inventory_bytes), 'schema': 'otziv-official-os-packages-v1'},
            'dpkgConfig': dpkg_config, 'protectedPaths': PROTECTED[component],
            'changedPrefixes': [item['_changed'] for item in packages]}
    if component == 'keycloak':
        database = current_activation(root, 'postgres')
        database_config = json.loads((root / database['publication']['path']).read_bytes())['imageId']
        spec['rehearsal'] = {'sourcePostgres': database['reference'], 'sourcePostgresConfig': database_config,
                             'sourceKeycloak': reference, 'sourceKeycloakConfig': publication['imageId'],
                             'replayPostgres': database['reference'], 'replayPostgresConfig': database_config,
                             'tableCount': args.table_count, 'captureWindow': True}
        spec['coupling'] = {'postgresReference': database['reference'], 'postgresConfigId': database_config,
                            'historicalKeycloakReference': HISTORICAL_PAIR[0], 'historicalKeycloakConfigId': HISTORICAL_PAIR[1]}
    else:
        adjudication = json.loads((root / parent['publication']['path']).parent.joinpath('vulnerabilities.adjudications.json').read_bytes())
        spec['alloyAdjudication'] = re.fullmatch(r'otziv-alloy-(c[0-9]+)-adjudication-v1', adjudication['alloy']['schema']).group(1)
    spec['compressedProofs'] = {}
    (root / paths['parent']).write_bytes(parent_bytes)
    (root / paths['inventory']).write_bytes(inventory_bytes)
    (root / paths['spec']).write_bytes(dumps(spec))
    rendered = render(load_spec(root / paths['spec']), root)
    print(json.dumps({'spec': paths['spec'], 'inventory': paths['inventory'], 'parent': paths['parent'], 'rendered': rendered,
                      'packages': {item['name']: [item['previousVersion'], item['version']] for item in packages}}, indent=2))


# ---------------------------------------------------------------- wiring

def wire(spec, root):
    spec_path = spec['specPath']
    spec_bytes = (root / spec_path).read_bytes()
    registry_path = root / REGISTRY
    registry = json.loads(registry_path.read_bytes())
    entries = [item for item in registry['overlays'] if item['path'] != spec_path]
    entries.append({'path': spec_path, 'sha256': sha(spec_bytes)})
    registry['overlays'] = sorted(entries, key=lambda item: item['path'])
    registry_path.write_bytes(dumps(registry))
    workflow_path = root / WORKFLOW
    workflow = workflow_path.read_text(encoding='utf-8')
    option = '          - ' + spec['set'] + '\n'
    if option not in workflow:
        anchor = '  pull_request:\n'
        if workflow.count(anchor) != 1 or not workflow.split(anchor)[0].endswith('\n') or \
                not re.search(r'\n          - c[0-9]+-[a-z0-9-]+\n$', workflow.split(anchor)[0]):
            raise SystemExit('overlay_workflow_anchor')
        workflow = workflow.replace(anchor, option + anchor, 1)
        workflow_path.write_text(workflow, encoding='utf-8', newline='\n')
    attributes_path = root / '.gitattributes'
    attributes = attributes_path.read_text(encoding='utf-8')
    block = ('\n# %s official OS package overlay keeps exact recipe, manifest, inventory and proof bytes.\n'
             '%s/** text eol=lf\n%s text eol=lf\n%s text eol=lf\n%s text eol=lf\n%s text eol=lf\n%s** -text\n') % (
        spec['set'].upper(), spec['context'], spec['manifestPath'], spec['packageInventory']['path'], spec['specPath'],
        spec['parent']['activation']['path'], spec['proofRoot'])
    if block not in attributes:
        if not attributes.endswith('\n'): attributes += '\n'
        attributes_path.write_text(attributes + block, encoding='utf-8', newline='\n')
    return {'registry': REGISTRY, 'specSha256': sha(spec_bytes), 'workflowOption': spec['set']}


# ---------------------------------------------------------------- compression

def compress(spec_path, files, root):
    spec_file = root / spec_path
    raw = json.loads(spec_file.read_bytes())
    spec = load_spec(spec_file)
    compressed = raw.setdefault('compressedProofs', {})
    for path in files:
        if not path.startswith(spec['proofRoot']) or not path.endswith('.json'): raise SystemExit('overlay_compress_path:' + path)
        source = root / path
        data = source.read_bytes()
        (root / (path + '.gz')).write_bytes(gzip.compress(data, mtime=0))
        if gzip.decompress((root / (path + '.gz')).read_bytes()) != data: raise SystemExit('overlay_compress_roundtrip')
        source.unlink()
        compressed[path] = sha(data)
    spec_file.write_bytes(dumps(raw))
    return compressed


# ---------------------------------------------------------------- stopped-image inspection

def captured_name(name, spec):
    return (name == 'var/lib/dpkg/status' or name.startswith('etc/dpkg/dpkg.cfg')
            or any(name == 'var/lib/dpkg/info/' + item['dpkgInfoName'] + '.list' for item in spec['packages']))


def inspect_image(reference, directory, spec):
    image = json.loads(docker('image', 'inspect', reference))[0]
    owner = 'otziv-%s-inspection-%s' % (spec['namePrefix'], uuid.uuid4().hex)
    docker('create', '--name', owner, '--label', 'com.otziv.overlay-inspection.owner=' + owner,
           '--network', 'none', '--entrypoint', '/not-executed', reference)
    try:
        path = directory / (owner + '.tar')
        docker('export', '--output', str(path), owner)
        inventory, captured = {}, {}
        with tarfile.open(path) as archive:
            for member in archive:
                name = member.name.removeprefix('./').rstrip('/')
                if not name or name.startswith('/') or '..' in name.split('/'): raise ValueError('overlay_export_path')
                if name in GENERATED or name == 'dev' or name.startswith('dev/'): continue
                row = {'mode': member.mode, 'uid': member.uid, 'gid': member.gid}
                if member.isfile():
                    digest, content, capture = hashlib.sha256(), bytearray(), captured_name(name, spec)
                    with archive.extractfile(member) as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                            digest.update(chunk)
                            if capture: content.extend(chunk)
                    row.update(kind='file', sha256=digest.hexdigest())
                    if capture: captured[name] = content.decode('utf-8')
                elif member.issym() or member.islnk():
                    row.update(kind='symlink' if member.issym() else 'hardlink', target=member.linkname)
                elif member.isdir(): row['kind'] = 'directory'
                else: raise ValueError('unexpected_filesystem_object')
                if name in inventory: raise ValueError('overlay_duplicate_export_member')
                inventory[name] = row
        return {'reference': reference, 'imageId': image['Id'], 'rootfs': image['RootFS']['Layers'],
                'configuration': {key: image['Config'].get(key) for key in FIELDS},
                'inventory': inventory, 'captured': captured, 'containerExecuted': False}
    finally:
        container = json.loads(docker('inspect', owner))[0]
        if container['Config']['Labels']['com.otziv.overlay-inspection.owner'] != owner or container['State']['Running']:
            raise RuntimeError('overlay_inspection_ownership')
        docker('rm', '-v', owner)


def status_packages(status):
    result = {}
    for block in status.strip().split('\n\n'):
        fields, key = {}, None
        for line in block.splitlines():
            if line.startswith(' ') and key: fields[key] += '\n' + line
            elif ':' in line:
                key, value = line.split(':', 1); fields[key] = value.lstrip()
        if fields:
            if fields['Package'] in result: raise ValueError('overlay_duplicate_package')
            result[fields['Package']] = fields
    return result


def dpkg_filters(image):
    rules = []
    for name in sorted(n for n in image['captured'] if n.startswith('etc/dpkg/dpkg.cfg.d/')) + ['etc/dpkg/dpkg.cfg']:
        for line in image['captured'].get(name, '').splitlines():
            line = line.strip()
            if line.startswith(('path-exclude', 'path-include')):
                match = re.fullmatch(r'(path-exclude|path-include)[= ](/\S+)', line)
                if not match or set('[]\\') & set(match.group(2)): raise SystemExit('overlay_unsupported_dpkg_filter')
                rules.append(match.groups())
    return rules


def excluded(path, rules):
    result = False
    for kind, pattern in rules:
        if fnmatch.fnmatchcase('/' + path, pattern): result = kind == 'path-exclude'
    return result


def resolve(path, inventory):
    parts, resolved = path.split('/'), []
    for index, part in enumerate(parts):
        resolved.append(part)
        current = '/'.join(resolved)
        if index < len(parts) - 1 and inventory.get(current, {}).get('kind') == 'symlink':
            target = inventory[current]['target']
            joined = target.lstrip('/').split('/') if target.startswith('/') else resolved[:-1] + target.split('/')
            resolved = []
            for item in joined:
                if item == '..':
                    if not resolved: raise SystemExit('overlay_symlink_escape')
                    resolved.pop()
                elif item and item != '.': resolved.append(item)
    return '/'.join(resolved)


def verify(spec, before, after, commit, package):
    parent = spec['parent']
    assert before['reference'] == parent['reference'] and before['imageId'] in (parent['configId'], parent['reference'].split('@')[1])
    assert re.fullmatch('[a-f0-9]{40}', commit)
    assert not before['containerExecuted'] and not after['containerExecuted']
    assert after['rootfs'][:len(before['rootfs'])] == before['rootfs'] and len(after['rootfs']) > len(before['rootfs'])
    expected = copy.deepcopy(before['configuration'])
    expected['Labels'].update({**spec['labels'], 'com.otziv.publication.revision': commit})
    assert expected == after['configuration'], 'overlay_unrelated_configuration'
    assert package['schema'] == spec['packageInventory']['schema'] and package['component'] == spec['component']
    assert package['parent'] == parent['reference'] and package['advisory'] == spec['advisory']
    assert [(item['package'], item['dpkgInfoName']) for item in package['packages']] == \
        [(item['name'], item['dpkgInfoName']) for item in spec['packages']], 'overlay_package_set'
    fragments = sorted(name for name in spec['dpkgConfig'] if name.startswith('etc/dpkg/dpkg.cfg.d/'))
    for image in (before, after):
        assert sorted(name for name in image['inventory'] if name.startswith('etc/dpkg/dpkg.cfg.d/')) == fragments, 'overlay_dpkg_fragments'
        for name, digest in spec['dpkgConfig'].items():
            assert image['inventory'][name]['sha256'] == digest == sha(image['captured'][name].encode()), 'overlay_dpkg_configuration'
        assert sha(image['captured']['var/lib/dpkg/status'].encode()) == image['inventory']['var/lib/dpkg/status']['sha256']
    rules = dpkg_filters(after)
    old_packages, new_packages = [status_packages(image['captured']['var/lib/dpkg/status']) for image in (before, after)]
    names = {item['name'] for item in spec['packages']}
    assert {k: v for k, v in old_packages.items() if k not in names} == {k: v for k, v in new_packages.items() if k not in names}, \
        'overlay_unrelated_package'
    allowed = set(METADATA)
    for item, entry in zip(spec['packages'], package['packages']):
        assert old_packages[item['name']]['Version'] == item['previousVersion'] == entry['previousVersion'], 'overlay_source_package'
        assert new_packages[item['name']]['Version'] == item['version'] == entry['version'], 'overlay_target_package'
        assert new_packages[item['name']]['Status'] == 'install ok installed', 'overlay_target_package'
        assert entry['sha256'] == item['sha256'] and entry['url'] == item['url'], 'overlay_official_deb'
        prefix = 'var/lib/dpkg/info/' + item['dpkgInfoName'] + '.'
        for image in (before, after):
            assert sha(image['captured'][prefix + 'list'].encode()) == image['inventory'][prefix + 'list']['sha256']
        old_listed = {line.strip('/') for line in before['captured'][prefix + 'list'].splitlines()} - {'.', ''}
        listed = {line.strip('/') for line in after['captured'][prefix + 'list'].splitlines()} - {'.', ''}
        assert listed == set(entry['payload']), 'overlay_package_manifest'
        for path, row in entry['payload'].items():
            actual = resolve(path, after['inventory'])
            assert actual == resolve(path, before['inventory']), 'overlay_path_resolution_changed'
            allowed.add(actual)
            if row['kind'] == 'directory':
                if actual in before['inventory']: assert after['inventory'].get(actual) == before['inventory'][actual], 'overlay_existing_directory:' + path
                elif actual in after['inventory']: assert after['inventory'][actual] == row, 'overlay_official_directory:' + path
                else: assert excluded(path, rules), 'overlay_missing_directory:' + path
            elif excluded(path, rules):
                assert actual not in after['inventory'], 'overlay_filtered_path_present:' + path
                assert actual not in before['inventory'] or path in old_listed, 'overlay_filtered_path_owner:' + path
            else:
                assert after['inventory'].get(actual) == row, 'overlay_official_payload:' + path
        for name, row in entry['control'].items():
            if name != 'control':
                assert after['inventory'].get(prefix + name) == row, 'overlay_official_control:' + name
                allowed.add(prefix + name)
        allowed.add(prefix + 'list')
    changed = sorted(path for path in before['inventory'].keys() | after['inventory'].keys()
                     if before['inventory'].get(path) != after['inventory'].get(path))
    assert all(any(path.startswith(prefix) for path in changed) for prefix in spec['changedPrefixes']), 'overlay_required_change_missing'
    assert not set(changed) - allowed, 'overlay_unrelated_payload:' + ','.join(sorted(set(changed) - allowed)[:15])
    for path in spec['protectedPaths']:
        assert after['inventory'][path] == before['inventory'][path] and path not in changed, 'overlay_protected_changed:' + path
    return changed


def inspect(args):
    spec = load_spec(args.spec)
    if not REFERENCE.fullmatch(args.candidate): raise SystemExit('overlay_candidate_reference')
    temporary_parent, output = pathlib.Path(args.temporary_directory).resolve(strict=True), pathlib.Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    package_bytes = (ROOT / spec['packageInventory']['path']).read_bytes()
    if sha(package_bytes) != spec['packageInventory']['sha256']: raise SystemExit('overlay_package_inventory_digest')
    with tempfile.TemporaryDirectory(prefix='overlay-owned-', dir=temporary_parent) as directory:
        before, after = [inspect_image(reference, pathlib.Path(directory), spec) for reference in (spec['parent']['reference'], args.candidate)]
    changed = verify(spec, before, after, args.publication_commit, json.loads(package_bytes))
    payload = (json.dumps({'schema': schema(spec, 'inventory'), 'component': spec['component'], 'parent': before, 'candidate': after},
                          sort_keys=True, separators=(',', ':')) + '\n').encode()
    (output / 'runtime.json.gz').write_bytes(gzip.compress(payload, mtime=0))
    receipt = {'schema': schema(spec, 'inspection'), 'result': 'PASS', 'component': spec['component'],
               'parent': spec['parent']['reference'], 'parentImageId': before['imageId'], 'candidate': args.candidate,
               'candidateImageId': after['imageId'], 'runtimeSha256': sha(payload), 'changedFiles': changed,
               'packageInventorySha256': sha(package_bytes), 'inspectedContainersExecuted': False,
               'executedScriptSha256': sha(pathlib.Path(__file__).read_bytes()),
               'inventoryScriptSha256': sha(pathlib.Path(__file__).read_bytes())}
    (output / 'inspection.json').write_bytes(dumps(receipt))
    print(json.dumps({'result': 'PASS', 'changedFiles': len(changed)}))


# ---------------------------------------------------------------- capture window

def capture_window(args):
    spec = load_spec(args.spec)
    if spec['component'] != 'keycloak' or not spec['rehearsal'].get('captureWindow'): raise SystemExit('overlay_window_not_planned')
    project, network = 'otziv-prod-local', 'otziv-prod-local_internal_net'
    services = {'keycloak': spec['rehearsal']['sourceKeycloak'], 'keycloak-postgres': spec['rehearsal']['sourcePostgres']}
    url = 'http://localhost:8088/keycloak/realms/otziv/.well-known/openid-configuration'
    label = 'com.otziv.%s-capture.owner' % spec['namePrefix']
    commands = []

    def run(*command, allow=False):
        commands.append(['docker', *command])
        result = subprocess.run(['docker', *command], capture_output=True, timeout=180)
        if result.returncode and not allow: raise RuntimeError('docker_failed:' + ' '.join(command[:3]))
        return result

    inspect_object = lambda name, kind='container': json.loads(docker(kind, 'inspect', name))[0]

    def find(service):
        ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project,
                     '--filter', 'label=com.docker.compose.service=' + service).decode().split()
        if len(ids) != 1: raise SystemExit('overlay_window_service:' + service)
        return inspect_object(ids[0])

    def attachment(container, name):
        value = container['NetworkSettings']['Networks'].get(name)
        return None if value is None else {'aliases': sorted(value.get('Aliases') or []), 'ipAddress': value.get('IPAddress')}

    def identity(container):
        return {'id': container['Id'], 'name': container['Name'].lstrip('/'), 'imageId': container['Image'],
                'startedAt': container['State']['StartedAt'], 'restarts': container['RestartCount'],
                'running': container['State']['Running'], 'networks': sorted(container['NetworkSettings']['Networks']),
                'portBindings': container['HostConfig'].get('PortBindings') or {}}

    owner = uuid.uuid4().hex[:12]
    temporary = 'otziv-%s-capture-%s' % (spec['namePrefix'], owner)
    endpoint = subprocess.run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'],
                              capture_output=True, timeout=30).stdout.decode().strip()
    if not endpoint.startswith(('npipe://', 'unix://')): raise SystemExit('local_docker_only')
    record = {'schema': schema(spec, 'capture-window'), 'result': 'FAIL', 'productionAccess': False, 'dataWrites': False,
              'containersRestarted': False, 'originalNetwork': network, 'dockerEndpoint': endpoint.split(':', 1)[0],
              'temporaryNetwork': {'name': temporary, 'label': label + '=' + owner}, 'checkUrl': url,
              'executedScriptSha256': sha(pathlib.Path(__file__).read_bytes())}
    containers = {service: find(service) for service in services}
    original = {}
    for service, container in containers.items():
        if container['Image'] != services[service].split('@')[1]: raise SystemExit('unexpected_image:' + service)
        if not container['State']['Running'] or container['HostConfig'].get('PortBindings'): raise SystemExit('unexpected_runtime:' + service)
        if sorted(container['NetworkSettings']['Networks']) != [network]: raise SystemExit('unexpected_networks:' + service)
        original[service] = attachment(container, network)
    record['before'] = {service: {**identity(container), 'attachment': original[service]} for service, container in containers.items()}
    created, moved = False, []
    try:
        record['windowOpenedAt'] = now()
        run('network', 'create', '--internal', '--label', label + '=' + owner, temporary)
        created = True
        details = inspect_object(temporary, 'network')
        if not details['Internal'] or details['Labels'].get(label) != owner: raise RuntimeError('temporary_network_not_internal')
        record['temporaryNetwork']['internal'] = True
        for service, container in containers.items():
            run('network', 'connect', *[x for alias in original[service]['aliases'] for x in ('--alias', alias)], temporary, container['Id'])
            moved.append(service)
            run('network', 'disconnect', network, container['Id'])
        isolated = {service: inspect_object(container['Id']) for service, container in containers.items()}
        record['isolated'] = {service: {'networks': sorted(c['NetworkSettings']['Networks']), 'aliases': attachment(c, temporary)['aliases']}
                              for service, c in isolated.items()}
        if any(value['networks'] != [temporary] or value['aliases'] != original[s]['aliases'] for s, value in record['isolated'].items()):
            raise RuntimeError('window_not_isolated')
        record['captureStartedAt'] = now()
        rehearsal = ROOT / PREFIX / 'os-package-overlay-rehearsal.py'
        commands.append(['python', '-B', PREFIX + 'os-package-overlay-rehearsal.py', '--spec', spec['specPath'], 'capture',
                         '--private-directory', '<private>', '--output', spec['proofRoot'] + 'rehearsal'])
        capture = subprocess.run([sys.executable, '-B', str(rehearsal), '--spec', str(pathlib.Path(args.spec).resolve()), 'capture',
                                  '--private-directory', str(args.private_directory), '--output', str(args.output)],
                                 capture_output=True, timeout=900)
        record['captureCompletedAt'] = now()
        record['captureExitCode'] = capture.returncode
        capture_path = pathlib.Path(args.output) / 'capture.json'
        if capture_path.exists():
            record['captureSha256'] = sha(capture_path.read_bytes())
            record['captureResult'] = json.loads(capture_path.read_bytes())['result']
    finally:
        notes = []
        for service in moved:
            container, current = containers[service], inspect_object(containers[service]['Id'])
            aliases = [x for alias in original[service]['aliases'] for x in ('--alias', alias)]
            if network not in current['NetworkSettings']['Networks']:
                if run('network', 'connect', *aliases, '--ip', original[service]['ipAddress'], network, container['Id'], allow=True).returncode:
                    notes.append('fixed_address_unavailable:' + service)
                    run('network', 'connect', *aliases, network, container['Id'])
            if temporary in current['NetworkSettings']['Networks']: run('network', 'disconnect', temporary, container['Id'])
        if created:
            if inspect_object(temporary, 'network')['Labels'].get(label) != owner: raise RuntimeError('temporary_network_ownership_changed')
            run('network', 'rm', temporary)
        record['windowClosedAt'] = now()
        record['restoreNotes'] = notes
    after = {service: inspect_object(container['Id']) for service, container in containers.items()}
    record['after'] = {service: {**identity(container), 'attachment': attachment(container, network)} for service, container in after.items()}
    record['nginxReloaded'] = record['after']['keycloak']['attachment']['ipAddress'] != original['keycloak']['ipAddress']
    if record['nginxReloaded']: run('kill', '--signal', 'HUP', find('nginx')['Id'])
    status, deadline = None, time.time() + 240
    while time.time() < deadline and status != 200:
        try:
            with urllib.request.urlopen(url, timeout=5) as response: status = response.status
        except Exception as error:
            status = getattr(error, 'code', None); time.sleep(3)
    record['stackStatus'] = status
    record['temporaryNetworkRemaining'] = len(docker('network', 'ls', '-q', '--filter', 'label=' + label + '=' + owner).split())
    record['commands'] = commands
    keys = ('id', 'imageId', 'startedAt', 'restarts', 'running', 'networks', 'portBindings')
    record['restoredExactly'] = all({k: record['before'][s][k] for k in keys} == {k: record['after'][s][k] for k in keys}
                                    and record['before'][s]['attachment']['aliases'] == record['after'][s]['attachment']['aliases']
                                    for s in services)
    record['addressesPreserved'] = all(record['before'][s]['attachment']['ipAddress'] == record['after'][s]['attachment']['ipAddress']
                                       for s in services)
    ok = (record.get('captureResult') == 'PASS' and record.get('captureExitCode') == 0 and record['restoredExactly']
          and record['stackStatus'] == 200 and record['temporaryNetworkRemaining'] == 0)
    record['result'] = 'PASS' if ok else 'FAIL'
    pathlib.Path(args.record).write_bytes(dumps(record))
    print(json.dumps({key: record.get(key) for key in ('result', 'captureResult', 'restoredExactly', 'addressesPreserved',
                                                         'stackStatus', 'windowOpenedAt', 'windowClosedAt', 'restoreNotes')}))
    if not ok: sys.exit(1)


# ---------------------------------------------------------------- acceptance and activation

EXECUTED = {'executed-inspection.py.txt': ('inspection/inspection.json', 'executedScriptSha256'),
            'executed-runtime-inventory.py.txt': ('inspection/inspection.json', 'inventoryScriptSha256'),
            'executed-rehearsal.py.txt': ('rehearsal/capture.json', 'executedScriptSha256'),
            'executed-capture-window.py.txt': ('rehearsal/capture-window.json', 'executedScriptSha256')}
SOURCES = {'executed-inspection.py.txt': 'os-package-overlay.py', 'executed-runtime-inventory.py.txt': 'os-package-overlay.py',
           'executed-rehearsal.py.txt': 'os-package-overlay-rehearsal.py', 'executed-capture-window.py.txt': 'os-package-overlay.py'}


def accept(spec, root):
    proofs = root / spec['proofRoot']
    for name in spec['proofs']:
        if name in EXECUTED:
            target, (record, field) = proofs / name, EXECUTED[name]
            if not target.exists(): shutil.copyfile(root / PREFIX / SOURCES[name], target)
            if sha(target.read_bytes()) != json.loads((proofs / record).read_bytes())[field]:
                raise SystemExit('overlay_executed_script_changed:' + name)
        elif not (proofs / name).exists(): raise SystemExit('overlay_missing_proof:' + name)
    for name in ('rehearsal/capture.json', 'rehearsal/replay.json', 'rehearsal/capture-window.json', 'inspection/inspection.json'):
        if name in spec['proofs'] and json.loads((proofs / name).read_bytes())['result'] != 'PASS': raise SystemExit('overlay_proof_failed:' + name)
    draft_path, candidate_path = proofs / 'activation-draft.json', proofs / 'activation-candidate.json'
    draft = json.loads(draft_path.read_bytes())['entry'] if draft_path.exists() else \
        {k: v for k, v in json.loads(candidate_path.read_bytes()).items() if k not in (spec['acceptanceKey'], 'migrationAcceptance')}
    publication = json.loads((root / draft['publication']['path']).read_bytes())
    acceptance = {'schema': schema(spec, 'acceptance'), 'result': 'PASS', 'component': spec['component'],
                  'reference': publication['reference'], 'imageConfigId': publication['imageId'],
                  'parentReference': spec['parent']['reference'], 'parentConfigId': spec['parent']['configId'],
                  'advisory': spec['advisory'], 'commit': publication['commit'], 'run': publication['run'], 'attempt': publication['attempt'],
                  'packages': [{'name': item['name'], 'version': item['version'], 'sha256': item['sha256']} for item in spec['packages']],
                  'files': {spec['proofRoot'] + name: sha((proofs / name).read_bytes()) for name in spec['proofs']}}
    acceptance_bytes = dumps(acceptance)
    (proofs / 'acceptance.json').write_bytes(acceptance_bytes)
    bound = {'path': spec['proofRoot'] + 'acceptance.json', 'sha256': sha(acceptance_bytes)}
    entry = {**draft, spec['acceptanceKey']: bound}
    if spec['component'] == 'keycloak': entry['migrationAcceptance'] = dict(bound)
    candidate_path.write_bytes(dumps(entry))
    if draft_path.exists(): draft_path.unlink()
    return entry


def activate(spec, root):
    entry = json.loads((root / spec['proofRoot'] / 'activation-candidate.json').read_bytes())
    index_path = root / PREFIX / 'reviewed-image-activations.json'
    index = json.loads(index_path.read_bytes())
    positions = [i for i, item in enumerate(index['images']) if item['component'] == spec['component']]
    if len(positions) != 1 or index['images'][positions[0]]['reference'] != spec['parent']['reference']:
        raise SystemExit('overlay_activation_parent_not_current')
    index['images'][positions[0]] = entry
    for path in COMPOSE:
        data = (root / path).read_bytes()
        if data.count(spec['parent']['reference'].encode()) != 1: raise SystemExit('overlay_compose_reference:' + path)
        (root / path).write_bytes(data.replace(spec['parent']['reference'].encode(), entry['reference'].encode()))
    index_path.write_bytes(dumps(index))
    return entry['reference']


# ---------------------------------------------------------------- command line

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('pin')
    p.add_argument('--component', choices=sorted(PROTECTED), required=True)
    p.add_argument('--generation', required=True)
    p.add_argument('--advisory', required=True)
    p.add_argument('--distribution', required=True, help='for example ubuntu-22.04 or ubuntu-24.04')
    p.add_argument('--package', action='append', required=True, help='NAME=VERSION, in dpkg install order')
    p.add_argument('--label-name', required=True, help='image label suffix, for example apt')
    p.add_argument('--deb-directory', required=True, help='new empty directory outside the repository')
    p.add_argument('--table-count', type=int, default=102)
    for name in ('render', 'wire', 'accept', 'activate'):
        p = commands.add_parser(name)
        p.add_argument('--spec', required=True)
        p.add_argument('--root', default=str(ROOT))
        if name == 'render': p.add_argument('--check', action='store_true')
    p = commands.add_parser('compress')
    p.add_argument('--spec', required=True)
    p.add_argument('--root', default=str(ROOT))
    p.add_argument('files', nargs='+')
    p = commands.add_parser('inspect')
    for name in ('spec', 'candidate', 'publication-commit', 'temporary-directory', 'output'): p.add_argument('--' + name, required=True)
    p = commands.add_parser('verify-runtime')
    for name in ('spec', 'runtime', 'commit'): p.add_argument('--' + name, required=True)
    p.add_argument('--root', default=str(ROOT))
    p = commands.add_parser('capture-window')
    for name in ('spec', 'private-directory', 'output', 'record'): p.add_argument('--' + name, required=True)
    args = parser.parse_args(argv)
    root = pathlib.Path(getattr(args, 'root', ROOT)).resolve()
    if args.command == 'pin':
        if not re.fullmatch(r'c[1-9][0-9]*', args.generation): raise SystemExit('overlay_generation')
        if not re.fullmatch(r'[a-z0-9-]+', args.label_name): raise SystemExit('overlay_label_name')
        pin(args)
    elif args.command == 'render':
        print(json.dumps(render(load_spec(args.spec), root, args.check), indent=2))
    elif args.command == 'wire':
        print(json.dumps(wire(load_spec(args.spec), root), indent=2))
    elif args.command == 'compress':
        print(json.dumps(compress(args.spec, args.files, root), indent=2))
    elif args.command == 'inspect':
        inspect(args)
    elif args.command == 'verify-runtime':
        spec = load_spec(args.spec)
        runtime = json.loads(gzip.decompress(pathlib.Path(args.runtime).read_bytes()))
        package = json.loads((root / spec['packageInventory']['path']).read_bytes())
        if runtime['schema'] != schema(spec, 'inventory') or runtime['component'] != spec['component']: raise SystemExit('overlay_runtime_schema')
        print(json.dumps(verify(spec, runtime['parent'], runtime['candidate'], args.commit, package)))
    elif args.command == 'capture-window':
        capture_window(args)
    elif args.command == 'accept':
        print(json.dumps(accept(load_spec(args.spec), root), indent=2))
    elif args.command == 'activate':
        print(activate(load_spec(args.spec), root))


if __name__ == '__main__':
    main()
