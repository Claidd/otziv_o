FROM ghcr.io/claidd/otziv-security@sha256:eda70c6cb0a835e788a5ec96539610e9ef6a574ffc4129c9c5717acd647e159a

# CVE-2026-111497. Keep the MySQL engine and every unrelated RPM unchanged.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libbrotli-' | sort > /tmp/packages.before \
    && microdnf update -y libbrotli \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libbrotli-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' libbrotli)" = '1.0.9-10.0.1.el9_8' \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = '9.7.3' \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
