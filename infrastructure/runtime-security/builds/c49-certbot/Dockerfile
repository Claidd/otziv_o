FROM ghcr.io/claidd/otziv-security@sha256:e74ff9f71d224293f21ab35bfc4b445f3ef5dd731d91009fc63afb0e1a8306a3

# Keep the accepted C34 Certbot, Python runtime and CA bundle intact.
# Install only the official Alpine 3.23 zlib 1.3.2-r1 security update.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /opt/certbot /usr/local/bin /usr/local/lib \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' + | LC_ALL=C sort; \
      find /opt/certbot /usr/local/bin /usr/local/lib \
        -type f -exec sha256sum '{}' + | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e zlib | grep -Fx 'zlib-1.3.2-r0'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^zlib-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.23/main zlib=1.3.2-r1; \
    apk --no-network info -v -e zlib | grep -Fx 'zlib-1.3.2-r1'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^zlib-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    PYTHONDONTWRITEBYTECODE=1 certbot --version; \
    PYTHONDONTWRITEBYTECODE=1 python -c "import zlib, certifi; assert zlib.ZLIB_RUNTIME_VERSION == '1.3.2'; assert certifi.__version__ == '2026.10.19'"; \
    inventory > /tmp/payload.checked; cmp /tmp/payload.before /tmp/payload.checked; \
    rm /tmp/payload.before /tmp/payload.after /tmp/payload.checked /tmp/packages.before /tmp/packages.after
LABEL com.otziv.security.zlib-refresh="C49: official Alpine3.23 zlib1.3.2-r1; C34 Certbot, Python runtime and unrelated packages unchanged"
