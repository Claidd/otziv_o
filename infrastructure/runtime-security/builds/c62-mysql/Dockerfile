FROM ghcr.io/claidd/otziv-security@sha256:5ff5fb9db73125ad1056bad980eb17c25d944c8a5775b53dbfe845b826ae9109

# Update the signed Oracle Linux library without changing mysqld or other RPMs.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libxml2-' | sort > /tmp/packages.before \
    && microdnf update -y libxml2-2.9.13-18.el9_9.1 \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libxml2-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q libxml2)" = 'libxml2-2.9.13-18.el9_9.1.x86_64' \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = '9.7.3' \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
