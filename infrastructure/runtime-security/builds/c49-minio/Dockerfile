FROM ghcr.io/claidd/otziv-security@sha256:bdbacc1acd769119b1456b830196041eecb4ed50bcb8a5e03d9812e309c6309c

# Keep the accepted C36 local S3 gateway (versitygw executable, entrypoint and build records) intact.
# Install only the official Alpine 3.24 zlib 1.3.2-r1 security update.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/local/bin /usr/share/otziv-build \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /usr/local/bin /usr/share/otziv-build \
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
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
LABEL com.otziv.security.zlib-refresh="C49: official Alpine3.24 zlib1.3.2-r1; C36 versitygw executable and unrelated packages unchanged"
