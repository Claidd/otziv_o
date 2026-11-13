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
        finally:
            subprocess.run(['docker', 'rm', '-v', container], check=True, stdout=subprocess.DEVNULL)
    record = {'schema': 'otziv-postgres-c54-inspection-v1', 'candidate': image,
              'inspectorSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
              'parentInspectorSha256': hashlib.sha256(base.read_bytes()).hexdigest()}
    args.output.write_bytes(gzip.compress(json.dumps(record, separators=(',', ':')).encode(), mtime=0))


if __name__ == '__main__':
    main()
