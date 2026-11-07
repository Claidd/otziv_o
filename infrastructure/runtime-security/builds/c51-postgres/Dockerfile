# C36 is the accepted PostgreSQL 17.11 / Debian trixie image. Only the official
# trixie-security zlib1g package (CVE-2026-76844, CVE-2026-85091) changes in C51.
FROM ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5 AS parent
FROM node:22-trixie-slim@sha256:7b8a0c89c54499bee567618f96578e1a12a800f062fbdbfd1fb6a443fa6f6284 AS packages
WORKDIR /refresh/debs
ADD --checksum=sha256:8c27c342230503b73dfde629642c3b352e87b554e06d60cf58772f236a305ceb \
    https://deb.debian.org/debian-security/pool/updates/main/z/zlib/zlib1g_1.3.dfsg%2breally1.3.1-1%2bdeb13u1_amd64.deb /refresh/debs/zlib1g_amd64.deb
COPY --from=parent /var/lib/dpkg/status /refresh/status
COPY update-status.mjs /refresh/update-status.mjs
RUN --network=none mkdir -p /refresh/root/var/lib/dpkg/info && node /refresh/update-status.mjs
FROM parent
# The reviewed runtime intentionally has no package manager. Overlay only the
# authenticated Debian package and its exact dpkg inventory, retaining every
# custom PostgreSQL/source artifact and the complete launch configuration.
COPY --from=packages /refresh/root/ /
LABEL com.otziv.zlib.package="zlib1g=1:1.3.dfsg+really1.3.1-1+deb13u1" \
      com.otziv.zlib.deb-sha256="8c27c342230503b73dfde629642c3b352e87b554e06d60cf58772f236a305ceb" \
      com.otziv.security.proof="postgres-c51-zlib-deb13u1"
RUN --network=none set -eu; \
    printf '%s  %s\n' 6c13931760210e8b391b63a337dfe4071346c4ad6e3827694edb7b6989bb16dd \
      /usr/lib/x86_64-linux-gnu/libz.so.1.3.1 | sha256sum --check --strict; \
    test "$(postgres --version)" = 'postgres (PostgreSQL) 17.11'
