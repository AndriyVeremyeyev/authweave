SHELL := /bin/sh

AI_WORKER_PYTHON ?= .venv/bin/python
PYTHON ?= python3.13

.PHONY: help setup setup-env setup-web setup-ai setup-contracts \
	check check-policy check-core check-web check-ai check-contracts \
	setup-auth check-auth-config auth-up auth-status auth-check auth-password-check auth-down \
	generate-jooq infra-up infra-status infra-down seed-core store-catalog-proposal store-catalog-impact dev-core dev-web dev-ai

help:
	@printf '%s\n' \
		'AuthWeave development commands:' \
		'  make setup           Install local project dependencies' \
		'  make check           Run every local validation command' \
		'  make check-policy    Check public files for Cyrillic text' \
		'  make check-core      Run Core API tests with Testcontainers' \
		'  make check-web       Lint, test and build the web application' \
		'  make check-ai        Lint and test the AI worker' \
		'  make check-contracts Validate OpenAPI and JSON Schemas' \
		'  make generate-jooq   Migrate local PostgreSQL and regenerate jOOQ types' \
		'  make seed-core       Add synthetic assessments without replacing existing data' \
		'  make store-catalog-proposal  Store an unreviewed proposal from an explicit local JSON file' \
		'  make store-catalog-impact    Save a conditional scenario report for an exact proposal revision' \
		'  make infra-up        Start local PostgreSQL' \
		'  make infra-status    Show local infrastructure status' \
		'  make infra-down      Stop local infrastructure' \
		'  make setup-auth      Create separate ignored local ZITADEL secrets; no install/start' \
		'  make check-auth-config  Test identity config without downloading images or starting services' \
		'  make auth-up         Download/start the optional loopback-only ZITADEL lab' \
		'  make auth-status     Show only identity lab containers' \
		'  make auth-check      Check local OIDC discovery and Login UI readiness; no login' \
		'  make auth-password-check  Verify the synthetic admin password; delete the temporary session' \
		'  make auth-down       Stop identity lab; preserve its database and bootstrap volumes' \
		'  make dev-core        Start the Core API using infra/.env' \
		'  make dev-web         Start the Next.js development server' \
		'  make dev-ai          Start the AI worker development server'

setup: setup-env setup-web setup-ai setup-contracts

setup-env:
	python3 scripts/create_local_env.py

setup-web:
	cd apps/web && npm ci

setup-ai:
	$(PYTHON) -m venv services/ai-worker/.venv
	services/ai-worker/.venv/bin/python -m pip install --disable-pip-version-check -e "services/ai-worker[dev]"

setup-contracts:
	cd packages/contracts && npm ci

check: check-policy check-auth-config check-core check-web check-ai check-contracts

setup-auth:
	$(PYTHON) scripts/local_identity.py setup

check-auth-config:
	$(PYTHON) -m unittest discover -s scripts/tests -p 'test_local_identity.py'
	$(PYTHON) scripts/local_identity.py config-check

auth-up:
	$(PYTHON) scripts/local_identity.py up

auth-status:
	$(PYTHON) scripts/local_identity.py status

auth-check:
	$(PYTHON) scripts/local_identity.py check

auth-password-check:
	$(PYTHON) scripts/local_identity.py password-check

auth-down:
	$(PYTHON) scripts/local_identity.py down

check-policy:
	python3 scripts/check_public_language.py

check-core:
	cd services/core-api && ./mvnw --batch-mode --no-transfer-progress test
	node packages/contracts/scripts/validate-core-http.mjs services/core-api/target/core-http-contract-samples.json

check-web:
	cd apps/web && npm run lint && npm test && npm run build

check-ai:
	cd services/ai-worker && $(AI_WORKER_PYTHON) -m ruff check . && $(AI_WORKER_PYTHON) -m pytest

check-contracts:
	cd packages/contracts && npm run check
	node packages/contracts/scripts/generate-web-profile.mjs --check

generate-jooq:
	@set -a; . ./infra/.env; set +a; cd services/core-api; \
		exec ./mvnw --batch-mode --no-transfer-progress -Pjooq-codegen generate-sources

infra-up:
	cd infra && docker compose up --detach

infra-status:
	cd infra && docker compose ps

infra-down:
	cd infra && docker compose down

seed-core:
	@set -a; . ./infra/.env; set +a; cd services/core-api; \
		exec ./mvnw --batch-mode --no-transfer-progress spring-boot:run \
		-Dspring-boot.run.arguments="--seed-assessments --spring.main.web-application-type=none"

store-catalog-proposal:
	@set -a; . ./infra/.env; set +a; cd services/core-api; \
		exec ./mvnw --batch-mode --no-transfer-progress spring-boot:run \
		-Dspring-boot.run.arguments="--store-catalog-proposal --spring.main.web-application-type=none"

store-catalog-impact:
	@set -a; . ./infra/.env; set +a; cd services/core-api; \
		exec ./mvnw --batch-mode --no-transfer-progress spring-boot:run \
		-Dspring-boot.run.arguments="--store-catalog-impact --spring.main.web-application-type=none"

dev-core:
	@set -a; . ./infra/.env; set +a; cd services/core-api; exec ./mvnw spring-boot:run

dev-web:
	cd apps/web && npm run dev

dev-ai:
	cd services/ai-worker && $(AI_WORKER_PYTHON) -m uvicorn authweave_ai_worker.main:app --reload
