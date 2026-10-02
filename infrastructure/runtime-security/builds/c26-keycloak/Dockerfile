FROM ghcr.io/claidd/otziv-security@sha256:5c73f4ab9b91ea48fc11333d4ebd93e702959ea558318cb6d7849deb5387d4f4 AS parent
FROM ghcr.io/claidd/otziv-security@sha256:f4c38162a10001267b4667afc890a7a9438b5a27cb456c77c87cdcc5aa8d39f6 AS patch
RUN apk add --no-cache python3
ADD --checksum=sha256:8c5623b98f32d5f7e1287ff61ed50e08ab7b11381e991fff89327faabba42261 https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.7/jackson-core-2.21.7.jar /tmp/core.jar
COPY --from=parent /opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar /tmp/cli.jar
COPY --from=parent /opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-core-2.21.5.jar /tmp/old-core.jar
COPY patch_cli.py /tmp/patch_cli.py
RUN python3 /tmp/patch_cli.py
FROM parent
# Quarkus's generated classpath binds this filename; the JAR contains 2.21.7.
COPY --chown=1000:0 --chmod=0644 --from=patch /tmp/core.jar /opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-core-2.21.5.jar
COPY --chown=1000:0 --chmod=0644 --from=patch /tmp/patched-cli.jar /opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar
