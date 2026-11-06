FROM ghcr.io/claidd/otziv-security@sha256:0df04ca6f4b3b05ca72a4fc44ee7ee992a3152caa32dadeb0fd12b4d5f9d39de

# Keep the accepted C47 application, server and configuration bytes intact.
# Install only the official Alpine 3.24 zlib 1.3.2-r1 security update.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e zlib | grep -Fx 'zlib-1.3.2-r0'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^zlib-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main zlib=1.3.2-r1; \
    apk --no-network info -v -e zlib | grep -Fx 'zlib-1.3.2-r1'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^zlib-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    nginx -t; nginx -v; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
LABEL com.otziv.security.zlib-refresh="C49: official Alpine3.24 zlib1.3.2-r1; C47 server/application/config and unrelated packages unchanged"
