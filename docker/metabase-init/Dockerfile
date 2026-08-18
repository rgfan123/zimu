FROM alpine:3.20

RUN apk add --no-cache curl jq
COPY docker/metabase-init/provision.sh /usr/local/bin/provision-metabase
RUN chmod +x /usr/local/bin/provision-metabase
ENTRYPOINT ["/usr/local/bin/provision-metabase"]
