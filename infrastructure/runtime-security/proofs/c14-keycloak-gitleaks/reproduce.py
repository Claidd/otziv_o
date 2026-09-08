"""Causal pinned-engine checks for exact public C14 metadata exceptions only."""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, tomllib, uuid

REPO = Path(__file__).resolve().parents[4]
ENGINE = 'ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
parser = argparse.ArgumentParser()
parser.add_argument('--config', type=Path, default=REPO / '.gitleaks.toml')
parser.add_argument('--output', type=Path)
options = parser.parse_args()
candidate = options.config.read_bytes()
baseline = candidate.split(b'\n# C14 reviewed public metadata only:', 1)[0]
assert baseline != candidate, 'Reviewed C14 blocks missing'
scratch = REPO / '.codex-tmp' / ('kc-meta-' + uuid.uuid4().hex[:12])
scratch.mkdir(parents=True)
results, records, groups = [], [], {}
sha = lambda value: hashlib.sha256(value).hexdigest()
native = lambda path: Path('\\\\?\\' + str(path.resolve())) if os.name == 'nt' else path
def unquote_regex(value):
    result, escaped = [], False
    for char in value:
        if escaped:
            result.append(char); escaped = False
        elif char == chr(92):
            escaped = True
        else:
            result.append(char)
    assert not escaped
    return ''.join(result)

for block in tomllib.loads(candidate.decode())['allowlists']:
    if not block.get('description', '').startswith('Exact public C14 migration metadata '):
        continue
    assert block['condition'] == 'AND' and block['regexTarget'] in ['line', 'match']
    assert len(block['paths']) == len(block['targetRules']) == 1
    rule, target = block['targetRules'][0], block['regexTarget']
    assert rule in ['generic-api-key', 'sentry-access-token']
    pattern = block['paths'][0]
    assert pattern.startswith(r'^(?:/repo/)?(?:\.gitleaks-staged-index-[0-9a-fA-F-]{36}/)?')
    path = unquote_regex(pattern[pattern.index('infrastructure/'):-1])
    assert path.startswith(('infrastructure/keycloak/security-generation/c14-migration-fix/proofs/security-v3/',
                            'infrastructure/runtime-security/proofs/c14-keycloak-published/'))
    source = (REPO / path).read_text()
    for regex in block['regexes']:
        assert regex.startswith(r'^\n?') and regex.endswith(r'\r?$') if target == 'line' else regex.startswith('^') and regex.endswith('$')
        escaped = regex[4:-4] if target == 'line' else regex[1:-1]
        value = unquote_regex(escaped)
        assert re.fullmatch(regex, value) and (value in source.splitlines() if target == 'line' else value in source)
        tokens = re.findall(r'[a-f0-9]{64}', value)
        if tokens:
            changed = tokens[0]
        elif 'keycloak-crypto-fips1402' in value:
            changed = 'keycloak-crypto-fips1402'
        else:
            changed = re.search(r'(?:libgssapi-krb5-2|libkeyutils1):(amd64\.[a-z0-9]+)', value)[1]
        row = dict(path=path, rule=rule, target=target, value=value, changed=changed)
        records.append(row)
        groups.setdefault((path, rule), row)

public = {}
for path in sorted({row['path'] for row in records}):
    rows = [row for row in records if row['path'] == path]
    values = sorted({row['value'] for row in rows})
    public[path] = (('{' + ','.join('"' + value for value in values) + '}\n') if rows[0]['target'] == 'match'
                    else '\n'.join(values) + '\n').encode()

