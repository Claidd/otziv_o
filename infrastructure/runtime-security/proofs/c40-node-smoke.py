"""Build the actual frontend with the candidate's npm; no host dependencies or secrets."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import uuid


def docker(*args, **kwargs):
    return subprocess.run(['docker', *args], check=True, capture_output=True, timeout=1200, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'output_already_exists'
    assert re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9./:@_-]*', args.image), 'invalid_image_reference'
    endpoint = docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}').stdout.decode().strip()
    assert re.fullmatch(r'(?:unix|npipe)://[^\r\n]+', endpoint), 'local_docker_required'
    assert not os.environ.get('DOCKER_HOST') or re.fullmatch(r'(?:unix|npipe)://[^\r\n]+', os.environ['DOCKER_HOST']), 'remote_docker_forbidden'
    image = json.loads(docker('image', 'inspect', args.image).stdout)[0]
    assert image['Os'] == 'linux' and image['Architecture'] == 'amd64'
    repo = pathlib.Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    result = {'schema': 'otziv-c40-node-smoke-v1', 'result': 'IN_PROGRESS', 'image': args.image,
              'inspectedImageId': image['Id'], 'productionAccess': False, 'sourceFiles': {},
              'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
    tag = 'otziv-c40-node-smoke-' + uuid.uuid4().hex
    try:
        with tempfile.TemporaryDirectory(prefix='otziv-c40-node-') as temporary:
            context = pathlib.Path(temporary)
            # Explicit source allowlist: no env files, npm credentials, host node_modules or build outputs.
            entries = ['frontend/package.json', 'frontend/package-lock.json', 'frontend/angular.json',
                       'frontend/tsconfig.json', 'frontend/tsconfig.app.json', 'frontend/src',
                       'frontend/public', 'shared/client-common/package.json', 'shared/client-common/src']
            for relative in entries:
                source, target = repo / relative, context / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                if source.is_dir(): shutil.copytree(source, target)
                else: shutil.copyfile(source, target)
            for source in sorted(context.rglob('*')):
                if source.is_file():
                    result['sourceFiles'][source.relative_to(context).as_posix()] = hashlib.sha256(source.read_bytes()).hexdigest()
            shutil.copyfile(pathlib.Path(__file__).with_name('c40-node-runtime.mjs'), context / 'runtime.mjs')
            (context / 'Dockerfile').write_text(
                'FROM ' + args.image + '\nCOPY runtime.mjs /fixture/runtime.mjs\n'
                'RUN --network=none node /fixture/runtime.mjs\n'
                'WORKDIR /fixture/frontend\nCOPY shared /fixture/shared\nCOPY frontend/ /fixture/frontend/\n'
                'ENV npm_config_install_links=true NG_BUILD_MAX_WORKERS=2 NODE_OPTIONS=--max-old-space-size=3072\n'
                'RUN npm ci --no-audit --no-fund --registry=https://registry.npmjs.org\n'
                'RUN --network=none npm run build -- --configuration production\n'
                'RUN test -s dist/frontend/browser/index.html\n', encoding='utf-8', newline='\n')
            build = docker('build', '--platform', 'linux/amd64', '--progress', 'plain', '-t', tag, str(context))
            output.with_suffix('.build.log').write_bytes(build.stdout + build.stderr)
            assert json.loads(docker('image', 'inspect', args.image).stdout)[0]['Id'] == image['Id'], 'candidate_changed_during_proof'
            result.update(result='PASS', runtime='PASS', actualFrontendProductionBuild='PASS', npmCi='PASS')
    except subprocess.CalledProcessError as error:
        output.with_suffix('.build.log').write_bytes((error.stdout or b'') + (error.stderr or b''))
        result.update(result='FAIL', failure='build_failed')
        raise
    finally:
        # Only the uniquely named proof image; candidate and user's images are preserved.
        subprocess.run(['docker', 'image', 'rm', tag], capture_output=True, timeout=60)
        output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'sourceFiles'}))


if __name__ == '__main__':
    main()
