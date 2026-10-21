# Preserve the reviewed Keycloak 26.7.3 issuer and update its complete Vert.x 4.5
# runtime set. Quarkus serializes the original classpath filenames, so the
# immutable upstream JARs replace those paths without rewriting their contents.
FROM ghcr.io/claidd/otziv-security@sha256:76ad15fac3c0041798b6b2b87632bbf91ae7c171ebfa813aa1d1633b48f90ff9

ADD --chown=1000:0 --chmod=0664 --checksum=sha256:5103b6ce5121aa783707278b4ec08a19a0405d87b91dced1d9f6054d7ffb5dc1 \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-auth-common/4.5.35/vertx-auth-common-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-auth-common-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:a004fef3622ce6db8ba11c502f2c752dd0975eaf7c8e940e71c4c4ca0ccbf4cd \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-bridge-common/4.5.35/vertx-bridge-common-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-bridge-common-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:b82fe8fe7324af7a37fd41940c228af99ea9c400e96ac55d0cfe8016b85b5337 \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-codegen/4.5.35/vertx-codegen-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-codegen-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:1a274ba3739dc72506d5e801a8337ab734e07fbfd48ece7e77a518fbf40cea9a \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-core/4.5.35/vertx-core-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-core-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:70c91f9d4d9716f130057bc6d286687da9222a7fb96251dd91ac0a7bb55ed143 \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-grpc/4.5.35/vertx-grpc-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-grpc-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:474b83d79944d1b4869452a9712b8cb51c9b6584e48d32ef113720c4de1072f7 \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-grpc-client/4.5.35/vertx-grpc-client-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-grpc-client-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:908557303faea174e8755e6a1fb01771208db2e215dbeaff2549891855b50f8e \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-grpc-common/4.5.35/vertx-grpc-common-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-grpc-common-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:115d31258beffa4c2205ccd614981653df4a3ff48ad6dfb428fb9bd2a8a46962 \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-grpc-server/4.5.35/vertx-grpc-server-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-grpc-server-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:a8d9d06554e23286edb542d9adf15d057bda0399455a8f04ceb27e39bea1a0ac \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-web/4.5.35/vertx-web-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-web-4.5.31.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:e6938a7c555ae87ccde10af71cfb264483ea7f952e7d7724e4196bef21cec64d \
    https://repo.maven.apache.org/maven2/io/vertx/vertx-web-common/4.5.35/vertx-web-common-4.5.35.jar \
    /opt/keycloak/lib/lib/main/io.vertx.vertx-web-common-4.5.31.jar

# Vert.x 4.5.35 ships with Netty 4.1.139 and tcnative 2.0.84. Preserve
# the original registered classpath names and each original file mode.
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:48d805b74e56dc5f92b1f288858185abc86db16d293e8da183542df6243427f2 \
    https://repo.maven.apache.org/maven2/io/netty/netty-buffer/4.1.139.Final/netty-buffer-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-buffer-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:ad43f07219dfb757a87b9c2b4765002be478c5401971379954e3d182f285fe6b \
    https://repo.maven.apache.org/maven2/io/netty/netty-codec/4.1.139.Final/netty-codec-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-codec-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:e044715cfd72e4717a06e41f4614dea0246ac20059d3aa72f36f16be20bc623c \
    https://repo.maven.apache.org/maven2/io/netty/netty-codec-dns/4.1.139.Final/netty-codec-dns-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-codec-dns-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:3109e099f34523799479c70b12b7d3731b9bb891acc8654a145eefd189a63472 \
    https://repo.maven.apache.org/maven2/io/netty/netty-codec-haproxy/4.1.139.Final/netty-codec-haproxy-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-codec-haproxy-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:4cb8bc95386112f3a9da935272781d577d9e70ca4c56cf0eef603a45e9526e02 \
    https://repo.maven.apache.org/maven2/io/netty/netty-codec-http2/4.1.139.Final/netty-codec-http2-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-codec-http2-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:627cfdac2cd5dbd8a33af85977fb81fa6b46f0c00389ab4c8677a415f0f85480 \
    https://repo.maven.apache.org/maven2/io/netty/netty-codec-http/4.1.139.Final/netty-codec-http-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-codec-http-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:94d6800d5f717b40db5d0bf42a77b448d472cd9033735e69e7b18311bd902f23 \
    https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.1.139.Final/netty-codec-socks-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-codec-socks-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:50372d86e27b4b1cf0810d75eb534795966a199049a1e85a0c41bbd2bb97f519 \
    https://repo.maven.apache.org/maven2/io/netty/netty-common/4.1.139.Final/netty-common-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-common-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0644 --checksum=sha256:ff85f00781b956d618b20f33da076c839a5e43e0084108b07bab0c19737168ec \
    https://repo.maven.apache.org/maven2/io/netty/netty-handler/4.1.139.Final/netty-handler-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-handler-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:9c92c2407b6fd3c120ab5efb900475a1fe40ce099703d2500c8679d3c75a90cb \
    https://repo.maven.apache.org/maven2/io/netty/netty-handler-proxy/4.1.139.Final/netty-handler-proxy-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-handler-proxy-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:562a5388c88325b7d6ed05e8168736871ce0e64ab01e0ce52ea7710760b9b457 \
    https://repo.maven.apache.org/maven2/io/netty/netty-resolver/4.1.139.Final/netty-resolver-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-resolver-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:15feeffd0cbc2987ba35b798ca5b05fa1bc10cb880b8aa13e9bd81af3d483525 \
    https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns/4.1.139.Final/netty-resolver-dns-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-resolver-dns-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:be7bff8b6274e7fae12bbad3c565777539e9b069517e0b3acc06d9ac2cd6ec05 \
    https://repo.maven.apache.org/maven2/io/netty/netty-transport/4.1.139.Final/netty-transport-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-transport-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:99ea06d67e3e2792cc93521b686223c78da2561bfdf98533ebb29fed1729cce4 \
    https://repo.maven.apache.org/maven2/io/netty/netty-transport-classes-epoll/4.1.139.Final/netty-transport-classes-epoll-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-transport-classes-epoll-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:039c9ce3bef089dfde34987cac3c928466cb71d54d3df79c0068fd17ee166e51 \
    https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.139.Final/netty-transport-native-epoll-4.1.139.Final-linux-aarch_64.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-transport-native-epoll-4.1.136.Final-linux-aarch_64.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:696fff4fb645d64fec6bc54fae27d9b643d64814e9be3bf42cbd94a608e2bcd0 \
    https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.139.Final/netty-transport-native-epoll-4.1.139.Final-linux-x86_64.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-transport-native-epoll-4.1.136.Final-linux-x86_64.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:8e660ac8a485fc8d8b47d87d04ea33d19902dc6fc5a113e9353b762188b3f333 \
    https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.139.Final/netty-transport-native-unix-common-4.1.139.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-transport-native-unix-common-4.1.136.Final.jar
ADD --chown=1000:0 --chmod=0664 --checksum=sha256:7a4437abf98b0cec053af4443fe321177d2cfccfec7bcad4e7d8b75571bbec4d \
    https://repo.maven.apache.org/maven2/io/netty/netty-tcnative-classes/2.0.84.Final/netty-tcnative-classes-2.0.84.Final.jar \
    /opt/keycloak/lib/lib/main/io.netty.netty-tcnative-classes-2.0.78.Final.jar

LABEL otziv.vertx="4.5.35" otziv.vertx-advisory="CVE-2026-102477" \
    otziv.netty="4.1.139.Final" otziv.netty-tcnative="2.0.84.Final" \
    otziv.netty-handler="4.1.139.Final"
RUN --network=none /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