def run(label, files, config, detected, rule=None, field=None, mode='dir', staged=False):
    directory = scratch / sha(label.encode())[:8]
    fixture, output = directory / 'repo', directory / 'output'
    fixture.mkdir(parents=True); output.mkdir()
    for path, content in files.items():
        destination = native(fixture / path)
        destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(content)
    (fixture / '.gitleaks.toml').write_bytes(config)
    target = '.'
    if staged:
        stage = fixture / ('.gitleaks-staged-index-' + str(uuid.uuid4()))
        for path, content in files.items():
            destination = native(stage / path)
            destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(content)
        target = '/repo/' + stage.name
    if mode == 'git':
        def git(*args): subprocess.run(['git', '-C', str(fixture), *args], check=True, capture_output=True)
        git('init', '--quiet'); git('config', 'core.longpaths', 'true'); git('config', 'core.autocrlf', 'false')
        git('config', 'core.hooksPath', '/dev/null'); git('config', 'user.name', 'Unbound Fixture')
        git('config', 'user.email', 'fixture@example.invalid'); git('add', '--all')
        git('commit', '--quiet', '-m', 'Unbound public metadata fixture')
    name = 'otziv-c14-meta-' + uuid.uuid4().hex
    command = ['docker', 'run', '--rm', '--name', name, '--label', 'com.otziv.gitleaks-proof=' + name,
               '--pull', 'never', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
               '--security-opt', 'no-new-privileges:true', '--tmpfs', '/tmp:rw,nosuid,size=64m',
               '--memory', '256m', '--cpus', '1', '--pids-limit', '64',
               '--mount', 'type=bind,source=' + str(fixture) + ',target=/repo,readonly',
               '--mount', 'type=bind,source=' + str(output) + ',target=/proof', '--workdir', '/repo',
               '--env', 'GIT_CONFIG_COUNT=1', '--env', 'GIT_CONFIG_KEY_0=safe.directory', '--env', 'GIT_CONFIG_VALUE_0=/repo',
               ENGINE, mode, '--config', '/repo/.gitleaks.toml', '--redact', '--no-banner',
               '--report-format', 'json', '--report-path', '/proof/report.json', target]
    try:
        process = subprocess.run(command, capture_output=True, timeout=120)
    except subprocess.TimeoutExpired:
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
        raise
    (output / 'engine.log').write_bytes(process.stdout + process.stderr)
    raw = (output / 'report.json').read_bytes(); findings = json.loads(raw)
    assert process.returncode == (1 if detected else 0), (label, process.returncode)
    assert bool(findings) == detected, label
    if rule: assert any(item['RuleID'] == rule for item in findings), (label, rule)
    if field: assert any(field in item['Match'].lower() for item in findings), (label, 'unknown field not detected')
    receipt = dict(case=label, result='PASS', mode=mode, findings=len(findings),
                   rules=sorted({item['RuleID'] for item in findings}), reportSha256=sha(raw), configSha256=sha(config))
    results.append(receipt); print(json.dumps(receipt), flush=True)
    return findings

run('baseline-public', public, baseline, True)
run('candidate-public', public, candidate, False)
run('candidate-public-crlf', {path: value.replace(b'\n', b'\r\n') for path, value in public.items()}, candidate, False)
one = next(row for row in records if row['target'] == 'line' and re.fullmatch('[a-f0-9]{64}', row['changed']))
sent = next(row for row in records if row['rule'] == 'sentry-access-token')
# A deterministic unbound value; confirm the original detector recognizes it
# before using it as a negative control (default detectors contain stopwords).
token = sha(b'otziv C14 unbound negative control 2026-09-08 0')
probe = ('{"api_key":"' + token + '"}\n').encode()
sentry_probe = ('{"sentry":"' + token + '"}\n').encode()
run('baseline-unbound-controls', {one['path']: probe}, baseline, True, 'generic-api-key', 'api_key')
for index, row in enumerate(groups.values()):
    replacement = row['changed'][:-1] + ('1' if row['changed'][-1] != '1' else '2')
    run('changed-value-' + str(index), {row['path']: public[row['path']].replace(row['changed'].encode(), replacement.encode())}, candidate, True, row['rule'])
run('same-line-unknown', {one['path']: (one['value'] + ' "api_key":"' + token + '"\n').encode()}, candidate, True, 'generic-api-key', 'api_key')
run('before-unknown', {one['path']: probe + (one['value'] + '\n').encode()}, candidate, True, 'generic-api-key', 'api_key')
run('after-unknown', {one['path']: (one['value'] + '\n').encode() + probe}, candidate, True, 'generic-api-key', 'api_key')
run('provenance-unknown', {sent['path']: public[sent['path']].rstrip() + sentry_probe}, candidate, True, 'sentry-access-token', 'sentry')
run('other-path', {'elsewhere/' + one['path']: public[one['path']]}, candidate, True, 'generic-api-key')
run('provenance-other-path', {'elsewhere/' + sent['path']: public[sent['path']]}, candidate, True, 'sentry-access-token')
independent = ('\n[[rules]]\nid="otziv-independent-control"\ndescription="Unbound public metadata control"\nregex=\'\'\'' + one['changed'] + '\'\'\'\n').encode()
run('other-detector', {one['path']: public[one['path']]}, candidate + independent, True, 'otziv-independent-control')
run('staged-absolute', public, candidate, False, staged=True)
run('git-public', public, candidate, False, mode='git')
run('git-unknown', {sent['path']: public[sent['path']].rstrip() + sentry_probe}, candidate, True, 'sentry-access-token', 'sentry', mode='git')
summary = dict(schema='otziv-c14-public-metadata-gitleaks-causal-v1', result='PASS', engine=ENGINE,
               configSha256=sha(candidate), originalConfigurationPrefixSha256=sha(baseline),
               groups=len(groups), exactSelectors=len(records), cases=results, syntheticTokens='Deterministic unbound bytes; reports redacted.',
               reproducerSha256=sha(Path(__file__).read_bytes()))
destination = options.output or scratch / 'result.json'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'result':'PASS','cases':len(results),'groups':len(groups),'exactSelectors':len(records)}))
