FROM ghcr.io/claidd/otziv-security@sha256:d006cedf890ade4915a098a5362ce0cda9a3fd7ec2c02320f15e92ccfd669122
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3t64=3.0.13-0ubuntu3.16 openssl=3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/*
