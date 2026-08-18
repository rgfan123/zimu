FROM nginx:1.27-alpine

RUN apk add --no-cache apache2-utils
COPY docker/nginx/entrypoint.sh /usr/local/bin/zimu-nginx-entrypoint
RUN chmod +x /usr/local/bin/zimu-nginx-entrypoint

ENTRYPOINT ["/usr/local/bin/zimu-nginx-entrypoint"]

