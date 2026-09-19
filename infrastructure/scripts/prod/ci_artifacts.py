"""Read immutable Actions artifacts without forwarding GitHub credentials to storage."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import stat
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import zipfile

from release_ci import API, REPOSITORY, GateError, github_token, pages, require


class NoFollow(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def storage_url(value):
    url = urlsplit(value)
    require(url.scheme == 'https' and not url.username and not url.password and not url.fragment
            and url.port in (None, 443) and url.hostname
            and any(url.hostname.endswith(suffix) for suffix in ('.blob.core.windows.net', '.actions.githubusercontent.com')),
            'Artifact storage redirect is outside GitHub storage')
    return value


def validate_artifact(item, run_id, revision, name=None):
    require(type(item.get('id')) is int and item['id'] > 0 and not item.get('expired'), 'Artifact missing or expired')
    require(item.get('workflow_run', {}).get('id') == run_id
            and item['workflow_run'].get('head_sha') == revision, 'Artifact belongs to another source/run')
    require(name is None or item.get('name') == name, 'Artifact name mismatch')
    require(re.fullmatch('sha256:[a-f0-9]{64}', item.get('digest', '')), 'GitHub artifact digest is unavailable')
    require(type(item.get('size_in_bytes')) is int and 0 < item['size_in_bytes'] <= 20 * 1024**3, 'Invalid artifact size')
    return item


def extract_files(archive, output, expected):
    """Extract only named regular files, with per-file byte limits; no extractall."""
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        require(len(members) == len(expected) and {m.filename for m in members} == set(expected), 'Unexpected ZIP members')
        for member in members:
            mode = member.external_attr >> 16
            require(not member.is_dir() and stat.S_IFMT(mode) in (0, stat.S_IFREG)
                    and '/' not in member.filename and '\\' not in member.filename
                    and 0 < member.file_size <= expected[member.filename], 'Unsafe artifact member')
            target = output / member.filename
            require(not target.exists() and target.parent == output, 'Artifact would overwrite a file')
            with source.open(member) as stream, target.open('xb') as destination:
                shutil.copyfileobj(stream, destination, 1024 * 1024)
            require(target.stat().st_size == member.file_size, 'Incomplete artifact member')


class Client:
    def __init__(self, repo):
        self.token = github_token(repo)
        self.opener = urllib.request.build_opener(NoFollow())

    def request(self, path, method='GET', body=None):
        require(path.startswith('/') and not re.search(r'[\r\n#\\]', path) and '..' not in path.split('/'), 'Invalid GitHub path')
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'otziv-ci-release'}
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers['Content-Type'] = 'application/json'
        return self.opener.open(urllib.request.Request(API + path, data=data, method=method, headers=headers), timeout=60)

    def get(self, path, method='GET', body=None):
        with self.request(path, method, body) as response:
            data = response.read(4 * 1024**2 + 1)
            require(len(data) <= 4 * 1024**2, 'GitHub response too large')
            return json.loads(data)

    def artifacts(self, run_id):
        return pages(self.get, f'/actions/runs/{int(run_id)}/artifacts', 'artifacts')

    def download(self, item, output, maximum):
        require(0 < item['size_in_bytes'] <= maximum, 'Artifact exceeds its size limit')
        output = Path(output)
        require(not output.exists(), 'Refusing to overwrite an artifact')
        try:
            response = self.request(f"/actions/artifacts/{int(item['id'])}/zip")
        except urllib.error.HTTPError as error:
            require(error.code == 302, f'Artifact download failed (HTTP {error.code})')
            location = storage_url(error.headers.get('Location', ''))
            # A fresh request deliberately contains no Authorization header.
            response = self.opener.open(urllib.request.Request(location), timeout=180)
        value = hashlib.sha256()
        total = 0
        with response, output.open('xb') as destination:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                require(total <= maximum, 'Artifact response exceeded its limit')
                value.update(chunk)
                destination.write(chunk)
        require(total == item['size_in_bytes'] and 'sha256:' + value.hexdigest() == item['digest'], 'Artifact checksum/size mismatch')
        return output
