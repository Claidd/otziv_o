FROM ghcr.io/claidd/otziv-security@sha256:d8b2c5d02611016eee74b77caede5c57a16cd8a1f379bf5c74d322bfd1b5f75d

# Preserve the MySQL engine, entrypoint and every package except curl/libcurl.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(curl|libcurl)-' | sort > /tmp/packages.before \
    && microdnf update -y curl libcurl \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(curl|libcurl)-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' curl)" = '7.76.1-40.el9_8.8' \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' libcurl)" = '7.76.1-40.el9_8.8' \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = '9.7.3' \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
