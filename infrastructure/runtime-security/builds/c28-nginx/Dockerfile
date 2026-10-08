FROM ghcr.io/claidd/otziv-security@sha256:847ffa6cc2bcbebf94763b1b2c9e025b24e374b46a815fa4ed918901e6f49578

# Image filtering is not used by the proxy. Remove its TIFF dependency rather
# than retain a vulnerable parser in an otherwise unchanged Nginx runtime.
RUN apk del --no-cache nginx-module-image-filter \
    && ! apk --no-network info -e tiff \
    && nginx -t \
    && nginx -v
