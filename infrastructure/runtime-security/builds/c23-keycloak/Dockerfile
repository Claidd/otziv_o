FROM ghcr.io/claidd/otziv-security@sha256:dd68530849039933b31724522ff290c5d4253175bdd9e23f0d132f7e66aa53ff
USER 0:0
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3=3.0.2-0ubuntu1.30 openssl=3.0.2-0ubuntu1.30 \
    && rm -rf /var/lib/apt/lists/*
USER 1000:0
