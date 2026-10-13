# syntax=docker/dockerfile:1.7
# Keep the v11.3.1 frontend and runtime layout while rebuilding its Go binary
# with the October 2026 fixes. The source archive is checksum-bound.
FROM oven/bun:1.4.2-alpine@sha256:d888c0ae6c86d7866ff10c5aafdd9077b36aee6455b33dd270fb93c0dd5cef6f AS assets
WORKDIR /src
ADD --checksum=sha256:1ad00ccd300db670f4775b3d981fbede91bda36b29bc68e3bbd48544a6724bff \
    https://codeload.github.com/amir20/dozzle/tar.gz/refs/tags/v11.3.1 /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 \
    && bun install --frozen-lockfile --ignore-scripts \
    && bun run build

FROM golang:1.27.2-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS build
RUN apk add --no-cache ca-certificates openssl && mkdir /data
WORKDIR /src
COPY --from=assets /src /src
ENV GOTOOLCHAIN=local CGO_ENABLED=0
# Dozzle embeds a certificate for its optional agent protocol. Our deployment
# uses docker-observer over the internal network, so each build gets a distinct
# certificate and key; no credential is checked into source control.
RUN openssl genpkey -algorithm Ed25519 -out shared_key.pem \
    && openssl req -new -key shared_key.pem -out /tmp/shared_request.csr \
       -subj '/C=US/ST=California/L=San Francisco/O=Dozzle' \
    && openssl x509 -req -in /tmp/shared_request.csr -signkey shared_key.pem \
       -out shared_cert.pem -days 1825 \
    && go get golang.org/x/net@v0.60.0 \
    && go mod verify \
    && go test ./internal/cli/... \
    && go build -trimpath -buildvcs=false \
       -ldflags '-s -w -X github.com/amir20/dozzle/internal/cli.Version=v11.3.1-otziv.1' \
       -o /dozzle . \
    && go version -m /dozzle > /buildinfo.txt

FROM scratch
COPY --from=build /data /data
COPY --from=build /tmp /tmp
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /dozzle /dozzle
COPY --from=build /buildinfo.txt /usr/share/otziv-build/dozzle.buildinfo.txt
COPY --from=assets /src/LICENSE /usr/share/licenses/dozzle/LICENSE
LABEL org.opencontainers.image.source="https://github.com/amir20/dozzle" \
      org.opencontainers.image.version="v11.3.1-otziv.1" \
      com.otziv.security.patch="Go1.27.2; golang.org/x/net0.60.0"
EXPOSE 8080
ENTRYPOINT ["/dozzle"]
