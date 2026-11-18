# Language Makefile for the TypeScript package (@grow-codes/zipkin).
# Targets: deps build lint test coverage license pack docs clean (contract shared by every language).

NPM ?= npm
VITEST_FLAGS ?= --pool=threads --maxWorkers=2

.PHONY: deps build lint test coverage license pack docs clean integration format

deps:
	@if [ -f package-lock.json ]; then $(NPM) ci --no-audit --no-fund; else $(NPM) install --no-audit --no-fund; fi

build:
	$(NPM) run --silent typecheck
	$(NPM) run --silent build

lint:
	npx --no-install tsc --noEmit
	npx --no-install eslint . --max-warnings 0
	npx --no-install prettier --check .

test:
	npx --no-install vitest run $(VITEST_FLAGS)

# Fails below the SPEC section 7 gates (lines and branches >= 90 %) and writes coverage/summary.json.
coverage:
	npx --no-install vitest run --coverage $(VITEST_FLAGS)
	node scripts/coverage-summary.mjs

license:
	node scripts/license-check.mjs

pack: build
	mkdir -p dist
	rm -f dist/*.tgz
	for f in LICENSE NOTICE; do if [ -f ../$$f ]; then cp ../$$f $$f; fi; done
	$(NPM) pack --ignore-scripts --pack-destination dist

# Not part of the shared contract: pack, install the tarball into integration/ and run the
# integration tests against GROW_ZIPKIN_URL (needs a running service).
integration: pack
	cd integration && $(NPM) run install-package && $(NPM) test

format:
	npx --no-install prettier --write .

docs:
	npx --no-install typedoc

clean:
	rm -rf lib dist coverage docs/api integration/node_modules
