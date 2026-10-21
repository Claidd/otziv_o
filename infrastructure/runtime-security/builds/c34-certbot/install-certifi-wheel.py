"""Replace only the pinned certifi CA bundle without installing a package manager."""
import hashlib
import importlib.metadata as metadata
from pathlib import Path
import shutil
import ssl
import sysconfig
import zipfile

from cryptography import x509
from cryptography.x509.oid import NameOID


wheel = Path('/tmp/certifi.whl')
assert hashlib.sha256(wheel.read_bytes()).hexdigest() == '42d35340cc5b9ba78e818ddbff0c5b1c2b779de448a340fd1b989f5fa8670e1b'
assert metadata.version('certifi') == '2026.7.22'
before = sorted((d.metadata['Name'], d.version) for d in metadata.distributions() if d.metadata['Name'].lower() != 'certifi')
root = Path(sysconfig.get_paths()['purelib']).resolve()

with zipfile.ZipFile(wheel) as archive:
    entries = archive.infolist()
    assert entries
    for entry in entries:
        path = Path(entry.filename)
        assert not path.is_absolute() and '..' not in path.parts
        assert entry.filename.startswith(('certifi/', 'certifi-2026.10.19.dist-info/'))
        assert ((entry.external_attr >> 16) & 0o170000) != 0o120000
    for name in ('certifi', 'certifi-2026.7.22.dist-info'):
        target = (root / name).resolve()
        assert target.parent == root and target.is_dir() and not target.is_symlink()
        shutil.rmtree(target)
    archive.extractall(root)

assert metadata.version('certifi') == '2026.10.19'
after = sorted((d.metadata['Name'], d.version) for d in metadata.distributions() if d.metadata['Name'].lower() != 'certifi')
assert before == after, 'unrelated runtime distributions changed'

import certifi  # noqa: E402

bundle = Path(certifi.where())
assert bundle.is_file() and bundle.is_relative_to(root)
ssl.create_default_context(cafile=str(bundle))
certificates = x509.load_pem_x509_certificates(bundle.read_bytes())
assert certificates
for certificate in certificates:
    common_names = certificate.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    assert all('emSign ECC Root CA - G3' not in name.value for name in common_names)
