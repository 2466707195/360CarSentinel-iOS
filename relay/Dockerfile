# 车载哨兵中继：零依赖，直接跑 node server.js
FROM node:20-alpine

WORKDIR /app
COPY server.js ./

ENV PORT=8787
EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -qO- http://127.0.0.1:8787/health || exit 1

CMD ["node", "server.js"]
