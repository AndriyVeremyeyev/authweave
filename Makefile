SHELL := /bin/sh

AI_WORKER_PYTHON ?= .venv/bin/python
PYTHON ?= python3.13

.PHONY: help setup setup-env setup-web setup-ai setup-contracts \
	check check-policy check-core check-web check-ai check-contracts \
	generate-jooq infra-up infra-status infra-down dev-core dev-web dev-ai

help:
	@printf '%s\n' \
		'AuthWeave development commands:' \
		'  make setup           Install local project dependencies' \
		'  make check           Run every local validation command' \
		'  make check-policy    Check public files for Cyrillic text' \
		'  make check-core      Run Core API tests with Testcontainers' \
		'  make check-web       Lint and build the web application' \
		'  make check-ai        Lint and test the AI worker' \
		'  make check-contracts Validate OpenAPI and JSON Schemas' \
		'  make generate-jooq   Migrate local PostgreSQL and regenerate jOOQ types' \
		'  make infra-up        Start local PostgreSQL' \
		'  make infra-status    Show local infrastructure status' \
		'  make infra-down      Stop local infrastructure' \
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

check: check-policy check-core check-web check-ai check-contracts

check-policy:
	python3 scripts/check_public_language.py

check-core:
	cd services/core-api && ./mvnw --batch-mode --no-transfer-progress test

check-web:
	cd apps/web && npm run lint && npm run build

check-ai:
	cd services/ai-worker && $(AI_WORKER_PYTHON) -m ruff check . && $(AI_WORKER_PYTHON) -m pytest

check-contracts:
	cd packages/contracts && npm run check

generate-jooq:
	@set -a; . ./infra/.env; set +a; cd services/core-api; \
		exec ./mvnw --batch-mode --no-transfer-progress -Pjooq-codegen generate-sources

infra-up:
	cd infra && docker compose up --detach

infra-status:
	cd infra && docker compose ps

infra-down:
	cd infra && docker compose down

dev-core:
	@set -a; . ./infra/.env; set +a; cd services/core-api; exec ./mvnw spring-boot:run

dev-web:
	cd apps/web && npm run dev

dev-ai:
	cd services/ai-worker && $(AI_WORKER_PYTHON) -m uvicorn authweave_ai_worker.main:app --reload
