# Stage 1: Build
FROM amazoncorretto:21 AS builder
WORKDIR /build

RUN yum install -y tar gzip && yum clean all

COPY .mvn/ .mvn/
COPY mvnw pom.xml lombok.config ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src/ src/
RUN ./mvnw package -DskipTests -B

# Stage 2: Runtime
FROM amazoncorretto:21-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app

COPY --from=builder /build/target/*.jar app.jar
RUN mkdir -p /app/file-storage && chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
