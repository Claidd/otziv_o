FROM ghcr.io/claidd/otziv-security@sha256:370d1bbed24bb5f1178ebe2a332f018f8224cf8ac48bc61103bc4d470e9030b2

# Update the signed Oracle Linux base packages (curl, glibc, OpenSSL, rpm, xz, gawk, GLib) without changing
# mysqld or any other RPM. Weak dependencies stay out, so exactly these 11 packages change.
RUN sha256sum /usr/sbin/mysqld > /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -Ev '^(curl|libcurl|glibc|glibc-common|glibc-minimal-langpack|openssl-libs|rpm|rpm-libs|xz-libs|gawk|glib2)-[0-9]' | sort > /tmp/packages.before \
    && microdnf update -y --setopt=install_weak_deps=0 \
        curl-7.76.1-44.el9_9.1 libcurl-7.76.1-44.el9_9.1 \
        glibc-2.34-290.0.1.el9_9 glibc-common-2.34-290.0.1.el9_9 glibc-minimal-langpack-2.34-290.0.1.el9_9 \
        openssl-libs-3.5.9-1.0.1.el9_9 rpm-4.16.1.3-43.el9_9 rpm-libs-4.16.1.3-43.el9_9 \
        xz-libs-5.2.5-8.el9_9.1 gawk-5.1.0-6.el9_8.1 glib2-2.68.4-29.el9_9.1 \
    && microdnf clean all \
    && sha256sum -c /tmp/mysql-engine.sha256 \
    && rpm -qa | grep -Ev '^(curl|libcurl|glibc|glibc-common|glibc-minimal-langpack|openssl-libs|rpm|rpm-libs|xz-libs|gawk|glib2)-[0-9]' | sort > /tmp/packages.after \
    && test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)" \
    && test "$(rpm -q curl libcurl glibc glibc-common glibc-minimal-langpack openssl-libs rpm rpm-libs xz-libs gawk glib2 | tr '\n' ' ')" = 'curl-7.76.1-44.el9_9.1.x86_64 libcurl-7.76.1-44.el9_9.1.x86_64 glibc-2.34-290.0.1.el9_9.x86_64 glibc-common-2.34-290.0.1.el9_9.x86_64 glibc-minimal-langpack-2.34-290.0.1.el9_9.x86_64 openssl-libs-3.5.9-1.0.1.el9_9.x86_64 rpm-4.16.1.3-43.el9_9.x86_64 rpm-libs-4.16.1.3-43.el9_9.x86_64 xz-libs-5.2.5-8.el9_9.1.x86_64 gawk-5.1.0-6.el9_8.1.x86_64 glib2-2.68.4-29.el9_9.1.x86_64 ' \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = '9.7.3' \
    && rm /tmp/mysql-engine.sha256 /tmp/packages.before /tmp/packages.after \
    && mysqld --version
