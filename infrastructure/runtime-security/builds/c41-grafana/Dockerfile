FROM ghcr.io/claidd/otziv-security@sha256:db6100e472c78215c923e1472fbfe0b4109a1303505aa959b190a3cae2bccc37

# Retain the reviewed C40 application and exact C36 executable/Tempo proof.
# Update only the official Alpine curl packages and return to UID472.
USER 0
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /usr/share/grafana /etc/grafana /usr/share/otziv-build /run.sh /.grafana-version \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /usr/share/grafana /etc/grafana /usr/share/otziv-build /run.sh /.grafana-version \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e curl | grep -Fx 'curl-8.21.0-r0'; \
    apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.21.0-r0'; \
    inventory > /tmp/payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main curl=8.23.0-r0 libcurl=8.23.0-r0; \
    apk --no-network info -v -e curl | grep -Fx 'curl-8.23.0-r0'; \
    apk --no-network info -v -e libcurl | grep -Fx 'libcurl-8.23.0-r0'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    printf '%s  /usr/share/grafana/bin/grafana\n' '8b8981ecfb6dd1123831116f6428c531d33f43d7cda839677a6335f73895b32e' | sha256sum -c -; \
    grafana server -v; curl --version; \
    rm /tmp/payload.before /tmp/payload.after /tmp/packages.before /tmp/packages.after
USER 472
LABEL com.otziv.security.curl-refresh="C41: official Alpine3.24 curl/libcurl8.23.0-r0; C40 application/config/assets, exact C36 Grafana executable and unrelated packages unchanged"
