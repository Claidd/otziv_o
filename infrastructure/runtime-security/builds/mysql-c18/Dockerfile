FROM ghcr.io/claidd/otziv-security@sha256:d626770288635060a3b150872e75888c2890d7a580392f04c8924a8071bd1a2d
# Update only the vulnerable OS library; keep the exact reviewed MySQL engine.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libevent-' | sort > /tmp/packages.before \
    && microdnf update -y libevent \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libevent-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' libevent)" = 2.1.13-1.el9_8 \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = 9.7.3 \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
