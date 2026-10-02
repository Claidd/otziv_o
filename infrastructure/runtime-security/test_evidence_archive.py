"""Cold-checkout and fail-closed tests; no network or repository evidence is needed."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('evidence_archive', Path(__file__).with_name('evidence_archive.py'))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def make_zip(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as bundle:
        for name, content, mode in entries:
            member = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            member.create_system = 3
            member.external_attr = mode << 16
            member.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(member, content)
    return stream.getvalue()


class Response(io.BytesIO):
    status = 200

    def __init__(self, data, length=None):
        super().__init__(data)
        self.headers = {'Content-Length': str(len(data) if length is None else length)}


class Opener:
    def __init__(self, *responses):
        self.responses = iter(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        result = next(self.responses)
        if isinstance(result, Exception):
            raise result
        return result


class EvidenceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.contents = {evidence.ROOTS[0] + '/acceptance.json': b'{"exact":true}\r\n',
                         evidence.ROOTS[1] + '/runtime.json.gz': bytes(range(256))}
        self.entries = [(name, data, stat.S_IFREG | 0o644) for name, data in sorted(self.contents.items())]
        self.data = make_zip(self.entries)
        self.manifest = {'schema': evidence.SCHEMA, 'sourceRevision': '1' * 40,
                         'archive': {'url': evidence.URL, 'sha256': evidence.sha(self.data), 'bytes': len(self.data)},
                         'files': [{'path': name, 'sha256': evidence.sha(data), 'bytes': len(data), 'mode': '100644'}
                                   for name, data in sorted(self.contents.items())]}
        self.archive = self.root / 'fixture.zip'
        self.archive.write_bytes(self.data)

    def container_identity(self, data):
        manifest = copy.deepcopy(self.manifest)
        manifest['archive'].update(sha256=evidence.sha(data), bytes=len(data))
        return manifest

    def assert_cold(self):
        self.assertFalse((self.root / 'infrastructure').exists())

    def test_cold_checkout_restores_original_bytes_and_second_run_is_offline(self):
        result = evidence.hydrate(self.root, self.manifest, self.archive)
        self.assertEqual(result, {'files': 2, 'restored': 2, 'networkUsed': False})
        for name, content in self.contents.items():
            self.assertEqual((self.root / name).read_bytes(), content)
        self.archive.unlink()
        with patch.object(evidence, 'download_bytes', side_effect=AssertionError('Unexpected network')):
            self.assertEqual(evidence.hydrate(self.root, self.manifest, download=True)['restored'], 0)
        self.assertEqual(evidence.check_existing(self.root, self.manifest), [])

    def test_missing_evidence_needs_an_explicit_download_option(self):
        with patch.object(evidence, 'download_bytes', side_effect=AssertionError('Unexpected network')):
            with self.assertRaisesRegex(ValueError, 'provide the archive or pass --download'):
                evidence.hydrate(self.root, self.manifest)
        self.assert_cold()

    def test_download_then_cache_can_restore_deleted_file_without_network(self):
        opener = Opener(Response(self.data))
        self.assertTrue(evidence.hydrate(self.root, self.manifest, download=True, opener=opener)['networkUsed'])
        name = next(iter(self.contents))
        (self.root / name).unlink()
        with patch.object(evidence, 'download_bytes', side_effect=AssertionError('Unexpected network')):
            self.assertEqual(evidence.hydrate(self.root, self.manifest)['restored'], 1)
        self.assertEqual(len(opener.requests), 1)
        self.assertFalse(any(k.lower() == 'authorization' for k in opener.requests[0][0].headers))

    def test_corrupt_cache_is_not_replaced_even_when_download_enabled(self):
        cached = self.root / '.codex-tmp/runtime-evidence' / (self.manifest['archive']['sha256'] + '.zip')
        cached.parent.mkdir(parents=True)
        cached.write_bytes(b'corrupt')
        with patch.object(evidence, 'download_bytes', side_effect=AssertionError('Unexpected network')):
            with self.assertRaisesRegex(ValueError, 'archive hash or size mismatch'):
                evidence.hydrate(self.root, self.manifest, download=True)
        self.assertEqual(cached.read_bytes(), b'corrupt')
        self.assert_cold()

    def test_corrupt_existing_evidence_is_not_overwritten(self):
        evidence.hydrate(self.root, self.manifest, self.archive)
        target = self.root / next(iter(self.contents))
        target.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'Existing evidence differs'):
            evidence.hydrate(self.root, self.manifest, self.archive, download=True)
        self.assertEqual(target.read_bytes(), b'corrupt')

    def test_harmless_sibling_log_is_preserved_without_becoming_evidence(self):
        extra = self.root / evidence.ROOTS[0] / 'local-scanner.log'
        extra.parent.mkdir(parents=True)
        extra.write_bytes(b'extra')
        self.assertEqual(evidence.hydrate(self.root, self.manifest, self.archive)['restored'], 2)
        self.assertEqual(evidence.check_existing(self.root, self.manifest), [])
        self.assertEqual(extra.read_bytes(), b'extra')

    def test_all_archive_members_verified_before_any_evidence_is_written(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['files'][-1]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'member hash or size mismatch'):
            evidence.hydrate(self.root, manifest, self.archive)
        self.assert_cold()

    def test_hostile_zip_paths_links_duplicates_and_missing_members_rejected(self):
        name, content, mode = self.entries[0]
        cases = [self.entries + [('../escape', b'bad', mode)],
                 [('../escape', content, mode), self.entries[1]],
                 [(name, content, stat.S_IFLNK | 0o644), self.entries[1]],
                 [(name, content, stat.S_IFREG | 0o777), self.entries[1]],
                 [self.entries[0], self.entries[0]], self.entries[:1]]
        for entries in cases:
            with self.subTest(entries=[item[0] for item in entries]):
                with patch('warnings.warn'):
                    data = make_zip(entries)
                with self.assertRaises(ValueError):
                    evidence.archive_payloads(data, self.container_identity(data))

    def test_archive_and_member_size_bounds(self):
        with self.assertRaisesRegex(ValueError, 'archive hash or size mismatch'):
            evidence.archive_payloads(self.data + b'x', self.manifest)
        manifest = copy.deepcopy(self.manifest)
        manifest['files'][0]['bytes'] += 1
        with self.assertRaisesRegex(ValueError, 'Unsafe archive entry metadata'):
            evidence.archive_payloads(self.data, manifest)
        with self.assertRaisesRegex(ValueError, 'size bound'):
            evidence.bounded_bytes(self.archive, len(self.data) - 1)

    def test_malformed_manifest_fields_paths_and_bounds_rejected(self):
        cases = []
        for bad in ['../escape', '/absolute', evidence.ROOTS[0] + '/a/../b', evidence.ROOTS[0] + '/NUL.txt',
                    evidence.ROOTS[0] + '/test.', evidence.ROOTS[0] + '/a:b', evidence.ROOTS[0] + r'\bad',
                    'infrastructure/runtime-security/proofs/c24-keycloak/a', evidence.ROOTS[0] + '/./a']:
            value = copy.deepcopy(self.manifest)
            value['files'][0]['path'] = bad
            cases.append(value)
        for key, bad in [('bytes', 0), ('bytes', True), ('bytes', evidence.MAX_FILE + 1),
                         ('sha256', '0' * 63), ('sha256', 7), ('mode', '120000')]:
            value = copy.deepcopy(self.manifest)
            value['files'][0][key] = bad
            cases.append(value)
        for key, bad in [('bytes', evidence.MAX_ARCHIVE + 1), ('bytes', False), ('url', 'https://evil.invalid/a')]:
            value = copy.deepcopy(self.manifest)
            value['archive'][key] = bad
            cases.append(value)
        for key, bad in [('sourceRevision', 123), ('schema', 'future-schema'), ('files', []),
                         ('files', list(reversed(self.manifest['files']))),
                         ('files', [self.manifest['files'][0]] * 2), ('extra', True)]:
            value = copy.deepcopy(self.manifest)
            value[key] = bad
            cases.append(value)
        for value in cases:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    evidence.validate_manifest(value)

    def test_path_and_cache_parent_links_rejected_before_network_or_writes(self):
        original = evidence.linked
        for suspect in [self.root / 'infrastructure', self.root / '.codex-tmp']:
            with self.subTest(suspect=suspect):
                with patch.object(evidence, 'linked', side_effect=lambda p: p == suspect or original(p)):
                    with patch.object(evidence, 'download_bytes', side_effect=AssertionError('Unexpected network')):
                        with self.assertRaisesRegex(ValueError, 'contains a link'):
                            evidence.hydrate(self.root, self.manifest, download=True)
        self.assert_cold()

    def test_real_symlink_is_rejected_when_platform_supports_it(self):
        outside = self.root / 'outside'
        outside.mkdir()
        destination = self.root / 'infrastructure'
        try:
            destination.symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest('This Windows account cannot create symbolic links')
        with self.assertRaisesRegex(ValueError, 'contains a link'):
            evidence.hydrate(self.root, self.manifest, self.archive)
        self.assertEqual(list(outside.iterdir()), [])

    def test_github_release_redirect_is_allowed_and_authenticated_headers_are_absent(self):
        redirect = urllib.error.HTTPError(evidence.URL, 302, 'Found',
                                          {'Location': 'https://release-assets.githubusercontent.com/example?sig=redacted'}, None)
        opener = Opener(redirect, Response(self.data))
        self.assertEqual(evidence.download_bytes(self.manifest, opener), self.data)
        self.assertEqual(len(opener.requests), 2)
        for request, timeout in opener.requests:
            self.assertEqual(timeout, 45)
            self.assertFalse(any(name.lower() == 'authorization' for name in request.headers))

    def test_redirect_to_untrusted_or_non_https_destination_is_rejected(self):
        for url in ['https://evil.invalid/a', 'http://release-assets.githubusercontent.com/a',
                    'https://release-assets.githubusercontent.com.evil.invalid/a',
                    'https://user:secret@release-assets.githubusercontent.com/a',
                    'https://release-assets.githubusercontent.com:444/a',
                    'https://release-assets.githubusercontent.com/a#fragment', '/unrelated-github-path']:
            with self.subTest(url=url):
                redirect = urllib.error.HTTPError(evidence.URL, 302, 'Found', {'Location': url}, None)
                opener = Opener(redirect)
                with self.assertRaisesRegex(ValueError, 'Untrusted evidence download destination'):
                    evidence.download_bytes(self.manifest, opener)
                self.assertEqual(len(opener.requests), 1)

    def test_bad_download_size_hash_http_failure_and_redirect_loop_rejected(self):
        cases = [Opener(Response(self.data, length=0)), Opener(Response(b'x' * len(self.data))),
                 Opener(urllib.error.HTTPError(evidence.URL, 404, 'sensitive body', {}, None)),
                 Opener(*[urllib.error.HTTPError(evidence.URL, 302, 'Found', {'Location': evidence.URL}, None)] * 4)]
        for opener in cases:
            with self.subTest(opener=opener):
                with self.assertRaises(ValueError) as caught:
                    evidence.hydrate(self.root, self.manifest, download=True, opener=opener)
                self.assertNotIn('sensitive body', str(caught.exception))
                self.assert_cold()
                self.assertFalse((self.root / '.codex-tmp').exists())

    def test_manifest_round_trip(self):
        path = self.root / 'manifest.json'
        path.write_text(json.dumps(self.manifest), encoding='utf-8')
        self.assertEqual(evidence.load_manifest(path), self.manifest)


if __name__ == '__main__':
    unittest.main()
