FROM ghcr.io/claidd/otziv-security@sha256:025c28f7afbcc84d8d836840afb7cb2e8e44f2b4930131c4bbb539380fd8185f
# Preserve nginx and all unrelated packages; patch only Expat.
RUN sha256sum /usr/sbin/nginx > /tmp/nginx.sha256 \
    && apk add --no-cache --upgrade libexpat=2.8.5-r0 \
    && sha256sum -c /tmp/nginx.sha256 && rm /tmp/nginx.sha256 \
    && nginx -v
