# nginxinc/nginx-unprivileged already runs as a non-root user (uid 101) and
# listens on unprivileged ports by default — AGENTS.md requires containers
# never run as root, and our gateway only needs unprivileged port 8080 anyway.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
