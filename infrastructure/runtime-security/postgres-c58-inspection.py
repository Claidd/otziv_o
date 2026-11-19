"""Inspect the stopped image and retain the package manifests needed for version verification."""
import argparse
import gzip
import hashlib
import json
import pathlib
import runpy
import subprocess
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--temporary-directory', required=True)
    args = parser.parse_args()
    base = pathlib.Path(__file__).with_name('ssl-refresh-inspection.py')
    inspector = runpy.run_path(str(base))['inspect']
    with tempfile.TemporaryDirectory(dir=args.temporary_directory) as temp:
        folder = pathlib.Path(temp)
        image = inspector(args.candidate, folder)
        owner = 'otziv-postgres-inspect-' + uuid.uuid4().hex
        container = subprocess.check_output(['docker', 'create', '--network', 'none', '--name', owner,
                                             '--entrypoint', '/not-executed', args.candidate], text=True).strip()
        try:
            for name in ['upstream-components.json', 'runtime-files.json', 'mit-kerberos.json', 'upstream.cdx.json']:
                path = 'usr/local/share/otziv/' + name
                subprocess.run(['docker', 'cp', container + ':/' + path, str(folder / name)], check=True)
                data = (folder / name).read_bytes()
                assert hashlib.sha256(data).hexdigest() == image['inventory'][path]['sha256']
                image['captured'][path] = data.decode()
            binary = folder / 'gosu'
            subprocess.run(['docker', 'cp', container + ':/usr/local/bin/gosu', str(binary)], check=True)
            assert hashlib.sha256(binary.read_bytes()).hexdigest() == image['inventory']['usr/local/bin/gosu']['sha256']
            reader = subprocess.check_output(['docker', 'create', '--network', 'none', '--cap-drop=ALL',
                '--security-opt=no-new-privileges:true', '--memory=512m', '--pids-limit=128',
                '--env=GOTOOLCHAIN=local', '--entrypoint=/bin/sh',
                'golang:1.27.4-bookworm@sha256:4fa7c968ba93f1635670522dbb01a2c78150443e1385746924566079f36570ea', '-ec', 'go version -m /tmp/gosu'], text=True).strip()
            try:
                subprocess.run(['docker', 'cp', str(binary), reader + ':/tmp/gosu'], check=True)
                build_info = subprocess.check_output(['docker', 'start', '-a', reader], text=True, timeout=120)
            finally:
                subprocess.run(['docker', 'rm', '-v', reader], check=True, stdout=subprocess.DEVNULL)
        finally:
            subprocess.run(['docker', 'rm', '-v', container], check=True, stdout=subprocess.DEVNULL)
    record = {'gosuBuildInfo': build_info, 'inspectedBinaryExecuted': False, 'schema': 'otziv-postgres-c58-inspection-v1', 'candidate': image,
              'inspectorSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
              'parentInspectorSha256': hashlib.sha256(base.read_bytes()).hexdigest()}
    args.output.write_bytes(gzip.compress(json.dumps(record, separators=(',', ':')).encode(), mtime=0))


if __name__ == '__main__':
    main()
