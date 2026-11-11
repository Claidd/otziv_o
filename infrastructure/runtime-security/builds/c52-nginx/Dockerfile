FROM ghcr.io/claidd/otziv-security@sha256:a1f83a9a65d250dc10276236d09161cf47c325cb4e876e7d82046b16dad7cefa

# Official Alpine 3.24 libxslt 1.1.43-r4 (CVE-2026-87636, CVE-2026-9947); reject unrelated package/payload changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx /docker-entrypoint.sh /docker-entrypoint.d -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx /docker-entrypoint.sh /docker-entrypoint.d -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e libxslt | grep -Fx 'libxslt-1.1.43-r3'; \
    inventory > /tmp/c52.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^libxslt-[0-9]' > /tmp/c52.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main libxslt=1.1.43-r4; \
    apk --no-network info -v -e libxslt | grep -Fx 'libxslt-1.1.43-r4'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^libxslt-[0-9]' > /tmp/c52.packages.after; \
    cmp /tmp/c52.packages.before /tmp/c52.packages.after; \
    inventory > /tmp/c52.payload.after; cmp /tmp/c52.payload.before /tmp/c52.payload.after; \
    rm /tmp/c52.payload.before /tmp/c52.payload.after /tmp/c52.packages.before /tmp/c52.packages.after
RUN nginx -t && nginx -v

LABEL com.otziv.security.c52="C52: official libxslt security package; accepted application and configuration bytes unchanged"
