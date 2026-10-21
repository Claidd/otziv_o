FROM ghcr.io/claidd/otziv-security@sha256:28521a2c331136328abde7b504ae0ded617c74caa8a4a2554f1f0494d4df9009

# Retain the reviewed MySQL 9.7.3 server and all unrelated RPMs. Apply only
# Oracle's OpenSSL erratum for CVE-2026-84782.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^openssl-libs-' | sort > /tmp/packages.before \
    && microdnf update -y openssl-libs && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^openssl-libs-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{EPOCH}:%{VERSION}-%{RELEASE}' openssl-libs)" = 1:3.5.8-2.0.1.el9_8 \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = 9.7.3 \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
