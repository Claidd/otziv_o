"""Replace only the official Jackson core payload, including Java 11/17/21 variants."""
import copy
import hashlib
import pathlib
import re
import zipfile

OLD = '36111c3a4372cd5c2be6f4ec44a050382487920f3fc38ca3015c9c1678bd7c56'
NEW = '8c5623b98f32d5f7e1287ff61ed50e08ab7b11381e991fff89327faabba42261'


def selected(name):
    return (re.match(r'^(?:META-INF/versions/[0-9]+/)?com/fasterxml/jackson/core/', name) is not None
            or name.startswith('META-INF/maven/com.fasterxml.jackson.core/jackson-core/')
            or name == 'META-INF/services/com.fasterxml.jackson.core.JsonFactory')


def entries(jar, include):
    names = jar.namelist()
    assert len(names) == len(set(names)), 'duplicate_jar_entry'
    return {name: hashlib.sha256(jar.read(name)).hexdigest() for name in names if include(name)}


def patch(source, parent_core, parent_cli, output):
    assert hashlib.sha256(pathlib.Path(source).read_bytes()).hexdigest() == NEW, 'unreviewed_core_bytes'
    assert hashlib.sha256(pathlib.Path(parent_core).read_bytes()).hexdigest() == OLD, 'unexpected_parent_core'
    with zipfile.ZipFile(source) as updated, zipfile.ZipFile(parent_core) as core, zipfile.ZipFile(parent_cli) as old:
        assert entries(old, selected) == entries(core, selected), 'parent_cli_core_payload_mismatch'
        assert entries(updated, selected), 'replacement_core_missing'
        original = entries(old, lambda name: not selected(name))
        with zipfile.ZipFile(output, 'w') as out:
            for item in old.infolist():
                if not selected(item.filename):
                    out.writestr(copy.copy(item), old.read(item.filename))
                    # Python substitutes mode 0600 for a zero mode unless restored.
                    out.filelist[-1].external_attr = item.external_attr
            for item in updated.infolist():
                if selected(item.filename):
                    out.writestr(copy.copy(item), updated.read(item.filename))
                    out.filelist[-1].external_attr = item.external_attr
        with zipfile.ZipFile(output) as result:
            assert entries(result, selected) == entries(updated, selected), 'cli_core_patch_incomplete'
            assert entries(result, lambda name: not selected(name)) == original, 'unrelated_cli_payload_changed'
            for item in old.infolist():
                if not selected(item.filename):
                    after = result.getinfo(item.filename)
                    for key in ['date_time', 'compress_type', 'comment', 'extra', 'internal_attr', 'external_attr', 'create_system']:
                        assert getattr(item, key) == getattr(after, key), 'unrelated_cli_metadata_changed'


if __name__ == '__main__':
    patch('/tmp/core.jar', '/tmp/old-core.jar', '/tmp/cli.jar', '/tmp/patched-cli.jar')
