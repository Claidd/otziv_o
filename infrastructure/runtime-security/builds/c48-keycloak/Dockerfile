# Preserve the accepted C45 issuer and all Java/provider bytes; update only the official Ubuntu apt packages.
FROM ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e
USER 0
ADD --checksum=sha256:3a6fbab029e15ca7b8da642c17ec110995a6e3a0bc40eaa08e239cfc94d6c426 \
    https://security.ubuntu.com/ubuntu/pool/main/a/apt/libapt-pkg6.0_2.4.15ubuntu0.1_amd64.deb /tmp/otziv-libapt-pkg.deb
ADD --checksum=sha256:2d4db9a3017c5364e50af5bc2f0c76828b52933b682eff4728f1211e081c6736 \
    https://security.ubuntu.com/ubuntu/pool/main/a/apt/apt_2.4.15ubuntu0.1_amd64.deb /tmp/otziv-apt.deb
RUN --network=none set -eu; \
    test "$(dpkg-query -W -f='${Version}' apt)" = '2.4.14'; \
    test "$(dpkg-query -W -f='${Version}' libapt-pkg6.0)" = '2.4.14'; \
    dpkg -i /tmp/otziv-libapt-pkg.deb /tmp/otziv-apt.deb; \
    test "$(dpkg-query -W -f='${Version}' apt)" = '2.4.15ubuntu0.1'; \
    test "$(dpkg-query -W -f='${Version}' libapt-pkg6.0)" = '2.4.15ubuntu0.1'; \
    dpkg --verify apt libapt-pkg6.0; \
    rm /tmp/otziv-libapt-pkg.deb /tmp/otziv-apt.deb
USER 1000:0
LABEL otziv.apt="2.4.15ubuntu0.1" otziv.apt-advisory="CVE-2026-109518"
