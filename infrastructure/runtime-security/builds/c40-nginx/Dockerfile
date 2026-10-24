FROM ghcr.io/claidd/otziv-security@sha256:8921dafa2cda60de1348d76b629b23af5aac5ef7cdfa870a39531ac049bce3dd

# C38 application and nginx modules remain unchanged; only the official Alpine
# Brotli security backport is installed. No repository or TLS policy changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
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
    if apk --no-network info -e nginx-module-image-filter; then exit 1; fi; \
    if apk --no-network info -e tiff; then exit 1; fi; \
    nginx -t; nginx -v; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
LABEL com.otziv.security.brotli-refresh="C40: official Alpine3.24 brotli-libs1.2.1-r0; C38 nginx/config/modules and unrelated packages unchanged"
