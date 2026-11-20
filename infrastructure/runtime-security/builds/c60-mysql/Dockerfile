FROM ghcr.io/claidd/otziv-security@sha256:52be6ed6c0039196dca88202ffd9714520d509bdde9b2f575238d1ea2d81028a

# Official Oracle Linux fix for CVE-2026-53615; preserve MySQL and unrelated RPMs.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(libblkid|libmount|libsmartcols|libuuid)-' | sort > /tmp/packages.before \
    && microdnf update -y libblkid-2.37.4-28.0.1.el9_9.1 libmount-2.37.4-28.0.1.el9_9.1 libsmartcols-2.37.4-28.0.1.el9_9.1 libuuid-2.37.4-28.0.1.el9_9.1 \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(libblkid|libmount|libsmartcols|libuuid)-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && for package in libblkid libmount libsmartcols libuuid; do test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' "$package")" = '2.37.4-28.0.1.el9_9.1' || exit 1; done \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = '9.7.3' \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
