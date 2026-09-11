FROM ghcr.io/versity/versitygw@sha256:30292fc2eeacc67a36993b01f7a7a5e3361a19cced0e80c1d71cfa2a4b0a2499
COPY runtime-apk.lock /usr/share/otziv-build/versity/runtime-apk.lock
RUN mkdir -p /usr/share/otziv-build/versity \
    && sha256sum /usr/local/bin/versitygw > /usr/share/otziv-build/versity/binary.before.sha256 \
    && apk info -vv | sort > /usr/share/otziv-build/versity/apk.before.lock \
    && apk add --no-cache --upgrade libssl3=3.5.8-r0 libcrypto3=3.5.8-r0 \
    && apk info -vv | sort > /usr/share/otziv-build/versity/apk.after.lock \
    && test "$(sha256sum /usr/share/otziv-build/versity/apk.after.lock | cut -d ' ' -f 1)" = "$(sha256sum /usr/share/otziv-build/versity/runtime-apk.lock | cut -d ' ' -f 1)" \
    && sha256sum -c /usr/share/otziv-build/versity/binary.before.sha256 \
    && cp /etc/apk/repositories /usr/share/otziv-build/versity/repositories.txt
LABEL org.opencontainers.image.source="https://github.com/Claidd/otziv_o" \
      org.opencontainers.image.licenses="Apache-2.0" \
      com.otziv.upstream.image="ghcr.io/versity/versitygw@sha256:30292fc2eeacc67a36993b01f7a7a5e3361a19cced0e80c1d71cfa2a4b0a2499" \
      com.otziv.derivative="Alpine vendor libssl3/libcrypto3 package update only; Versity v1.8.0 binary unchanged"
