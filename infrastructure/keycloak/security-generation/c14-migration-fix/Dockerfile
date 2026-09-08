FROM ghcr.io/claidd/otziv-security@sha256:93dd42a5379a80fc0673bc1d0c5601d3b35009c8250394b1e2b45082388c82cc AS reviewed

FROM maven@sha256:9b0bc1420b93f3eafb7401dc3030eac01e773d8d709686d3c4ac351fcf6f9f42 AS source-build
ENV SOURCE_DATE_EPOCH=1787875200
ADD --checksum=sha256:74675b6df593843c5f9384c145c04515c293db228ce76afa9d70c61040f69602 \
    https://codeload.github.com/keycloak/keycloak/tar.gz/6d238b6558037085cc25c915893c3d301a80243e /source.tar.gz
COPY --from=reviewed /opt/keycloak/lib/lib /vendor
COPY managed-models.patch source-before.sha256 source-after.sha256 source-provenance.json /patch/
COPY ApplyPatch.java ModulePatch.java ManagedModelsProof.java /patch/
RUN mkdir /src /classes /tools /out \
    && tar -xzf /source.tar.gz --strip-components=1 -C /src \
    && cd /src && sha256sum --strict -c /patch/source-before.sha256 \
    && javac --release 17 -d /tools /patch/ApplyPatch.java \
    && java -cp /tools ApplyPatch /src /patch/managed-models.patch \
    && sha256sum --strict -c /patch/source-after.sha256 \
    && javac --release 17 -proc:none -parameters -classpath '/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' \
       -d /classes \
       model/storage-private/src/main/java/org/keycloak/models/cache/CacheRealmProvider.java \
       model/storage-private/src/main/java/org/keycloak/migration/migrators/RealmMigration.java \
       model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/RealmCacheSession.java \
    && javac --release 17 -proc:none -classpath '/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' \
       -d /tools /patch/ModulePatch.java /patch/ManagedModelsProof.java \
    && java -cp '/tools:/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' ManagedModelsProof baseline \
       > /out/causal-baseline.txt \
    && java -cp /tools ModulePatch /vendor /classes /out /patch \
    && java -cp '/tools:/out/keycloak-model-storage-private.jar:/out/keycloak-model-infinispan.jar:/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' \
       ManagedModelsProof patched > /out/causal-patched.txt

FROM reviewed
COPY --from=source-build --chown=1000:0 /out/keycloak-model-storage-private.jar /opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-storage-private-26.7.3.jar
COPY --from=source-build --chown=1000:0 /out/keycloak-model-infinispan.jar /opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-infinispan-26.7.3.jar
COPY --from=source-build --chown=1000:0 /out/provenance.json /opt/keycloak/otziv-realm-migration-provenance.json
COPY --from=source-build --chown=1000:0 /out/causal-baseline.txt /out/causal-patched.txt /opt/keycloak/otziv-realm-migration-proof/
LABEL otziv.realm-migration-fix="26.7.3-otziv.1" \
      otziv.realm-migration-upstream-proposal="85a8379427a1d031afdd24f8c688bbcd2917fcca"
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
