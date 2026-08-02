# External Review Worker

Browser worker for post-checking already published reviews on public map cards.

The backend owns scheduling, S3 upload and database state. This worker only opens
a public filial URL, captures screenshots, runs OCR, compares visible text with
the expected review text and returns a JSON result.

## Run

```bash
npm ci
npm run install-browsers
cp .env.example .env
npm start
```

## Docker

```bash
docker build -t otziv-external-review-worker:local .
docker run --rm -p 3097:3097 otziv-external-review-worker:local
```

Inside the project compose network the backend should use:

```text
http://external-review-worker:3097
```

Backend endpoint expected by default:

```text
POST http://localhost:3097/api/external-review-checks/verify
```

`/health` and `/ready` expose only a minimal liveness response. The verification
endpoint accepts `X-Otziv-Internal-Token` when
`EXTERNAL_REVIEW_WORKER_SHARED_SECRET` is configured and rejects missing or
incorrect values with a constant-time digest comparison. Setting
`EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED=true` without a secret makes startup fail
closed.

The worker accepts only credential-free HTTP(S) filial URLs. It resolves every
host before navigation, blocks local/private/link-local/reserved IPv4 and IPv6,
and repeats the check for browser requests and redirects. A proxy remains a
separate trust boundary and must also enforce public-only egress.

Runtime work is bounded by the JSON limit, expected-text limit, redirect limit,
`MAX_CONCURRENT_CHECKS`, overall deadline and OCR deadline. The container runs as the unprivileged `node` user
with writable Chromium/Tesseract state under `/tmp`.

Proxy variables are present for future network routing, but proxy is disabled by
default. The worker does not use stealth plugins, fingerprint spoofing or captcha
bypass. If a captcha/block page is detected, it returns `BLOCKED`.
