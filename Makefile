.PHONY: build run test docker-up docker-down docker-logs clean infra

build:
	./mvnw clean package -DskipTests

run:
	./mvnw spring-boot:run

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
