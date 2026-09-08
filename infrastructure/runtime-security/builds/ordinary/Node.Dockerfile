FROM node@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf
COPY npm-patches.json patch-npm.mjs /patches/
ADD --checksum=sha256:5d06001fddd25cbee90c96db4dc5b7b57711b984c3141e28d10f143deb52dbaf https://registry.npmjs.org/brace-expansion/-/brace-expansion-5.0.9.tgz /patches/brace-expansion-5.0.9.tgz
ADD --checksum=sha256:ad1790063beea11a312c801df30d58e147de762f4f77787552376eb7424623e5 https://registry.npmjs.org/ip-address/-/ip-address-10.3.1.tgz /patches/ip-address-10.3.1.tgz
ADD --checksum=sha256:bcedf25a21daecd1a18fb5e19ab855b7d79ec8ef1da175e8ba85cfc0ed0069d1 https://registry.npmjs.org/tar/-/tar-7.5.21.tgz /patches/tar-7.5.21.tgz
RUN apk upgrade --no-cache \
    && node /patches/patch-npm.mjs \
    && node --version && npm --version \
    && mkdir -p /usr/local/share/otziv \
    && cp /patches/npm-patches.json /usr/local/share/otziv/npm-patches.json \
    && rm -rf /patches
LABEL com.otziv.security.patch="Node24.20.0/npm11.19.0 unchanged; exact compatible npm vendor package patches and Alpine3.24 updates"
