# Retain the reviewed issuer provider, realm migration fix and Netty patch.
FROM ghcr.io/claidd/otziv-security@sha256:bd5687843becb0cdb232c2b864cc3786fafb8fc91f30f531fa2db7fc4d1fc59d
# Replace complete upstream JARs, retaining registered Quarkus/CLI filenames.
ADD --chown=1000:0 --checksum=sha256:20af26bf6060bb8005cc2389916812c1e0e998dc48d2ced7131b89461b54cff7 \
    https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.85/bcprov-jdk18on-1.85.jar \
    /opt/keycloak/lib/lib/main/org.bouncycastle.bcprov-jdk18on-1.84.jar
ADD --chown=1000:0 --checksum=sha256:20af26bf6060bb8005cc2389916812c1e0e998dc48d2ced7131b89461b54cff7 \
    https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.85/bcprov-jdk18on-1.85.jar \
    /opt/keycloak/bin/client/lib/bcprov-jdk18on-1.84.jar
ADD --chown=1000:0 --checksum=sha256:590f55ed5d68529239898a4a5c4f730b6e37f45d1cfa3fbe51f8485abe32c42d \
    https://repo.maven.apache.org/maven2/org/bouncycastle/bcutil-jdk18on/1.85/bcutil-jdk18on-1.85.jar \
    /opt/keycloak/lib/lib/main/org.bouncycastle.bcutil-jdk18on-1.84.jar
ADD --chown=1000:0 --checksum=sha256:c9f82b2d4e99c4bbdfccf684e52cc06ea06a0b567bfd0d08f9c5a3f417055996 \
    https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.85/bcpkix-jdk18on-1.85.jar \
    /opt/keycloak/lib/lib/main/org.bouncycastle.bcpkix-jdk18on-1.84.jar
LABEL otziv.bouncycastle="1.85" otziv.bouncycastle-advisories="CVE-2026-8763,CVE-2026-13506"
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
