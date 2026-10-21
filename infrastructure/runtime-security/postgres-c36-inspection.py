"""Capture the stopped C36 PostgreSQL image for a byte-for-byte C23 comparison.

The existing C23 inspector is hash-bound by its published acceptance proof. Its
`inspect` routine exports a stopped container; it does not execute the image.
"""
import argparse
import gzip
import hashlib
import json
import pathlib
import runpy
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--temporary-directory', required=True)
    args = parser.parse_args()
    source = pathlib.Path(__file__).with_name('ssl-refresh-inspection.py')
    inspector = runpy.run_path(str(source))['inspect']
    with tempfile.TemporaryDirectory(prefix='postgres-c36-', dir=args.temporary_directory) as temporary:
        candidate = inspector(args.candidate, pathlib.Path(temporary))
    record = {
        'schema': 'otziv-postgres-c36-inspection-v1',
        'result': 'PASS',
        'component': 'postgres',
        'productionAccess': False,
        'ownedContainersRemaining': 0,
        'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
        'parentInspectorSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'candidate': candidate,
    }
    pathlib.Path(args.output).write_bytes(gzip.compress(
        (json.dumps(record, separators=(',', ':')) + '\n').encode(), mtime=0))
    print(json.dumps({'result': 'PASS', 'component': 'postgres',
                      'files': len(candidate['inventory'])}))


if __name__ == '__main__':
    main()
