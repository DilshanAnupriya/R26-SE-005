# ---- build stage ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S sprintiq && adduser -S sprintiq -G sprintiq
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN mkdir -p /app/data/attachments && chown -R sprintiq:sprintiq /app
USER sprintiq
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
