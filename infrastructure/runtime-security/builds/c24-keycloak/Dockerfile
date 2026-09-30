FROM ghcr.io/claidd/otziv-security@sha256:6b29d37b178d16d7f10ad637157fb0a14acb740338280fc574575e72dae8bf22 AS parent
FROM ghcr.io/claidd/otziv-security@sha256:f4c38162a10001267b4667afc890a7a9438b5a27cb456c77c87cdcc5aa8d39f6 AS patch
RUN apk add --no-cache python3
ADD --checksum=sha256:1290c2795e93e8a6861a6c4d9ff0d844d32f5ea178362cb2721edf5561e828b1 https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.21.7/jackson-databind-2.21.7.jar /tmp/databind.jar
COPY --from=parent /opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar /tmp/cli.jar
COPY patch_cli.py /tmp/patch_cli.py
RUN python3 /tmp/patch_cli.py
FROM parent
# Preserve Quarkus' existing runtime paths; replace only official databind bytes.
COPY --chown=1000:0 --chmod=0644 --from=patch /tmp/databind.jar /opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-databind-2.21.5.jar
COPY --chown=1000:0 --chmod=0644 --from=patch /tmp/patched-cli.jar /opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar
