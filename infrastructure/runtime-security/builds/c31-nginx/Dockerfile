FROM ghcr.io/claidd/otziv-security@sha256:183d7d2186d37c430f0824257c67803f61e51af507ae831e69afbb5cd68e5682

# Refresh only Expat in the already published and proven C28 Nginx image.
# Keep the proxy binary, configuration and every unrelated package unchanged.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/sbin/nginx /usr/lib/nginx /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /usr/sbin/nginx /usr/lib/nginx /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e libexpat)" > /tmp/packages.before; \
    apk add --no-cache --upgrade libexpat=2.9.0-r0; \
    apk --no-network info -v | LC_ALL=C sort | grep -Fvx "$(apk --no-network info -v -e libexpat)" > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    apk --no-network info -v -e libexpat | grep -Fx 'libexpat-2.9.0-r0'; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    nginx -t; nginx -v
