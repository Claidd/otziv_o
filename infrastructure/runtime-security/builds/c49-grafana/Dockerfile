FROM ghcr.io/claidd/otziv-security@sha256:07233e89f1964d967e516a7b97856011a502213194a27c941fc5cf5d052208fa

# Keep the accepted C44 image (exact C43 Grafana executable, configuration and labels) intact.
# Install only the official Alpine 3.24 zlib 1.3.2-r1 security update.
USER root
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/share/grafana /etc/grafana /run.sh /var/lib/grafana \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' + | LC_ALL=C sort; \
      find /usr/share/grafana /etc/grafana /run.sh /var/lib/grafana \
        -type f -exec sha256sum '{}' + | LC_ALL=C sort; \
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
USER 472
LABEL com.otziv.security.zlib-refresh="C49: official Alpine3.24 zlib1.3.2-r1; exact C44 Grafana executable, configuration and unrelated packages unchanged"
