# Thin wrapper around Maven. Examples:
#   make              build target/nimbus.jar (skips tests)
#   make run          build if needed, then run (default mode: all)
#   make run MODE=server ARGS="--port=8081"
#   make test         run unit + loopback IT tests (mvn verify)

MVN  ?= mvn -B -q
JAVA ?= java
JAR  := target/nimbus.jar
MODE ?= all
ARGS ?=

SOURCES := pom.xml $(shell find src/main -type f)

.PHONY: build run server gui bench fake-upstream test live-test clean help

build: $(JAR)

# Rebuild the jar only when sources or the pom change.
$(JAR): $(SOURCES)
	$(MVN) package -DskipTests

run: $(JAR)
	$(JAVA) -jar $(JAR) $(MODE) $(ARGS)

server gui bench fake-upstream: $(JAR)
	$(JAVA) -jar $(JAR) $@ $(ARGS)

test:
	$(MVN) verify

live-test:
	$(MVN) test -Dgroups=live

clean:
	$(MVN) clean

help:
	@sed -n '1,6p' Makefile
