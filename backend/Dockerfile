# Imagen para Cloud Run. Multi-stage: las devDependencies y el compilador de
# TypeScript no llegan a la imagen final, que es lo que se arranca en cada cold start.

FROM node:24-alpine AS build
WORKDIR /app

# Se copian primero los manifiestos para que la capa de `npm ci` sólo se invalide
# cuando cambian las dependencias, no en cada cambio de código.
COPY package*.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/dist ./dist

# Usuario sin privilegios: la imagen de node ya trae `node` (uid 1000).
USER node

# Cloud Run inyecta PORT; el 8080 es sólo el valor por defecto en local.
ENV PORT=8080
EXPOSE 8080

CMD ["node", "dist/index.js"]
