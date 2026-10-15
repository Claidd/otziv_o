FROM ghcr.io/claidd/otziv-security@sha256:3bf3e8465193c365e83dc2959a1ce944791c8fd49cf58b68aa96783f63df21b7

# Preserve the reviewed MySQL 9.7.3 server and runtime; apply only Oracle's
# pcre2 security erratum. Refuse unrelated package or engine changes.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^pcre2-' | sort > /tmp/packages.before \
    && microdnf update -y pcre2 pcre2-syntax && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^pcre2-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' pcre2)" = 10.40-7.0.1.el9_8 \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' pcre2-syntax)" = 10.40-7.0.1.el9_8 \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = 9.7.3 \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
