FROM ghcr.io/claidd/otziv-security@sha256:6d129626434475b9c0984cc0f766ac28aced19fb7fa430a2c261d18419be1caf
# Complete official FreeMarker artifact; retain the path registered by Quarkus.
ADD --chown=1000:0 --checksum=sha256:0fac87dddd78f1223139e8ef88e819c7f483c0a3835cdf5982ad5e4576d1d896 \
    https://repo.maven.apache.org/maven2/org/freemarker/freemarker/2.3.35/freemarker-2.3.35.jar \
    /opt/keycloak/lib/lib/main/org.freemarker.freemarker-2.3.32.jar
LABEL otziv.freemarker="2.3.35" otziv.freemarker-advisory="CVE-2026-84939"
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
