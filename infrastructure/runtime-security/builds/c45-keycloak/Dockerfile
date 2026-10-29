# Preserve the accepted C43 issuer and all Java/provider bytes; update one official OS package.
FROM ghcr.io/claidd/otziv-security@sha256:6df30e829eec786f9f7b51f56a5e0c10451e96b4ff086157129c649df80632f1
USER 0
ADD --checksum=sha256:908295e77a65219a62114c3c79690a3c99712274d1317c3ba47ffa2f8ea52ca5 \
    https://security.ubuntu.com/ubuntu/pool/main/b/brotli/libbrotli1_1.0.9-2ubuntu0.1_amd64.deb /tmp/otziv-libbrotli1.deb
RUN --network=none set -eu; \
    test "$(dpkg-query -W -f='${Version}' libbrotli1)" = '1.0.9-2build6'; \
    dpkg -i /tmp/otziv-libbrotli1.deb; \
    test "$(dpkg-query -W -f='${Version}' libbrotli1)" = '1.0.9-2ubuntu0.1'; \
    dpkg --verify libbrotli1; \
    rm /tmp/otziv-libbrotli1.deb
USER 1000:0
LABEL otziv.brotli="1.0.9-2ubuntu0.1" otziv.brotli-advisory="CVE-2026-111497"
