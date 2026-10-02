FROM ghcr.io/claidd/otziv-security@sha256:95c21948b23ef308f41dba871bb1320796ce66e1c59f6b7ffdd7e86e57cb588b
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/sbin/nginx /usr/lib/nginx /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /usr/sbin/nginx /usr/lib/nginx /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e pcre2)" > /tmp/packages.before; \
    apk add --no-cache --upgrade pcre2=10.49-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e pcre2)" > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v | grep -Fx 'pcre2-10.49-r0'; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    nginx -v
