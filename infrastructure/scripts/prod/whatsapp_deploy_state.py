"""Recognize an explicitly allowed QR wait without declaring delivery ready."""
import json
import subprocess
import sys


def qr_pending(payload):
    if not isinstance(payload, dict):
        return False
    for path in ('/ready', '/internal/operation-metrics', '/internal/inbox-metrics'):
        item = payload.get(path)
        if not isinstance(item, dict) or not isinstance(item.get('body'), dict):
            return False
    ready = payload.get('/ready', {})
    state = ready.get('body', {})
    operations = payload.get('/internal/operation-metrics', {})
    inbox = payload.get('/internal/inbox-metrics', {})
    ledger = operations.get('body', {})
    incoming = inbox.get('body', {})
    return (ready.get('http') == 503 and state.get('state') == 'qr'
            and state.get('hasQr') is True and state.get('authenticated') is False
            and state.get('ready') is False and state.get('gatewayAuthRequired') is True
            and bool(payload.get('expectedClientId'))
            and state.get('clientId') == payload.get('expectedClientId')
            and operations.get('http') == 200 and inbox.get('http') == 200
            and ledger.get('writer') is True and ledger.get('acceptingNew') is True
            and ledger.get('recoveryRequired') is False
            and ledger.get('persistenceFailures') == 0 and ledger.get('readFailures') == 0
            and incoming.get('healthy') is True and incoming.get('persistenceFailures') == 0
            and incoming.get('historyDiscoveryFailed') is False)


def main(container, service):
    if service not in ('whatsapp_lika', 'whatsapp_vika'):
        return 1
    info = json.loads(subprocess.check_output(['docker', 'inspect', container], timeout=15))[0]
    if not info['State']['Running'] or info['Config']['Labels'].get('com.docker.compose.service') != service:
        return 1
    code = """
    (async () => {
      const result = {expectedClientId: process.env.CLIENT_ID};
      for (const path of ['/ready','/internal/operation-metrics','/internal/inbox-metrics']) {
        const response = await fetch('http://127.0.0.1:3000'+path, {
          headers: {'x-otziv-internal-token': process.env.WHATSAPP_GATEWAY_SHARED_SECRET},
          signal: AbortSignal.timeout(5000)});
        result[path] = {http: response.status, body: await response.json()};
      }
      console.log(JSON.stringify(result));
    })().catch(() => {process.exitCode = 1});
    """
    result = subprocess.run(['docker', 'exec', container, 'node', '-e', code],
                            capture_output=True, timeout=20, check=True)
    if not qr_pending(json.loads(result.stdout)):
        return 1
    print(f'OTZIV_WHATSAPP_QR_PENDING={service}; delivery unavailable until phone linking')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(*sys.argv[1:]))
    except (ValueError, KeyError, TypeError, subprocess.SubprocessError):
        sys.exit(1)
