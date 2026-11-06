FROM ghcr.io/claidd/otziv-security@sha256:a28faa872c401b182e11021fc1cb2c20d10c4ade88dabf8d6be49c7a0660004b

# Official symfony/expression-language v5.4.54 (CVE-2026-109449), pinned to its tag commit and file hashes.
# v5.4.45 -> v5.4.54 changes only Parser.php and composer.json in the distributed package.
ADD --checksum=sha256:46bf564570f37a879d8254a2ebe9d3f4cadafe40c2043dbd139ac0358ceb844e \
    https://raw.githubusercontent.com/symfony/expression-language/f371786ec76ca4bed6a7ec03802ba2525f6714db/Parser.php \
    /tmp/expression-language/Parser.php
ADD --checksum=sha256:74810da27bc65d9af9ab2770f7a66da090c2e3933e6276258765118834d79317 \
    https://raw.githubusercontent.com/symfony/expression-language/f371786ec76ca4bed6a7ec03802ba2525f6714db/composer.json \
    /tmp/expression-language/composer.json
COPY record-expression-language.php /tmp/record-expression-language.php

# Keep the accepted C47 application, server and configuration bytes intact apart from those two
# package files and their Composer metadata. Install only the official Alpine 3.24 zlib 1.3.2-r1 update.
RUN set -eu; set -o pipefail; \
    el=/var/www/html/vendor/symfony/expression-language; meta=/var/www/html/vendor/composer; \
    inventory() { \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc \
        /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh \
        /usr/local/bin/apache2-foreground /usr/local/bin/php /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
        -exec stat -c '%a|%u|%g|%F|%N' '{}' ';' | LC_ALL=C sort; \
      find /var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc \
        /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh \
        /usr/local/bin/apache2-foreground /usr/local/bin/php /usr/local/bin/apachectl /usr/local/bin/apache2ctl \
        -type f -exec sha256sum '{}' ';' | LC_ALL=C sort \
        | grep -vxF -e "$(sha256sum "$el/Parser.php")" -e "$(sha256sum "$el/composer.json")" \
          -e "$(sha256sum "$meta/installed.json")" -e "$(sha256sum "$meta/installed.php")"; \
    }; \
    echo '69428dc5a399ee71f390ffe4fd7384299a7945aae4b2b076e360fe21e0ecd4f9  /tmp/record-expression-language.php' | sha256sum -c -; \
    echo "aab358fc18ebcffb7db500062c49b0c7181a1138eb8433a6e35af1a0ca3562f5  $el/Parser.php" | sha256sum -c -; \
    echo "4c536f5a1d51a949b70a80c1850cf2bcf277722586381a67f09e15fa9fd5eda5  $el/composer.json" | sha256sum -c -; \
    test -f /var/www/html/vendor/symfony/polyfill-php80/bootstrap.php; \
    apk --no-network info -v -e zlib | grep -Fx 'zlib-1.3.2-r0'; \
    inventory > /tmp/payload.before; \
    php -m | LC_ALL=C sort > /tmp/modules.before; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^zlib-[0-9]' > /tmp/packages.before; \
    apk add --no-cache --repositories-file /dev/null \
      --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main zlib=1.3.2-r1; \
    apk --no-network info -v -e zlib | grep -Fx 'zlib-1.3.2-r1'; \
    apk --no-network info -v | LC_ALL=C sort | grep -Ev '^zlib-[0-9]' > /tmp/packages.after; \
    cmp /tmp/packages.before /tmp/packages.after; \
    cat /tmp/expression-language/Parser.php > "$el/Parser.php"; \
    cat /tmp/expression-language/composer.json > "$el/composer.json"; \
    php /tmp/record-expression-language.php "$meta"; \
    echo "46bf564570f37a879d8254a2ebe9d3f4cadafe40c2043dbd139ac0358ceb844e  $el/Parser.php" | sha256sum -c -; \
    echo "74810da27bc65d9af9ab2770f7a66da090c2e3933e6276258765118834d79317  $el/composer.json" | sha256sum -c -; \
    inventory > /tmp/payload.after; cmp /tmp/payload.before /tmp/payload.after; \
    php -l "$el/Parser.php"; php -l "$meta/installed.php"; \
    php -r 'require "/var/www/html/vendor/autoload.php"; \
      if (\Composer\InstalledVersions::getPrettyVersion("symfony/expression-language") !== "v5.4.54") exit(2); \
      $language = new Symfony\Component\ExpressionLanguage\ExpressionLanguage(); \
      if ($language->evaluate("1 + 2") !== 3) exit(3); \
      try { $language->parse(str_repeat("-", 300) . "1", []); exit(4); } \
      catch (Symfony\Component\ExpressionLanguage\SyntaxError $error) { \
        if (strpos($error->getMessage(), "nested too deeply") === false) exit(5); }'; \
    php -m | LC_ALL=C sort > /tmp/modules.after; cmp /tmp/modules.before /tmp/modules.after; \
    test "$(php -r 'echo PHP_VERSION;')" = '8.3.36'; apachectl configtest; \
    rm -r /tmp/expression-language /tmp/record-expression-language.php /tmp/payload.before /tmp/payload.after \
      /tmp/packages.before /tmp/packages.after /tmp/modules.before /tmp/modules.after
LABEL com.otziv.security.zlib-refresh="C49: official Alpine3.24 zlib1.3.2-r1; official symfony/expression-language v5.4.54 Parser.php and composer.json; C47 server/application/config and unrelated packages unchanged"
