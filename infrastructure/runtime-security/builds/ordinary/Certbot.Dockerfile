FROM certbot/certbot@sha256:f70ad0adbb7e117f0fe42a63c553f28ea451edabc0148757b6efcd9735acaa20
# pip and uv are installers, not Certbot runtime dependencies. Remove their actual
# code (including vulnerable pip vendor packages); preserve Certbot and all plugins.
RUN apk upgrade --no-cache \
    && python -m pip uninstall -y uv pip \
    && certbot --version \
    && python -c "import certbot, acme, cryptography, josepy" \
    && ! command -v pip \
    && ! command -v uv
LABEL com.otziv.security.patch="Certbot5.8.0 unchanged; Alpine3.23 updates; actual installer code removed from runtime"
