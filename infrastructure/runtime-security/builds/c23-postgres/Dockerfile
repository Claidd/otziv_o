FROM ghcr.io/claidd/otziv-security@sha256:a30a580feb45fce65b87d21a104d407d3d1c5b255ae0e9a05ce9903e10adb270 AS parent
FROM node:22-trixie-slim@sha256:7b8a0c89c54499bee567618f96578e1a12a800f062fbdbfd1fb6a443fa6f6284 AS packages
WORKDIR /refresh/debs
RUN apt-get update && apt-get download libssl3t64=3.5.7-1~deb13u3 openssl=3.5.7-1~deb13u3 openssl-provider-legacy=3.5.7-1~deb13u3
COPY --from=parent /var/lib/dpkg/status /refresh/status
COPY update-status.mjs /refresh/update-status.mjs
RUN mkdir -p /refresh/root/var/lib/dpkg/info && node /refresh/update-status.mjs
FROM parent
# The reviewed runtime intentionally has no package manager. Overlay only the
# authenticated Debian packages and their exact dpkg inventory, retaining every
# custom PostgreSQL/source artifact and the complete launch configuration.
COPY --from=packages /refresh/root/ /
