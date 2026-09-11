FROM container-registry.oracle.com/mysql/community-server@sha256:bf955135c21e2c1c153417407fbbbb8efa786f487781b1fb6baaf7aba17d4340
# Preserve Oracle's MySQL 9.7.3 server and client binaries. mysql-shell is an
# administrative upgrade-checking tool, not a server runtime dependency.
# Run the mandatory upgrade checker separately before opening the data volume.
RUN microdnf update -y \
    && microdnf remove -y mysql-shell \
    && microdnf clean all \
    && test "$(rpm -q --qf '%{VERSION}' mysql-community-server-minimal)" = 9.7.3 \
    && ! command -v mysqlsh \
    && mysqld --version && mysql --version && mysqldump --version
