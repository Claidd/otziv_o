# Preserve the deployed realm-migration fix and custom issuer provider.
FROM ghcr.io/claidd/otziv-security@sha256:c6ee6afd6750ae0886a608c5837085cb20417a6746ca2ed47b59b1a0fdec1973
# Complete upstream artifact; the registered Quarkus classpath filename stays stable.
ADD --chown=1000:0 --checksum=sha256:d0e4c6ee4779f59f6ab2fb5d388e4f57147c82270164b37945764bb9bda96a44 \
    https://repo.maven.apache.org/maven2/io/netty/netty-handler/4.1.137.Final/netty-handler-4.1.137.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-handler-4.1.136.Final.jar
LABEL otziv.netty-handler="4.1.137.Final" otziv.netty-handler-advisory="CVE-2026-75595"
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
