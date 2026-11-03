FROM ghcr.io/claidd/otziv-security@sha256:b30ef32504546374c995f4c675c1a8f16f2feff0dc560c1f3df4725bdb0f4864

# Keep the accepted C41 application, server and configuration bytes intact.
# Install only the official Alpine 3.24 libexpat 2.10.0 security update.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e libexpat | grep -Fx 'libexpat-2.9.0-r0'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^libexpat-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main libexpat=2.10.0-r0; \
    apk --no-network info -v -e libexpat | grep -Fx 'libexpat-2.10.0-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^libexpat-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    nginx -t; nginx -v; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
LABEL com.otziv.security.expat-refresh="C47: official Alpine3.24 libexpat2.10.0-r0; C41 server/application/config and unrelated packages unchanged"
