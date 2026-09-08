FROM phpmyadmin@sha256:3a8a8d6b5289091f959ba0293f21163b3a2fc5741991a53de70b3497fe8d31db AS dependencies
RUN apt-get update && apt-get install -y --no-install-recommends git unzip
ADD --checksum=sha256:7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6 https://getcomposer.org/download/2.10.3/composer.phar /tmp/composer.phar
COPY preserve-pma-runtime.php /tmp/preserve-pma-runtime.php
RUN echo "7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6  /tmp/composer.phar" | sha256sum -c - \
    && cd /var/www/html \
    && php /tmp/preserve-pma-runtime.php \
    && php /tmp/composer.phar --version \
    && COMPOSER_ALLOW_SUPERUSER=1 php /tmp/composer.phar update twig/twig:3.27.0 symfony/cache:5.4.52 --with-dependencies --minimal-changes --no-dev --no-scripts --no-plugins --no-interaction --prefer-dist
FROM phpmyadmin@sha256:3a8a8d6b5289091f959ba0293f21163b3a2fc5741991a53de70b3497fe8d31db
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
COPY --from=dependencies /var/www/html/vendor /var/www/html/vendor
COPY --from=dependencies /var/www/html/composer.lock /var/www/html/composer.lock
COPY --from=dependencies /var/www/html/composer.json /var/www/html/composer.json
COPY --from=dependencies /tmp/installed-before.json /usr/local/share/otziv/pma-installed-before.json
LABEL com.otziv.security.patch="phpMyAdmin5.2.3/PHP8.3 unchanged; Twig3.27.0/SymfonyCache5.4.52 exact patches; same Debian13 branch OS fixes"

