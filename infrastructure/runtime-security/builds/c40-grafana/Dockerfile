FROM ghcr.io/claidd/otziv-security@sha256:64b884ccb67f556a18f439ecf8e486414d48d406711a521016cd8186e5a5721a

# Retain the exact reviewed C36 executable and its fixed Tempo source proof.
# Only the official Brotli shared library package changes; return to UID472.
USER 0
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/share/grafana /etc/grafana /usr/share/otziv-build /run.sh /.grafana-version \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /usr/share/grafana /etc/grafana /usr/share/otziv-build /run.sh /.grafana-version \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.2.0-r1'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^brotli-libs-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main brotli-libs=1.2.1-r0; \
    apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.2.1-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^brotli-libs-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    printf '%s  /usr/share/grafana/bin/grafana\n' '8b8981ecfb6dd1123831116f6428c531d33f43d7cda839677a6335f73895b32e' | sha256sum -c -; \
    grafana server -v; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
USER 472
LABEL com.otziv.security.brotli-refresh="C40: official Alpine3.24 brotli-libs1.2.1-r0; exact C36 Grafana executable/config/assets and unrelated packages unchanged"
