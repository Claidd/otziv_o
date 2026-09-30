# syntax=docker/dockerfile:1
FROM ghcr.io/claidd/otziv-security@sha256:74afe2ca053c338fe4d14ed36397b4ccea286efc52a3db4fc5a5c07289a9c360
# Replace only urllib3; preserve Certbot, plugins and the installer-free runtime.
ADD --checksum=sha256:0cf3cae568d36aa9576b28dfb35f11328f1cb974ca7647d9475ebb86c75ac6e3 https://files.pythonhosted.org/packages/92/9d/c4e665119135114480843e7ab388fa94d8480650450e6f8e26b70d323a4c/urllib3-2.8.0-py3-none-any.whl /tmp/urllib3.whl
COPY install-wheel.py /tmp/install-wheel.py
RUN python /tmp/install-wheel.py \
    && rm /tmp/install-wheel.py /tmp/urllib3.whl \
    && certbot --version \
    && python -c "import certbot, acme, cryptography, josepy, urllib3; assert urllib3.__version__ == '2.8.0'" \
    && ! command -v pip && ! command -v uv
