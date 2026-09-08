"""Causal Docker/Git controls for three exact public class-digest matches.

Only public retained provenance files enter owned fixtures. The caller's Git
index/worktree is never modified; reports redact all detected values.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import tomllib
import uuid

ROOT = Path(__file__).resolve().parents[4]
IMAGE = 'ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
PATHS = [
    'infrastructure/keycloak/security-generation/c14-migration-fix/proofs/security-v3/otziv-realm-migration-provenance.json',
    'infrastructure/runtime-security/proofs/c14-keycloak-published/final/runtime/otziv-realm-migration-provenance.json',
    'infrastructure/runtime-security/proofs/c14-keycloak-published/runtime/otziv-realm-migration-provenance.json',
]
PROVENANCE_SHA = '4f92a2fffad2fc1f6a18d1e7ba84e72ad2e119604c65835ac8cb5ea242c67de7'
CLASS_ENTRY = 'org/keycloak/models/sessions/infinispan/stream/RemoveKeyConsumer.class'
ENV = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
ENV.update({'GIT_OPTIONAL_LOCKS': '0', 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull})


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def run(args, cwd=ROOT, timeout=60):
    result = subprocess.run(args, cwd=cwd, env=ENV, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Fixture command failed: {args[0:2]} (exit {result.returncode})')
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix='otziv-gh-'))
    if args.output:
        output.mkdir(parents=True, exist_ok=False)
    endpoint = json.loads(run(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']))
    assert endpoint.startswith(('npipe:', 'unix:')), 'Local Docker only'
    image = json.loads(run(['docker', 'image', 'inspect', IMAGE]))[0]
    assert IMAGE in image['RepoDigests']
    raw_files = {path: (ROOT / path).read_bytes() for path in PATHS}
    assert all(sha(raw) == PROVENANCE_SHA for raw in raw_files.values())
    value = json.loads(raw_files[PATHS[0]])['modules'][0]['unchangedEntrySha256'][CLASS_ENTRY]
    assert re.fullmatch('[0-9a-f]{64}', value)
    pattern = '^' + re.escape('RemoveKeyConsumer.class":"' + value + '"') + '$'
    line = "  '''" + pattern + "''',\n"
    candidate = (ROOT / '.gitleaks.toml').read_bytes()
    assert candidate.count(line.encode()) == 3
    baseline = candidate.replace(line.encode(), b'')
    allowed = []
    for block in tomllib.loads(candidate.decode())['allowlists']:
        if pattern not in block.get('regexes', []):
            continue
        assert block['targetRules'] == ['generic-api-key']
        assert block['condition'] == 'AND' and block['regexTarget'] == 'match'
        assert len(block['paths']) == 1
        matches = [path for path in PATHS if re.fullmatch(block['paths'][0], path)]
        assert len(matches) == 1
        assert not re.fullmatch(block['paths'][0], 'elsewhere/' + matches[0])
        allowed.extend(matches)
    assert sorted(allowed) == sorted(PATHS)
    results = []

    def scan(label, files, config, mode, expected_count, field='RemoveKeyConsumer.class', required_rule='generic-api-key'):
        case = output / label
        repo, report_dir = case / 'repo', case / 'report'
        repo.mkdir(parents=True)
        report_dir.mkdir()
        for path, data in files.items():
            file = repo / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
        (repo / '.gitleaks.toml').write_bytes(config)
        if mode == 'git':
            def git(*arguments):
                return run(['git', '-C', str(repo), *arguments])
            git('init', '--quiet')
            for key, setting in [('core.longpaths', 'true'), ('core.autocrlf', 'false'),
                                 ('core.hooksPath', str(case / 'empty-hooks')), ('commit.gpgsign', 'false'),
                                 ('user.name', 'Public provenance fixture'), ('user.email', 'fixture@example.invalid')]:
                git('config', key, setting)
            git('add', '--', *files)
            git('commit', '--quiet', '-m', 'Unbound public provenance control')
        owner = uuid.uuid4().hex
        command = ['docker', 'run', '--rm', '--pull', 'never', '--name', 'otziv-gh-control-' + owner[:12],
                   '--label', 'otziv.proof.owner=' + owner, '--cidfile', str(case / 'container-id'),
                   '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                   '--tmpfs', '/tmp:rw,nosuid,size=64m', '--memory', '512m', '--cpus', '4', '--pids-limit', '128',
                   '--mount', f'type=bind,src={repo},dst=/repo,readonly',
                   '--mount', f'type=bind,src={report_dir},dst=/proof', '--workdir', '/repo',
                   '--env', 'GIT_CONFIG_COUNT=1', '--env', 'GIT_CONFIG_KEY_0=safe.directory',
                   '--env', 'GIT_CONFIG_VALUE_0=/repo', '--env', 'GIT_OPTIONAL_LOCKS=0',
                   IMAGE, mode, '--config', '/repo/.gitleaks.toml', '--redact', '--no-banner',
                   '--report-format', 'json', '--report-path', '/proof/redacted.json', '.']
        started = time.monotonic()
        try:
            process = subprocess.run(command, env=ENV, capture_output=True, timeout=300)
        except subprocess.TimeoutExpired:
            container_id = (case / 'container-id').read_text().strip()
            inspected = json.loads(run(['docker', 'inspect', container_id]))[0]
            assert inspected['Id'] == container_id and inspected['Config']['Labels']['otziv.proof.owner'] == owner
            assert inspected['Image'] == image['Id']
            run(['docker', 'stop', '--time', '10', container_id], timeout=20)
            raise
        (report_dir / 'redacted.log').write_bytes(process.stdout + process.stderr)
        report_bytes = (report_dir / 'redacted.json').read_bytes()
        findings = json.loads(report_bytes)
        assert process.returncode == (1 if expected_count else 0), (label, process.returncode)
        assert len(findings) == expected_count, (label, len(findings), expected_count)
        if expected_count:
            assert all(f['RuleID'] == required_rule for f in findings), label
            if field:
                assert all(field in f['Match'] for f in findings), label
        result = {'case': label, 'mode': mode, 'result': 'PASS', 'findings': len(findings),
                  'durationSeconds': round(time.monotonic() - started, 3),
                  'configSha256': sha(config), 'reportSha256': sha(report_bytes), 'redacted': True}
        results.append(result)
        print(json.dumps(result), flush=True)

    # Raw fixture proves that whole Git hunks expose a match missing from the
    # directory-mode fragment inventory, even with identical file bytes/config.
    scan('raw-baseline-dir', raw_files, baseline, 'dir', 0)
    scan('raw-baseline-git', raw_files, baseline, 'git', 3)
    scan('raw-candidate-git', raw_files, candidate, 'git', 0)
    minimum = ('{"RemoveKeyConsumer.class":"' + value + '"}\n').encode()
    scan('exact-match-three-paths', {p: minimum for p in PATHS}, candidate, 'git', 0)
    changed = value[:-1] + ('1' if value[-1] != '1' else '2')
    for i, path in enumerate(PATHS):
        scan('changed-value-' + str(i), {path: minimum.replace(value.encode(), changed.encode())}, candidate, 'git', 1)
    scan('same-value-other-paths', {'elsewhere/' + p: minimum for p in PATHS}, candidate, 'git', 3)
    # Deterministically derived, unbound synthetic bytes; never a real credential.
    unknown = sha(b'otziv-unbound-gitleaks-history-negative-control-v1')
    extra = minimum.rstrip()[:-1] + (',"api_key":"' + unknown + '"}\n').encode()
    scan('additional-secret-same-line', {p: extra for p in PATHS}, candidate, 'git', 3, field='api_key')
    independent = candidate + ('\n[[rules]]\nid = "otziv-history-independent-control"\ndescription = "Unbound fixture detector"\nregex = ' + "'''" + value + "'''\n").encode()
    scan('independent-detector-preserved', {p: minimum for p in PATHS}, independent, 'git', 3,
         field=None, required_rule='otziv-history-independent-control')
    receipt = {'schema': 'otziv-gitleaks-public-history-controls-v1', 'result': 'PASS', 'engine': IMAGE,
               'baselineConfigSha256': sha(baseline), 'candidateConfigSha256': sha(candidate),
               'sourceSha256': sha(Path(__file__).read_bytes()), 'provenanceSha256': PROVENANCE_SHA,
               'classEntry': CLASS_ENTRY, 'classByteSha256': value, 'publicValueSha256': sha(value.encode()),
               'exactPaths': PATHS, 'cases': results, 'caseCount': len(results),
               'rootGitWrites': False, 'rawProofFilesModified': False, 'reportsRedacted': True}
    (output / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'result': 'PASS', 'cases': len(results), 'output': str(output)}), flush=True)


if __name__ == '__main__':
    main()
