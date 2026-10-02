import copy
import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

import patch_cli


class CorePatchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.old_hash, self.new_hash = patch_cli.OLD, patch_cli.NEW
        self.old = {'com/fasterxml/jackson/core/Factory.class': b'old',
                    'META-INF/versions/21/com/fasterxml/jackson/core/internal/v2_21_6/Float.class': b'old variant',
                    'META-INF/maven/com.fasterxml.jackson.core/jackson-core/pom.properties': b'version=2.21.6',
                    'META-INF/services/com.fasterxml.jackson.core.JsonFactory': b'old service'}
        self.new = {'com/fasterxml/jackson/core/Factory.class': b'new',
                    'META-INF/versions/21/com/fasterxml/jackson/core/internal/v2_21_7/Float.class': b'new variant',
                    'META-INF/maven/com.fasterxml.jackson.core/jackson-core/pom.properties': b'version=2.21.7',
                    'META-INF/services/com.fasterxml.jackson.core.JsonFactory': b'new service'}
        self.unrelated = {'META-INF/MANIFEST.MF': b'original CLI main class',
                          'META-INF/versions/9/module-info.class': b'CLI module',
                          'META-INF/services/com.fasterxml.jackson.core.ObjectCodec': b'databind',
                          'org/keycloak/Main.class': b'original', 'com/fasterxml/jackson/databind/Tree.class': b'unchanged'}
        for name, contents in [('old.jar', self.old), ('new.jar', self.new), ('cli.jar', self.old | self.unrelated)]:
            self.write(name, contents)
        patch_cli.OLD = self.hash('old.jar')
        patch_cli.NEW = self.hash('new.jar')

    def tearDown(self):
        patch_cli.OLD, patch_cli.NEW = self.old_hash, self.new_hash
        self.temporary.cleanup()

    def hash(self, name):
        return hashlib.sha256((self.root / name).read_bytes()).hexdigest()

    def write(self, name, contents):
        with zipfile.ZipFile(self.root / name, 'w') as jar:
            for entry, payload in contents.items():
                item = zipfile.ZipInfo(entry, (2024, 2, 3, 4, 5, 6))
                item.comment = b'retained metadata'
                jar.writestr(item, payload)
                jar.filelist[-1].external_attr = 0

    def patch(self):
        patch_cli.patch(*(self.root / name for name in ['new.jar', 'old.jar', 'cli.jar', 'result.jar']))

    def test_replaces_multi_release_and_service_without_touching_other_payload(self):
        self.patch()
        with zipfile.ZipFile(self.root / 'result.jar') as jar:
            self.assertEqual({name: jar.read(name) for name in jar.namelist()}, self.new | self.unrelated)
            for name in self.unrelated:
                self.assertEqual(jar.getinfo(name).external_attr, 0)
                self.assertEqual(jar.getinfo(name).comment, b'retained metadata')

    def test_modified_parent_cli_is_rejected(self):
        self.write('cli.jar', self.old | self.unrelated | {'com/fasterxml/jackson/core/Factory.class': b'unreviewed'})
        with self.assertRaisesRegex(AssertionError, 'parent_cli_core_payload_mismatch'):
            self.patch()

    def test_unreviewed_vendor_bytes_are_rejected(self):
        with (self.root / 'new.jar').open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(AssertionError, 'unreviewed_core_bytes'):
            self.patch()

    def test_duplicate_cli_entries_are_rejected(self):
        with zipfile.ZipFile(self.root / 'cli.jar', 'a') as jar:
            jar.writestr('org/keycloak/Main.class', b'duplicate')
        with self.assertRaisesRegex(AssertionError, 'duplicate_jar_entry'):
            self.patch()


class InspectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = pathlib.Path(__file__).parents[2] / 'jackson-core-refresh-inspection.py'
        spec = importlib.util.spec_from_file_location('core_inspector_test', path)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def pair(self):
        m = self.module
        unrelated = {'org/keycloak/Main.class': {'sha256': 'keep', 'size': 1, 'mode': 0}}
        result = []
        for core, cli, content, layers in [(m.OLD, 'old-cli', 'old', ['parent']), (m.NEW, 'new-cli', 'new', ['parent', 'overlay'])]:
            selected = {'META-INF/versions/21/com/fasterxml/jackson/core/Float.class': {'sha256': content, 'size': 1},
                        'META-INF/services/com.fasterxml.jackson.core.JsonFactory': {'sha256': content, 'size': 1}}
            inventory = {p: {'mode': 420, 'uid': 1000, 'gid': 0, 'kind': 'file', 'sha256': sha}
                         for p, sha in [(m.SERVER, core), (m.CLI, cli), ('opt/keycloak/providers/otziv-security-generation.jar', 'provider')]}
            result.append({'configuration': {'Labels': {}}, 'rootfs': layers, 'inventory': inventory,
                           'jars': {m.SERVER: {'sha256': core, 'entries': copy.deepcopy(selected)},
                                    m.CLI: {'sha256': cli, 'entries': copy.deepcopy(selected | unrelated)}}})
        return result

    def test_scope_accepts_only_core_refresh(self):
        before, after = self.pair()
        self.assertEqual(self.module.verify(before, after), sorted([self.module.SERVER, self.module.CLI]))

    def test_provider_launch_or_unrelated_cli_metadata_changes_fail(self):
        for mutate in [lambda x: x['inventory']['opt/keycloak/providers/otziv-security-generation.jar'].update(sha256='bad'),
                       lambda x: x['configuration'].update(User='root'),
                       lambda x: x['jars'][self.module.CLI]['entries']['org/keycloak/Main.class'].update(mode=384),
                       lambda x: x['jars'][self.module.CLI]['entries']['META-INF/services/com.fasterxml.jackson.core.JsonFactory'].update(sha256='old'),
                       lambda x: x['jars'][self.module.CLI]['entries']['META-INF/versions/21/com/fasterxml/jackson/core/Float.class'].update(sha256='old')]:
            before, after = self.pair()
            mutate(after)
            with self.assertRaises(AssertionError):
                self.module.verify(before, after)


if __name__ == '__main__':
    unittest.main()
