"""Install the authenticated pure-Python wheel without adding an installer."""
import hashlib
import importlib.metadata as metadata
from pathlib import Path
import shutil
import sysconfig
import zipfile

wheel = Path('/tmp/urllib3.whl')
assert hashlib.sha256(wheel.read_bytes()).hexdigest() == '0cf3cae568d36aa9576b28dfb35f11328f1cb974ca7647d9475ebb86c75ac6e3'
assert metadata.version('urllib3') == '2.7.0'
before = sorted((d.metadata['Name'], d.version) for d in metadata.distributions() if d.metadata['Name'].lower() != 'urllib3')
root = Path(sysconfig.get_paths()['purelib']).resolve()
with zipfile.ZipFile(wheel) as archive:
    entries = archive.namelist()
    assert entries and all(name.startswith(('urllib3/', 'urllib3-2.8.0.dist-info/')) and '..' not in Path(name).parts for name in entries)
    for name in ('urllib3', 'urllib3-2.7.0.dist-info'):
        target = (root / name).resolve()
        assert target.parent == root and target.is_dir()
        shutil.rmtree(target)
    archive.extractall(root)
assert metadata.version('urllib3') == '2.8.0'
after = sorted((d.metadata['Name'], d.version) for d in metadata.distributions() if d.metadata['Name'].lower() != 'urllib3')
assert before == after, 'unrelated runtime distributions changed'
