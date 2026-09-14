FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_ANALYTICS_URL
ARG VITE_GATEWAY_URL
ENV VITE_ANALYTICS_URL=$VITE_ANALYTICS_URL
ENV VITE_GATEWAY_URL=$VITE_GATEWAY_URL
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html ./
COPY src ./src
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
