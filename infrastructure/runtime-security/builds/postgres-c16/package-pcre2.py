"""Install a real, traceable replacement package; retain every unrelated runtime file."""
import hashlib
import json
import pathlib
import shutil
import subprocess

root = pathlib.Path('/runtime')
package = pathlib.Path('/package')
component = json.loads(pathlib.Path('/build/component.json').read_text())
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(pathlib.Path('/sources/pcre2.tar.bz2')) == component['sha256']
libdir = pathlib.Path('usr/lib/x86_64-linux-gnu')
libraries = list((pathlib.Path('/stage') / libdir).glob('libpcre2-8.so.0*'))
assert len(libraries) == 2 and sum(p.is_symlink() for p in libraries) == 1
for path in libraries:
    target = package / libdir / path.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target, follow_symlinks=False)
license_path = package / 'usr/share/doc/libpcre2-8-0/copyright'
license_path.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2('/sources/unpacked/LICENCE.md', license_path)
component['licenseSha256'] = sha(license_path)
control = package / 'DEBIAN'
control.mkdir()
(control / 'control').write_text(
    'Package: libpcre2-8-0\nVersion: 10.48+otziv.1\nSource: pcre2\n'
    'Architecture: amd64\nMulti-Arch: same\nSection: libs\nPriority: optional\n'
    'Maintainer: Otziv runtime security\n'
    'Description: PCRE2 10.48 upstream 8-bit runtime with Unicode and JIT\n')
regular = sorted(p for p in package.rglob('*') if p.is_file() and not p.is_symlink() and control not in p.parents)
(control / 'md5sums').write_text(''.join(hashlib.md5(p.read_bytes()).hexdigest() + '  ' + str(p.relative_to(package)) + '\n' for p in regular))
deb = pathlib.Path('/libpcre2-8-0_10.48+otziv.1_amd64.deb')
subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(package), str(deb)], check=True)
component['debSha256'] = sha(deb)
# Unpack into an isolated root. The inherited runtime intentionally contains
# partial Debian packages: dpkg's unrelated empty-package cleanup must not alter
# their honest inventory. Import only the newly built package and its records.
installed = pathlib.Path('/pcre2-installed')
installed.mkdir()
subprocess.run(['dpkg', '--root=' + str(installed), '--install', str(deb)], check=True)
info = root / 'var/lib/dpkg/info'
old_lists = list(info.glob('libpcre2-8-0*.list'))
assert len(old_lists) == 1
for name in old_lists[0].read_text().splitlines():
    rel = name.lstrip('/')
    target = root / rel
    if target.is_file() or target.is_symlink():
        assert rel.startswith(('usr/lib/x86_64-linux-gnu/libpcre2-', 'usr/share/doc/libpcre2-8-0/'))
        target.unlink()
for path in info.glob('libpcre2-8-0*'):
    assert path.is_file() and not path.is_symlink()
    path.unlink()
shutil.copytree(installed / 'usr', root / 'usr', dirs_exist_ok=True, symlinks=True)
for path in (installed / 'var/lib/dpkg/info').glob('libpcre2-8-0*'):
    shutil.copy2(path, info / path.name)
status_path = root / 'var/lib/dpkg/status'
records = status_path.read_text().strip().split('\n\n')
assert len([r for r in records if r.startswith('Package: libpcre2-8-0\n')]) == 1
replacement = (installed / 'var/lib/dpkg/status').read_text().strip()
assert replacement.startswith('Package: libpcre2-8-0\n') and '\nStatus: install ok installed\n' in replacement
status_path.write_text('\n\n'.join(replacement if r.startswith('Package: libpcre2-8-0\n') else r for r in records) + '\n')
for path in libraries:
    actual = root / libdir / path.name
    assert (actual.readlink() == path.readlink()) if path.is_symlink() else (sha(actual) == sha(path))
evidence = root / 'usr/local/share/otziv'
upstream = json.loads((evidence / 'upstream-components.json').read_text())
assert [c['name'] for c in upstream] == ['libxml2', 'libxslt', 'postgresql', 'gzip']
upstream.append(component)
(evidence / 'upstream-components.json').write_text(json.dumps(upstream, indent=2) + '\n')
debian = json.loads((evidence / 'debian-components.json').read_text())
assert len([p for p in debian if p['Package'] == component['package']]) == 1
debian = [p for p in debian if p['Package'] != component['package']]
(evidence / 'debian-components.json').write_text(json.dumps(debian, indent=2, sort_keys=True) + '\n')
files = json.loads((evidence / 'runtime-files.json').read_text())
files = {k: v for k, v in files.items() if not v.get('debianPackage', '').startswith('libpcre2-8-0')}
for path in (package / 'usr').rglob('*'):
    if path.is_symlink() or path.is_file():
        rel = str(path.relative_to(package))
        files[rel] = {'upstream': 'pcre2', **({'symlink': str(path.readlink())} if path.is_symlink() else {'sha256': sha(path)})}
(evidence / 'runtime-files.json').write_text(json.dumps(files, indent=2, sort_keys=True) + '\n')
sbom = json.loads((evidence / 'upstream.cdx.json').read_text())
sbom['components'].append({'type': 'library', 'name': component['name'], 'version': component['version'],
    'purl': component['purl'], 'cpe': component['cpe'],
    'externalReferences': [{'type': 'distribution', 'url': component['url'],
        'hashes': [{'alg': 'SHA-256', 'content': component['sha256']}]}],
    'properties': [{'name': 'otziv:runtimeDebSha256', 'value': component['debSha256']},
        {'name': 'otziv:packageVersion', 'value': component['packageVersion']}]})
(evidence / 'upstream.cdx.json').write_text(json.dumps(sbom, indent=2) + '\n')
shutil.copytree('/build/evidence', evidence / 'build', dirs_exist_ok=True)
(evidence / 'build/pcre2-component.json').write_text(json.dumps(component, indent=2) + '\n')
print(json.dumps({'package': component['package'], 'version': component['packageVersion'], 'debSha256': component['debSha256']}))
