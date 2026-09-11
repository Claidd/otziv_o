FROM ghcr.io/claidd/otziv-security@sha256:ea9c7f5a1340e33094ce3576d67d220d1baa5b933e69d1b916b3f0bd43e712b7 AS application
FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS packages
COPY packages.lock /tmp/packages.lock
RUN mkdir /packages && apk fetch --no-cache --recursive --output /packages $(cat /tmp/packages.lock) \
    && cd /packages && sha256sum *.apk > SHA256SUMS

FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS iconv-build-packages
COPY iconv-build-packages.lock /tmp/build-packages.lock
RUN mkdir /build-packages && apk fetch --no-cache --recursive --output /build-packages $(cat /tmp/build-packages.lock) \
    && cd /build-packages && sha256sum *.apk > SHA256SUMS

FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS iconv-build
COPY --from=iconv-build-packages /build-packages /tmp/build-packages
RUN apk add --no-network --no-cache /tmp/build-packages/*.apk && mkdir /src
ADD --checksum=sha256:e293ed620cec74651bb4a071317892a478aa6840fab22db45c72d77cd42f9676 https://www.php.net/distributions/php-8.3.33.tar.xz /tmp/php-8.3.33.tar.xz
COPY build-iconv.sh iconv-proof.php /tmp/
RUN sh /tmp/build-iconv.sh

FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
COPY --from=packages /packages /tmp/packages
COPY packages.lock /usr/local/share/otziv/packages.lock
RUN apk add --no-network --no-cache /tmp/packages/*.apk \
    && mkdir -p /usr/local/share/otziv /usr/local/etc /etc/apache2/sites-enabled /sessions /var/run/apache2 /var/lock/apache2 /var/log/apache2 \
    && cp /tmp/packages/SHA256SUMS /usr/local/share/otziv/apk-payload-sha256.txt \
    && cp /lib/apk/db/installed /usr/local/share/otziv/apk-installed-database \
    && rm -rf /tmp/packages \
    && delgroup www-data && addgroup -g 33 -S www-data && adduser -u 33 -G www-data -h /var/www -S -D www-data \
    && chown www-data:www-data /sessions && chmod 1777 /sessions \
    && ln -s /etc/php83 /usr/local/etc/php && ln -s /usr/bin/php83 /usr/local/bin/php \
    && ln -s /usr/sbin/httpd /usr/local/bin/apache2
COPY --from=iconv-build /out/iconv.so /usr/lib/php83/modules/iconv.so
COPY --from=iconv-build /out/provenance /usr/local/share/otziv/iconv-build
RUN printf '%s\n' 'extension=iconv.so' > /etc/php83/conf.d/00_iconv.ini
COPY --from=application /var/www/html /var/www/html
COPY --from=application --chown=33:33 /etc/phpmyadmin /etc/phpmyadmin
COPY --from=application /usr/local/etc/php/conf.d/phpmyadmin-misc.ini /etc/php83/conf.d/phpmyadmin-misc.ini
COPY --from=application /usr/local/etc/php/conf.d/opcache-recommended.ini /etc/php83/conf.d/opcache-recommended.ini
COPY --from=application /usr/local/etc/php/conf.d/session-strict.ini /etc/php83/conf.d/session-strict.ini
COPY --from=application /docker-entrypoint.sh /docker-entrypoint.sh
COPY --from=application /usr/local/bin/apache2-foreground /usr/local/bin/apache2-foreground
COPY httpd.conf /etc/apache2/httpd.conf
COPY ports.conf /etc/apache2/ports.conf
COPY site.conf /etc/apache2/sites-enabled/000-default.conf
COPY envvars /etc/apache2/envvars
COPY --chmod=0755 apachectl /usr/local/bin/apachectl
RUN ln -s /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
    && chmod 755 /docker-entrypoint.sh /usr/local/bin/apache2-foreground \
    && apachectl configtest && php -v && php -m \
    && test "$(php -r 'echo PHP_VERSION;')" = '8.3.33'
ENV PHP_INI_DIR=/usr/local/etc/php APACHE_CONFDIR=/etc/apache2 APACHE_ENVVARS=/etc/apache2/envvars \
    PMA_SSL_DIR=/etc/phpmyadmin/ssl MAX_EXECUTION_TIME=600 MEMORY_LIMIT=512M UPLOAD_LIMIT=2048K \
    TZ=UTC SESSION_SAVE_PATH=/sessions VERSION=5.2.3
WORKDIR /var/www/html
USER root
EXPOSE 80
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["apache2-foreground"]
LABEL com.otziv.security.scope="Exact C7 PMA5.2.3 Composer tree; signed Alpine3.24 PHP83/Apache; unmodified PHP8.3.33 iconv extension with GNU libiconv1.18; community refresh required"
