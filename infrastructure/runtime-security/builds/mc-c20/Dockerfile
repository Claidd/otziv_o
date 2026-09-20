FROM ghcr.io/claidd/otziv-security@sha256:a0b7303c61b0f3e5b97d126ae34ce6cd53411dab07e721b7ba818706585dcced AS client

# Refresh the local S3 initializer's OS while retaining the exact reviewed Go binary.
FROM registry.access.redhat.com/ubi9/ubi-minimal@sha256:7fbeae18dc9476399f565e68255f602a3374ea8614ba3d14843565131a13ff93
COPY runtime-packages.lock /usr/share/otziv-build/mc-c20/runtime-packages.lock
RUN microdnf --refresh upgrade --nodocs --assumeyes \
    && microdnf install --nodocs --assumeyes ca-certificates coreutils-single \
    && mkdir -p /usr/share/otziv-build/mc-c20 \
    && rpm -qa --qf '%{NAME}=%{EPOCHNUM}:%{VERSION}-%{RELEASE}.%{ARCH}\n' | sort > /usr/share/otziv-build/mc-c20/packages.before-removal.txt \
    && test "$(sha256sum /usr/share/otziv-build/mc-c20/runtime-packages.lock | cut -d ' ' -f 1)" = "$(sha256sum /usr/share/otziv-build/mc-c20/packages.before-removal.txt | cut -d ' ' -f 1)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' libevent)" = 2.1.13-1.el9_8 \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' openssl-libs)" = 3.5.8-1.el9_8 \
    && microdnf clean all \
    && microdnf remove --assumeyes curl-minimal libcurl-minimal microdnf libdnf librepo rpm rpm-libs libmodulemd libsolv \
    && test ! -e /usr/bin/curl && test ! -e /usr/lib64/libcurl.so.4 \
    && test ! -e /usr/bin/rpm && test ! -e /usr/bin/microdnf \
    && test -s /var/lib/rpm/rpmdb.sqlite
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
