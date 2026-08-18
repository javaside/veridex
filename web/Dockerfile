# ---------- 构建阶段：Node 22 + npm ci，仅 production build ----------
FROM node:22-alpine AS build
WORKDIR /build
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---------- 运行阶段：非 root nginx，只读根文件系统友好 ----------
FROM nginxinc/nginx-unprivileged:1.27-alpine
# upstream 缺省值：编排层通过 VERIDEX_BACKEND_UPSTREAM 覆盖
ENV VERIDEX_BACKEND_UPSTREAM=veridex-backend:8080
COPY --chown=101:101 web/public/error_page.html /usr/share/nginx/html/error_page.html
COPY --from=build --chown=101:101 /build/dist/ /usr/share/nginx/html/
COPY --chown=101:101 deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --chown=101:101 deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
USER 101:101
