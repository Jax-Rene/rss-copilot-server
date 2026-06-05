MVNW := ./mvnw
HOMEBREW_JAVA_HOME := /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
JAVA_HOME_FOR_MAVEN := $(or $(JAVA_HOME),$(if $(wildcard $(HOMEBREW_JAVA_HOME)),$(HOMEBREW_JAVA_HOME)))
MAVEN_ENV := $(if $(JAVA_HOME_FOR_MAVEN),JAVA_HOME="$(JAVA_HOME_FOR_MAVEN)")
MAVEN_ARGS ?= $(ARGS)

.PHONY: build test smoke lint deploy dev run

build:
	$(MAVEN_ENV) $(MVNW) clean package -DskipTests

test:
	$(MAVEN_ENV) $(MVNW) test $(MAVEN_ARGS)

smoke:
	$(MAVEN_ENV) $(MVNW) -Dtest=ServerE2ETest test $(MAVEN_ARGS)

lint:
	$(MAVEN_ENV) $(MVNW) spotless:check $(MAVEN_ARGS)

deploy:
	docker build -t rss-copilot-server:latest .

dev:
	$(MAVEN_ENV) $(MVNW) spring-boot:run $(MAVEN_ARGS)

run: dev
