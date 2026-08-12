# Produkční obraz webu — Caddy servíruje statický Angular build A ZÁROVEŇ proxuje /api a
# /graphql na backend (Caddyfile), aby web i API byly na stejném originu. Refresh token je
# httpOnly cookie se SameSite=Strict (docs/soukromi.md) — na dvou různých doménách by se
# vůbec neodeslala a přihlášení na webu by se rozbilo.
#
# `npm run codegen` se tu záměrně NESPOUŠTÍ — výstup je součást repozitáře (CLAUDE.md, CI ho
# hlídá přes `git diff --exit-code`), image staví jen z toho, co je commitnuté.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist/frontend/browser /srv
