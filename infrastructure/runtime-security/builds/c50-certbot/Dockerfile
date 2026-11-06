FROM ghcr.io/claidd/otziv-security@sha256:93cf11b11f3bc0f1795da45dc024f8246a5e7bdbc70b11755e5678f1fbbfd708

# Official Alpine 3.23 CVE-2026-107891 update; reject unrelated package/payload changes.
RUN set -eu; set -o pipefail; \
    inventory() { \
      find /opt/certbot /usr/local/bin /usr/local/lib -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /opt/certbot /usr/local/bin /usr/local/lib -type f -exec sha256sum '{}' ';' | LC_ALL=C sort; \
    }; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.5-r23'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.5-r23'; \
    inventory > /tmp/c50.payload.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.23/main musl=1.2.5-r24 musl-utils=1.2.5-r24; \
    apk --no-network info -v -e musl | grep -Fx 'musl-1.2.5-r24'; \
    apk --no-network info -v -e musl-utils | grep -Fx 'musl-utils-1.2.5-r24'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(musl|musl-utils)-[0-9]' > /tmp/c50.packages.after; \
    cmp /tmp/c50.packages.before /tmp/c50.packages.after; \
    inventory > /tmp/c50.payload.after; cmp /tmp/c50.payload.before /tmp/c50.payload.after; \
    rm /tmp/c50.payload.before /tmp/c50.payload.after /tmp/c50.packages.before /tmp/c50.packages.after
RUN PYTHONDONTWRITEBYTECODE=1 certbot --version && PYTHONDONTWRITEBYTECODE=1 python -c 'import certifi; assert certifi.__version__ == "2026.10.19"'

LABEL com.otziv.security.c50="C50: official musl security package; accepted application and configuration bytes unchanged"
