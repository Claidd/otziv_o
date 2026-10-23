#!/bin/sh
set -eu
set -o pipefail
# PHP executable/modules deliberately change. Runtime configuration and all
# application files (including Composer dependencies) must remain byte-identical.
find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc \
  /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php \
  /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
  -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort
find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc \
  /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php \
  /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
  -type f -exec sha256sum '{}' ';' | LC_ALL=C sort
