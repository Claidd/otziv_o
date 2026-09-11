# phpMyAdmin Alpine candidate

This separate recipe preserves the exact C7 phpMyAdmin 5.2.3 application and its
58 Composer packages. It replaces the Debian runtime with signed, version-pinned
Alpine 3.24.1 APKs. The C7 recipe and source image remain the rollback reference.
This directory does not activate or publish an image.

Build from the repository root:

```sh
docker build --progress=plain -t otziv-phpmyadmin-alpine:reviewed infrastructure/runtime-security/builds/phpmyadmin-alpine
```

`packages.lock` fixes the complete 98-package runtime closure. The original
entrypoint, config helpers, foreground script and application are copied from the
immutable C7 image. Apache keeps prefork/mod_php and the original module set;
the small path adapters preserve `APACHE_PORT`, `_FILE`, BASE64 config, session
and secret-permission behavior. Runtime compilers and development packages are
not installed. `packages.requested` records the initial package selection; the
build consumes the full lock, not that human-readable selection.

## Iconv compatibility

Alpine's stock PHP iconv uses musl and rejects the `utf-8//TRANSLIT` conversion
used by phpMyAdmin for a Windows-1251 SQL import. `iconv-build` compiles only the
unmodified official PHP 8.3.33 `ext/iconv` against signed GNU libiconv 1.18 and
the exact `php83-dev=8.3.33-r0` ABI. It does not rebuild PHP or patch the extension.
The original `php83-iconv` APK is omitted, so package metadata does not claim
ownership of a binary replaced behind its back.

The [official PHP release metadata](https://www.php.net/releases/index.php?json&version=8.3.33)
publishes the tar.xz SHA-256 checked by the Dockerfile. The
[PHP APK recipe](https://raw.githubusercontent.com/alpinelinux/aports/2e2f201883a774da907662c21f46f329312110ec/community/php83/APKBUILD)
fixes API 20230831; the
[GNU libiconv APK recipe](https://raw.githubusercontent.com/alpinelinux/aports/5b9e7c13e61f92251ca4d963c957ee543e48763d/community/gnu-libiconv/APKBUILD)
identifies the signed 1.18 package. Build flags, compiler, all 69 builder APKs,
payload hashes, unchanged official extension files, module hash, ABI and upstream
test results are retained at `/usr/local/share/otziv/iconv-build` in the image.
The nine compatibility assertions and upstream tests fail the build on failure.

## What was actually proved

The final local image passed 75 HTTP/runtime checks and 24 config/session checks,
using only a disposable synthetic MySQL fixture. They cover C7 source, candidate,
custom port 8080 and C7 fallback; all 41 PHP modules; Unicode and actual TCPDF;
gzip, bzip2 and ZIP SQL imports; gzip/ZIP HTTP export followed by restore;
Windows-1251 import; `_FILE` precedence, custom config, limits, persistent secrets,
restart and authenticated cookie-session fallback. Owned resources were removed.

All 4275 application files/symlinks, config files, original entrypoint and
foreground script retain bytes, modes and owners. The final raw pinned Trivy
0.74.0 scan reports zero High/Critical, with no exclusion or adjudication. The
validation JSON binds the exact local index, OCI config, raw report, SBOM,
runtime proof and build inputs. Private evidence paths are hashes/provenance
references, not claims that raw artifacts are publicly hosted.

## Limits that remain explicit

- GNU and glibc have different lossy ASCII transliteration tables: `café` becomes
  `caf'e` and `cafe`, respectively. Actual UTF-8 Cyrillic import and the common
  `€ß` to `EURss` conversion passed. Complete encoding/libc/locale parity is not
  claimed.
- PMA 5.2.3 has gzip and ZIP export, but no bzip2 export option in the original
  application. Bzip2 import was tested; an export feature was not invented.
- The 71 upstream iconv tests yielded 68 passes and three upstream skips: CGI
  SAPI absent, Windows-only, and the upstream musl `setlocale` condition. None
  were locally disabled.
- The scan changes from Debian advisory coverage to Alpine coverage. The 149
  prior HC occurrences disappear with the replaced OS closure; this does not
  prove 149 individual source patches. Two unchanged lower-severity Composer
  findings remain in raw output.
- Alpine community packages, including PHP83, have a shorter maintenance window
  than main packages. Follow the active stable branch and refresh signed package
  pins before community support expires; see the
  [official branch support policy](https://www.alpinelinux.org/releases/).
- This proves an isolated candidate. Production activation, registry publication
  and risk acceptance are separate decisions. No production or main local DB
  state was used or changed.
