FROM ghcr.io/claidd/otziv-security@sha256:c810a0bd4902a4791824bce7795f95f97743506a4263889a1008076e6e9630b0
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(curl|libcurl)-' | sort > /tmp/packages.before \
    && microdnf update -y curl libcurl \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(curl|libcurl)-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' curl)" = 7.76.1-40.el9_8.7 \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' libcurl)" = 7.76.1-40.el9_8.7 \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = 9.7.3 \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
