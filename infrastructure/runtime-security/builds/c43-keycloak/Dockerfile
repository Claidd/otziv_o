# Preserve the accepted C35 issuer, provider and database version; replace Guava only.
FROM ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c \
    https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar \
    /opt/keycloak/lib/lib/main/com.google.guava.guava-33.5.0-jre.jar
LABEL otziv.guava="33.7.2-jre" otziv.guava-advisory="CVE-2026-102554"
RUN --network=none /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
