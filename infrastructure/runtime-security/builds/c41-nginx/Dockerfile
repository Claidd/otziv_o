FROM ghcr.io/claidd/otziv-security@sha256:f0f0b92345c2ddf67da923de76951d34d354957de9efda532c926b61ba84842d

# Preserve the reviewed C40 nginx payload and Brotli fix. Only the official
# Alpine curl/libcurl security update may change runtime packages.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx \
        /docker-entrypoint.sh /docker-entrypoint.d \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e curl | grep -Fx 'curl-8.22.0-r0'; \
    apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.22.0-r0'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main curl=8.23.0-r0 libcurl=8.23.0-r0; \
    apk --no-network info -v -e curl | grep -Fx 'curl-8.23.0-r0'; \
    apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.23.0-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    apk --no-network info -v -e brotli-libs | grep -Fx 'brotli-libs-1.2.1-r0'; \
    if apk --no-network info -e nginx-module-image-filter; then exit 1; fi; \
    if apk --no-network info -e tiff; then exit 1; fi; \
    curl --version | grep -F 'curl 8.23.0 '; \
    nginx -t; nginx -v; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
LABEL com.otziv.security.curl-refresh="C41: official Alpine3.24 curl/libcurl8.23.0-r0; C40 nginx/config/modules and all other packages unchanged"
