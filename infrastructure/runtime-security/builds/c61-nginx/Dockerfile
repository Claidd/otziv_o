FROM ghcr.io/claidd/otziv-security@sha256:a0168e8b87c0ecfeb2584b62827a0f7ec1690bb1aa26c762f6368636a5936416

# Official signed Alpine 3.24 fixes. Keep application/configuration and every unrelated package.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/c61.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(libxml2)-[0-9]' > /tmp/c61.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/community \
      libxml2=2.13.9-r3; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(libxml2)-[0-9]' > /tmp/c61.packages.after; \
    cmp /tmp/c61.packages.before /tmp/c61.packages.after; \
    inventory > /tmp/c61.payload.after; cmp /tmp/c61.payload.before /tmp/c61.payload.after; \
    rm /tmp/c61.payload.before /tmp/c61.payload.after /tmp/c61.packages.before /tmp/c61.packages.after
RUN set -eu; nginx -t; nginx -v; \
    printf '%s\n' 'load_module /usr/lib/nginx/modules/ngx_http_acme_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_http_geoip_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_stream_geoip_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_http_js_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_stream_js_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_http_xslt_filter_module.so;' \
      'events {}' 'http {}' 'stream {}' > /tmp/c59-modules.conf; \
    nginx -t -c /tmp/c59-modules.conf; \
    rm /tmp/c59-modules.conf
LABEL com.otziv.security.c61="Official libxml2/PHP fixes; configuration and unrelated packages unchanged"
