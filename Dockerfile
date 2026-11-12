# ---- build stage: compiles the Spring Boot jar (no local Java/Maven needed) ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests=false

# ---- runtime stage: small JRE image ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && mkdir -p /data && chown app:app /data
COPY --from=build /src/target/clean-hour.jar app.jar
USER app
# Stored history (3 months of hourly data + saved day-ahead forecasts) lives on a volume
ENV CLEANHOUR_STORE_PATH=/data/clean-hour-store.json
VOLUME /data

# Sized for a 1 GB host: cap the heap, use the low-overhead serial GC.
ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k -XX:+ExitOnOutOfMemoryError"
ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:${PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
