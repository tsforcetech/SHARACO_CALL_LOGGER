FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev --ignore-scripts
COPY src ./src
USER node
ENV NODE_ENV=production PORT=8080
EXPOSE 8080
CMD ["node", "src/index.js"]
