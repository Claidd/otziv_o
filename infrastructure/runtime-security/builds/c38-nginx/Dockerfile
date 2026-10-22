FROM ghcr.io/claidd/otziv-security@sha256:a08d8948f53646b5fc6ba2e02cb27467683b3c2fb3922007014888bea03b984d

# Upgrade nginx and its matching dynamic-module ABI together from nginx.org.
# Keep the existing Alpine branch, configuration, entrypoint and other packages.
ENV NGINX_VERSION=1.30.6 \
    PKG_RELEASE=1 \
    DYNPKG_RELEASE=1 \
    NJS_VERSION=1.0.2 \
    NJS_RELEASE=1 \
    ACME_VERSION=0.4.1
LABEL com.otziv.security.patch="Official nginx 1.30.6 stable and matching modules; preserve unrelated C31 packages"

RUN set -eu; set -o pipefail; \
    inventory() { \
      find /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /etc/nginx /usr/share/nginx /docker-entrypoint.sh /docker-entrypoint.d \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    unrelated_packages() { \
      apk --no-network info -v | LC_ALL=C sort \
        | grep -Ev '^nginx(-module-(acme|geoip|njs|xslt))?-[0-9]'; \
    }; \
    inventory > /tmp/payload.before; \
    unrelated_packages > /tmp/packages.before; \
    apk add --no-cache --upgrade --repositories-file /dev/null \
      --repository https://nginx.org/packages/alpine/v3.24/main \
      nginx=1.30.6-r1 \
      nginx-module-acme=1.30.6.0.4.1-r1 \
      nginx-module-geoip=1.30.6-r1 \
      nginx-module-njs=1.30.6.1.0.2-r1 \
      nginx-module-xslt=1.30.6-r1; \
    unrelated_packages > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; \
    cmp /tmp/payload.before /tmp/payload.after; \
    apk --no-network info -v -e nginx | grep -Fx 'nginx-1.30.6-r1'; \
    apk --no-network info -v -e nginx-module-acme | grep -Fx 'nginx-module-acme-1.30.6.0.4.1-r1'; \
    apk --no-network info -v -e nginx-module-geoip | grep -Fx 'nginx-module-geoip-1.30.6-r1'; \
    apk --no-network info -v -e nginx-module-njs | grep -Fx 'nginx-module-njs-1.30.6.1.0.2-r1'; \
    apk --no-network info -v -e nginx-module-xslt | grep -Fx 'nginx-module-xslt-1.30.6-r1'; \
    if apk --no-network info -e nginx-module-image-filter; then exit 1; fi; \
    if apk --no-network info -e tiff; then exit 1; fi; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after; \
    nginx -t; nginx -v
