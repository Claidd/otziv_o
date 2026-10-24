FROM node:24.22.1-alpine3.24@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53

# Official Node security release; retain Node24/npm11 and Alpine3.24.
ADD --checksum=sha256:ef8448ec78f20b692f04fa6d01f39b5ab34c66404bea3429f5a39c6c9e0be8b4 https://registry.npmjs.org/brace-expansion/-/brace-expansion-5.0.12.tgz /patches/brace-expansion-5.0.12.tgz
ADD --checksum=sha256:29c4b8e0b2d86cb8795571c1959012ce057a14ecfd34858335f47c31e770a04f https://registry.npmjs.org/undici/-/undici-6.29.1.tgz /patches/undici-6.29.1.tgz
ADD --checksum=sha256:d75e1e6a11587954da5e2f0e2b5c4b397a16d28cc2f7bdf64e9027fc2fe593ee https://registry.npmjs.org/http-cache-semantics/-/http-cache-semantics-4.3.0.tgz /patches/http-cache-semantics-4.3.0.tgz
COPY npm-patches.json patch-npm.mjs /patches/
RUN node /patches/patch-npm.mjs \
 && test "$(node --version)" = "v24.22.1" \
 && test "$(npm --version)" = "11.19.1" \
 && mkdir -p /usr/local/share/otziv \
 && cp /patches/npm-patches.json /usr/local/share/otziv/c40-npm-patches.json \
 && rm -rf /patches
LABEL com.otziv.security.node-refresh="C40: official Node24.22.1/npm11.19.1; compatible official npm package updates; no finding suppression"
