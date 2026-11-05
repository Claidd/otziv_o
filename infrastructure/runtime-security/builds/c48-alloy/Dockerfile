# Preserve the accepted C43 Alloy binary and runtime; update only the official Ubuntu apt packages.
FROM ghcr.io/claidd/otziv-security@sha256:27905df1862decf6e9c2543cb476e70e83d68351f7a5bb17194a6a094764db32
ADD --checksum=sha256:a50f3e68a793f90b05f44a4b5f2f59f3ec7687e39b5d8bbd1da44c84671fa3d6 \
    https://security.ubuntu.com/ubuntu/pool/main/a/apt/libapt-pkg6.0t64_2.8.4ubuntu0.1_amd64.deb /tmp/otziv-libapt-pkg.deb
ADD --checksum=sha256:ad2ea7f107f250ba88b0bfdba31812a341e3247589647aa2b03b9dc4e8abf8ed \
    https://security.ubuntu.com/ubuntu/pool/main/a/apt/apt_2.8.4ubuntu0.1_amd64.deb /tmp/otziv-apt.deb
RUN --network=none set -eu; \
    test "$(dpkg-query -W -f='${Version}' apt)" = '2.8.3'; \
    test "$(dpkg-query -W -f='${Version}' libapt-pkg6.0t64)" = '2.8.3'; \
    dpkg -i /tmp/otziv-libapt-pkg.deb /tmp/otziv-apt.deb; \
    test "$(dpkg-query -W -f='${Version}' apt)" = '2.8.4ubuntu0.1'; \
    test "$(dpkg-query -W -f='${Version}' libapt-pkg6.0t64)" = '2.8.4ubuntu0.1'; \
    dpkg --verify apt libapt-pkg6.0t64; \
    rm /tmp/otziv-libapt-pkg.deb /tmp/otziv-apt.deb
LABEL otziv.apt="2.8.4ubuntu0.1" otziv.apt-advisory="CVE-2026-109518"
