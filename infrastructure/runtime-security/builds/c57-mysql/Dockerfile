FROM ghcr.io/claidd/otziv-security@sha256:0ad40a668c292ebe70297cd4df66b2e9c5067fad3bfdb54179347a597e1ac48f

# Official Oracle Linux util-linux and Kerberos security fixes.
# Keep the MySQL engine and every other installed package byte-identical.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(libblkid|libmount|libsmartcols|libuuid|krb5-libs)-' | sort > /tmp/packages.before \
    && microdnf update -y libblkid-2.37.4-28.0.1.el9_9 libmount-2.37.4-28.0.1.el9_9 libsmartcols-2.37.4-28.0.1.el9_9 libuuid-2.37.4-28.0.1.el9_9 krb5-libs-1.21.1-10.0.1.el9_9.2 \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -vE '^(libblkid|libmount|libsmartcols|libuuid|krb5-libs)-' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && for package in libblkid libmount libsmartcols libuuid; do test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' "$package")" = '2.37.4-28.0.1.el9_9' || exit 1; done \
    && test "$(rpm -q --qf '%{VERSION}-%{RELEASE}' krb5-libs)" = '1.21.1-10.0.1.el9_9.2' \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = '9.7.3' \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
