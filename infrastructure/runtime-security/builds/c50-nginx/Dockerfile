FROM ghcr.io/claidd/otziv-security@sha256:ed728f40a3efd0a65241ddc75057a2835b953e4dc22dbab7f9a7da16c3dbd83e

# Official Alpine 3.24 CVE-2026-107891 update; reject unrelated package/payload changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx /docker-entrypoint.sh /docker-entrypoint.d -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx /docker-entrypoint.sh /docker-entrypoint.d -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.6-r2'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.6-r2'; \
    inventory > /tmp/c50.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main musl=1.2.6-r3 musl-utils=1.2.6-r3; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.6-r3'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.6-r3'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.after; \
    cmp /tmp/c50.packages.before /tmp/c50.packages.after; \
    inventory > /tmp/c50.payload.after; cmp /tmp/c50.payload.before /tmp/c50.payload.after; \
    rm /tmp/c50.payload.before /tmp/c50.payload.after /tmp/c50.packages.before /tmp/c50.packages.after
RUN nginx -t && nginx -v

LABEL com.otziv.security.c50="C50: official musl security package; accepted application and configuration bytes unchanged"
