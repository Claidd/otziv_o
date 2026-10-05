FROM node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5
RUN apt-get update && apt-get upgrade -y && apt-get clean && rm -rf /var/lib/apt/lists/* && \
    rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* && \
    rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg /usr/local/bin/pnpm /usr/local/bin/pnpx && \
    dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libpcre2-8-0)" ge 10.42-1+deb12u2 && \
    dpkg --compare-versions "$(dpkg-query -W -f='${Version}' perl-base)" ge 5.36.0-7+deb12u4
WORKDIR /monitor
COPY --chown=node:node publisher.mjs collect.mjs watch.mjs export-signals.mjs ./
USER node
EXPOSE 9444
CMD ["node", "publisher.mjs", "/config/publisher.json"]
