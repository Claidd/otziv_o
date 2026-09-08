FROM nginx@sha256:dc5069ad14f19660b141b21236140b91656bf89bbc3e2417c70ae650cd66104c
RUN apk upgrade --no-cache && nginx -v
LABEL com.otziv.security.patch="Official nginx1.30.4 stable; same Alpine3.24 branch package updates"
