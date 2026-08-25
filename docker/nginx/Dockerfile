FROM nginx:1.27-alpine

RUN apk add --no-cache apache2-utils
# 站点配置随镜像发布，使镜像自包含。
# 离线部署（目标机连不上 registry、用 offline override 钉镜像）会把 volumes 清空，
# 配置若只靠 bind mount，nginx 会静默退回默认站点、/healthz 返回 404。
# 本地开发仍由 docker-compose.yml 的 bind mount 覆盖此文件，改配置无需重建镜像。
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY docker/nginx/entrypoint.sh /usr/local/bin/zimu-nginx-entrypoint
RUN chmod +x /usr/local/bin/zimu-nginx-entrypoint

ENTRYPOINT ["/usr/local/bin/zimu-nginx-entrypoint"]

