FROM ghcr.io/claidd/otziv-security@sha256:89081171be2ff681481a2baa8d38303965c191acc2e1ba59fc852ab4a1a4496e
# Same MySQL engine, configuration and data format; only vendor libxml2 changes.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libxml2-' | sort > /tmp/packages.before \
    && microdnf update -y libxml2 && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -v '^libxml2-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' libxml2)" = 2.9.13-14.el9_8.5 \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = 9.7.3 \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
