FROM ghcr.io/claidd/otziv-security@sha256:19f50d276bee3804121159095b62a7fc6cf236565d45ecb5a36d6dd7c19566b2 AS client
# The reviewed mc executable is static (CGO_ENABLED=0). Keep its exact bytes.
FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
RUN apk upgrade --no-cache && apk add --no-cache ca-certificates \
    && mkdir -p /usr/share/otziv-build/mc-c22 \
    && cp /lib/apk/db/installed /usr/share/otziv-build/mc-c22/apk-installed-database
COPY --from=client /usr/bin/mc /usr/bin/mc
COPY --from=client /usr/share/otziv-build/mc/ /usr/share/otziv-build/mc/
COPY --from=client /licenses/ /licenses/
RUN echo '3ff50685174929a433a6b84e3bc00c484df31a22b4e34713d3d023bb78d613e9  /usr/bin/mc' | sha256sum -c - \
    && mc --version
LABEL org.opencontainers.image.source="https://github.com/Claidd/otziv_o" \
      org.opencontainers.image.revision="77f82e18b5401a65958f1619df6ebb994634bd88" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      com.otziv.corresponding-source="/usr/share/otziv-build/mc/corresponding-source.tar.gz"
ENV MC_CONFIG_DIR=/tmp/.mc
ENTRYPOINT ["mc"]
