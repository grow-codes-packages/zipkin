# Python package Makefile (contract shared with CI and the repo root Makefile).
# Targets: deps build lint test coverage license pack docs clean

PYTHON ?= python3
VENV ?= .venv
PY := $(VENV)/bin/python
COVERAGE_LINES ?= 90
COVERAGE_BRANCHES ?= 90
PKG := grow_zipkin

.PHONY: deps build lint test coverage license pack docs clean

$(PY):
	$(PYTHON) -m venv $(VENV)
	$(PY) -m pip install --quiet --upgrade pip

deps: $(PY)
	$(PY) -m pip install --quiet -e ".[dev,all]"

build:
	$(PY) -m compileall -q src/$(PKG)
	$(PY) -c "import $(PKG); print('$(PKG)', $(PKG).__version__)"

lint:
	$(PY) -m ruff check src tests tools
	$(PY) -m ruff format --check src tests tools
	$(PY) -m mypy

test:
	$(PY) -m pytest -q

coverage:
	@mkdir -p coverage
	$(PY) -m pytest -q --cov=$(PKG) --cov-branch \
		--cov-report=term --cov-report=json:coverage/coverage.json
	$(PY) tools/covergate.py coverage/coverage.json coverage/summary.json \
		--lines $(COVERAGE_LINES) --branches $(COVERAGE_BRANCHES)

# Installs the package with every optional runtime extra into a clean venv and checks the
# licences of everything that ends up there against the SPEC allowlist (tools/licensecheck.py;
# dev tools are not included).
license:
	rm -rf build/license-venv
	$(PYTHON) -m venv build/license-venv
	build/license-venv/bin/python -m pip install --quiet ".[all]"
	$(PY) -m piplicenses --python build/license-venv/bin/python --from=mixed --format=json \
		--ignore-packages grow-zipkin pip setuptools wheel > build/licenses.json
	$(PY) tools/licensecheck.py < build/licenses.json

pack:
	rm -rf dist
	@for f in LICENSE NOTICE; do if [ -f ../$$f ] && [ ! -f $$f ]; then cp ../$$f $$f; fi; done
	$(PY) -m build --no-isolation --outdir dist .

docs:
	$(PY) -m mkdocs build --strict --config-file mkdocs.yml --site-dir site

clean:
	rm -rf build dist site coverage .pytest_cache .mypy_cache .ruff_cache .coverage
	find . -name __pycache__ -type d -prune -exec rm -rf {} +
