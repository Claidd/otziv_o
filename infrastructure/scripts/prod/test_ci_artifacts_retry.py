"""A lost GitHub connection is retried for reads only; answers and writes are never repeated."""
import http.client
import io
import json
import unittest
import urllib.error
from email.message import Message
from unittest import mock

import ci_artifacts


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def redirect(location):
    headers = Message()
    headers['Location'] = location
    return urllib.error.HTTPError('https://api.github.com/x', 302, 'Found', headers, None)


class Opener:
    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.requests = []

    def open(self, request, timeout=None):
        self.requests.append(request)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return Response(outcome)


def client(*outcomes):
    with mock.patch.object(ci_artifacts, 'github_token', return_value='fixture-token'):
        value = ci_artifacts.Client('.', sleep=lambda seconds: value.pauses.append(seconds))
    value.pauses = []
    value.opener = Opener(*outcomes)
    return value


class ReadRetryTests(unittest.TestCase):
    def test_lost_connections_are_retried_with_growing_pauses(self):
        github = client(urllib.error.URLError(TimeoutError('timed out')), ConnectionResetError(),
                        http.client.IncompleteRead(b'{"id"'), b'{"id": 7}')
        with mock.patch('sys.stderr', io.StringIO()) as log:
            self.assertEqual(github.get('/actions/artifacts/7'), {'id': 7})
        self.assertEqual(github.pauses, [1, 2, 4])
        self.assertEqual(len(github.opener.requests), 4)
        self.assertNotIn('fixture-token', log.getvalue())

    def test_a_truncated_json_body_is_read_again(self):
        github = client(b'{"artifacts": [', b'{"artifacts": [], "total_count": 0}')
        with mock.patch('sys.stderr', io.StringIO()):
            self.assertEqual(github.get('/actions/runs/1/artifacts'), {'artifacts': [], 'total_count': 0})
        self.assertEqual(github.pauses, [1])

    def test_retries_are_bounded(self):
        github = client(*[urllib.error.URLError('down')] * 4)
        with mock.patch('sys.stderr', io.StringIO()), self.assertRaises(urllib.error.URLError):
            github.get('/actions/artifacts/7')
        self.assertEqual(github.pauses, [1, 2, 4])
        self.assertEqual(len(github.opener.requests), 4)

    def test_an_http_answer_is_never_repeated(self):
        github = client(urllib.error.HTTPError('https://api.github.com/x', 404, 'Not Found', Message(), None))
        with self.assertRaises(urllib.error.HTTPError):
            github.get('/actions/artifacts/7')
        self.assertEqual(github.pauses, [])
        self.assertEqual(len(github.opener.requests), 1)

    def test_a_write_is_never_repeated_after_a_lost_connection(self):
        github = client(urllib.error.URLError('down'), b'{"id": 1}')
        with self.assertRaises(urllib.error.URLError):
            github.get('/deployments', 'POST', {'ref': 'a' * 40})
        self.assertEqual(github.pauses, [])
        self.assertEqual(len(github.opener.requests), 1)

    def test_the_storage_redirect_is_retried_only_before_it_is_answered(self):
        location = 'https://productionresultssa1.blob.core.windows.net/actions-results/x?sig=fixture'
        github = client(urllib.error.URLError('down'), redirect(location))
        with mock.patch('sys.stderr', io.StringIO()):
            self.assertEqual(github.download_url({'id': 9}), location)
        self.assertEqual(github.pauses, [1])
        # The authenticated GitHub request never follows the redirect itself.
        self.assertTrue(all(request.full_url.startswith(ci_artifacts.API) for request in github.opener.requests))

    def test_an_outside_storage_redirect_is_still_refused(self):
        github = client(redirect('https://example.com/artifact.zip'))
        with self.assertRaises(ci_artifacts.GateError):
            github.download_url({'id': 9})
        self.assertEqual(github.pauses, [])


if __name__ == '__main__':
    unittest.main()
