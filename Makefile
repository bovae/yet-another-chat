.PHONY: build run test format lint docker-up docker-down docker-logs clean infra

build:
	./mvnw clean package -DskipTests

format:
	./mvnw spotless:apply

lint:
	./mvnw verify -DskipTests

run:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

test:
	./mvnw clean verify

docker-up:
	docker compose up --build -d

docker-down:
	docker compose down

infra:
	docker compose up postgres redis -d

docker-logs:
	docker compose logs -f app

clean:
	./mvnw clean
	docker compose down -v
