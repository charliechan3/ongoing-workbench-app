# ============================================================
# Ongoing Workbench - single-origin production image
# Build: frontend dist -> bundle into Spring Boot static -> jar
# ============================================================

# ---------- Stage 1: build frontend (Vue 3 + Vite) ----------
FROM node:22-bookworm-slim AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: build backend jar + bundle SPA ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
# 前端构建产物作为静态资源打进 jar（与 /api 同源，无 CORS）
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN mvn -B -q package -DskipTests

# ---------- Stage 3: runtime ----------
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai \
    APP_MODE=cloud
WORKDIR /app
COPY --from=backend /build/target/ongoing-workbench-1.0.0.jar app.jar
EXPOSE 8080
# Render 通过 $PORT 注入端口；本地直跑默认 8080
CMD ["sh", "-c", "exec java -jar app.jar --server.port=${PORT:-8080}"]
