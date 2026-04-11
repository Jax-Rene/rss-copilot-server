MVNW := ./mvnw

.PHONY: build test lint deploy dev run

build:
	$(MVNW) clean package -DskipTests

test:
	$(MVNW) test

lint:
	$(MVNW) spotless:check

deploy:
	docker build -t rss-copilot-server:latest .

dev:
	$(MVNW) spring-boot:run

run: dev
