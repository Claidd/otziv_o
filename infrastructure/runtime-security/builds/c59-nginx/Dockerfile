FROM ghcr.io/claidd/otziv-security@sha256:a873be5016237d2c08591bb1582a0535992466ef8744cb73d2c940b6c84abc23

# Signed nginx.org stable packages, including matching dynamic modules.
# Preserve entrypoints, configuration, static content and all unrelated packages.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    inventory > /tmp/c59.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^nginx(-module-(acme|geoip|njs|xslt))?-[0-9]' > /tmp/c59.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://nginx.org/packages/alpine/v3.24/main \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main \
      nginx=1.30.7-r1 nginx-module-acme=1.30.7.0.4.2-r1 \
      nginx-module-geoip=1.30.7-r1 nginx-module-njs=1.30.7.1.0.2-r1 nginx-module-xslt=1.30.7-r1; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^nginx(-module-(acme|geoip|njs|xslt))?-[0-9]' > /tmp/c59.packages.after; \
    cmp /tmp/c59.packages.before /tmp/c59.packages.after; \
    inventory > /tmp/c59.payload.after; cmp /tmp/c59.payload.before /tmp/c59.payload.after; \
    nginx -t; nginx -v; \
    printf '%s\n' 'load_module /usr/lib/nginx/modules/ngx_http_acme_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_http_geoip_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_stream_geoip_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_http_js_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_stream_js_module.so;' \
      'load_module /usr/lib/nginx/modules/ngx_http_xslt_filter_module.so;' \
      'events {}' 'http {}' 'stream {}' > /tmp/c59-modules.conf; \
    nginx -t -c /tmp/c59-modules.conf; \
    rm /tmp/c59.payload.before /tmp/c59.payload.after /tmp/c59.packages.before /tmp/c59.packages.after /tmp/c59-modules.conf
LABEL com.otziv.security.c59="Official Nginx 1.30.7 and ABI-matched modules; configuration and unrelated packages unchanged"
