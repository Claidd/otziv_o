# syntax=docker/dockerfile:1
FROM ghcr.io/claidd/otziv-security@sha256:fc21f2b893f9db7550cbad759cb2ad50fbf49bee61d5f58a3a45b009037a8764

# Keep the reviewed Certbot and urllib3 runtime; replace only its compromised CA bundle.
ADD --checksum=sha256:42d35340cc5b9ba78e818ddbff0c5b1c2b779de448a340fd1b989f5fa8670e1b https://files.pythonhosted.org/packages/c4/51/29046e48f693c54e5b7412216d447a1d8362c9653148d98c02cdf65fa733/certifi-2026.10.19-py3-none-any.whl /tmp/certifi.whl
COPY install-certifi-wheel.py /tmp/install-certifi-wheel.py
RUN python /tmp/install-certifi-wheel.py \
    && rm /tmp/install-certifi-wheel.py /tmp/certifi.whl \
    && certbot --version \
    && certbot plugins \
    && python -c "import certbot, acme, cryptography, josepy, urllib3, certifi; assert urllib3.__version__ == '2.8.0'; assert certifi.__version__ == '2026.10.19'" \
    && ! command -v pip && ! command -v uv
