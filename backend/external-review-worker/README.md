# External Review Worker

Browser worker for post-checking already published reviews on public map cards.

The backend owns scheduling, S3 upload and database state. This worker only opens
a public filial URL, captures screenshots, runs OCR, compares visible text with
the expected review text and returns a JSON result.

## Run

```bash
npm install
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

Proxy variables are present for future network routing, but proxy is disabled by
default. The worker does not use stealth plugins, fingerprint spoofing or captcha
bypass. If a captcha/block page is detected, it returns `BLOCKED`.
