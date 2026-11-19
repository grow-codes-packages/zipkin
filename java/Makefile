# Language Makefile for the Java package (codes.grow.packages:zipkin).
# Targets: deps build lint test coverage license pack docs clean (contract shared by every language).

MVN ?= $(shell command -v mvn >/dev/null 2>&1 && echo mvn || echo ./mvnw)
MVN_FLAGS ?= -B -T 1 --no-transfer-progress
M := $(MVN) $(MVN_FLAGS)
JACOCO_CSV := target/site/jacoco/jacoco.csv
RELEASE_CHECK ?= python3 ../.github/scripts/release-check.py

.PHONY: deps build lint test coverage license pack release-check docs clean

deps:
	$(M) -q dependency:go-offline

build:
	$(M) -DskipTests -Djacoco.skip=true test-compile

lint:
	$(M) -Djacoco.skip=true spotless:check test-compile

format:
	$(M) spotless:apply

test:
	$(M) test

# Fails below the SPEC section 7 gates (LINE and BRANCH >= 90 %, JaCoCo BUNDLE) and writes
# coverage/summary.json. The summary is written before the gate is checked.
coverage:
	$(M) test
	@mkdir -p coverage
	@awk -F, 'NR > 1 { bm += $$6; bc += $$7; lm += $$8; lc += $$9 } \
	  END { lt = lm + lc; bt = bm + bc; \
	    lp = lt ? lc * 100 / lt : 100; bp = bt ? bc * 100 / bt : 100; \
	    printf "{\"lines\": {\"covered\": %d, \"total\": %d, \"pct\": %.2f}, ", lc, lt, lp; \
	    if (bt) printf "\"branches\": {\"covered\": %d, \"total\": %d, \"pct\": %.2f}}\n", bc, bt, bp; \
	    else printf "\"branches\": null}\n" }' $(JACOCO_CSV) > coverage/summary.json
	@cat coverage/summary.json
	$(M) jacoco:check@coverage-check

license:
	$(M) -Dlicense.force=true license:add-third-party

pack:
	$(M) -DskipTests -Djacoco.skip=true install
	@mkdir -p dist
	rm -f dist/*.jar
	cp target/zipkin-*.jar dist/
	@ls -1 dist

# Release readiness: the `release` profile without credentials (GPG signing skipped, nothing
# deployed) builds jar + sources + javadoc jars; the POM must carry the Maven Central metadata.
release-check:
	$(M) -P release -Dgpg.skip=true -Dmaven.test.skip=true -Djacoco.skip=true verify
	$(RELEASE_CHECK) java

docs:
	$(M) -Djacoco.skip=true javadoc:javadoc

clean:
	$(M) -q clean
	rm -rf coverage dist
