.PHONY: install jar lint breaking test test-java test-mcp contract postman run token approve demo demo-scripted

# Homebrew's JDK is keg-only, so it isn't on PATH unless it was linked by hand.
BREW_JDK := /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
JAVA_HOME ?= $(if $(wildcard $(BREW_JDK)),$(BREW_JDK))
export JAVA_HOME
JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
JAR := target/entitlement-platform-1.0.0.jar
SPECTRAL := npx --yes @stoplight/spectral-cli@6.17.0

install: jar
	uv sync

jar:
	./mvnw -q -DskipTests package

lint: breaking
	uv run python -m openapi_spec_validator openapi.yaml
	$(SPECTRAL) lint openapi.yaml --fail-severity=warn
	uv run ruff check tests mcp

# contract/baseline-v1.yaml is the last released contract. Only copy openapi.yaml over it when
# releasing; never to silence this check.
breaking:
	@command -v oasdiff >/dev/null || { echo "oasdiff is required: brew install oasdiff"; exit 1; }
	oasdiff breaking contract/baseline-v1.yaml openapi.yaml --fail-on ERR --flatten-allof

test: test-java test-mcp

# The API, the mocks and the contract checks.
test-java:
	./mvnw -q test

# The MCP guardrails and the Schemathesis fuzz run, all against the built jar.
test-mcp: jar
	uv run pytest -q

contract: jar
	uv run pytest tests/contract/test_schemathesis.py -q

postman: jar
	./scripts/run_postman.sh

# make approve ID=apr_... [PERSONA=support] [DENY=1]
approve:
	PYTHONPATH=mcp uv run python mcp/demo/approve.py $(ID) --persona $(PERSONA) $(if $(DENY),--deny)

# Needs `make run` going, and the LLM CLI from the README signed in.
demo:
	uv run python mcp/demo/run_demo.py

# The same flow with no LLM and no account needed. Needs `make run` going.
demo-scripted:
	PYTHONPATH=mcp uv run python mcp/demo/scripted_demo.py

run: jar
	$(JAVA) -jar $(JAR)

# make token PERSONA=finance
PERSONA ?= support
token:
	@curl -s localhost:9100/dev/token -H 'content-type: application/json' \
		-d '{"persona":"$(PERSONA)"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
