FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY tsconfig.json ./
COPY src/ ./src/
COPY names.json ./
RUN npx tsc
CMD ["node", "dist/index.js"]
