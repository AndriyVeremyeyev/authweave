# AuthWeave

**AI-assisted identity architecture and change assurance platform for evidence-backed authentication decisions.**

> Status: local application foundation. No production release is available yet.

AuthWeave is an engineering workspace for designing identity and authentication
architecture. It will help engineers collect application requirements, compare identity
provider capabilities, evaluate authentication patterns and produce reviewable
architecture decisions backed by dated evidence.

The project follows three core principles:

- deterministic constraints before AI-generated explanations;
- structured, source-backed provider facts before semantic retrieval;
- human ownership of every final architecture decision.

Development is local-first and incremental. This README describes the project at a
high level; additional documentation remains private while the product is being
shaped.

## Local development

Required tools are Java 21, Node.js 24 with npm 11, Python 3.13, Docker Desktop and
GNU Make. From the repository root, install project-local dependencies and create the
ignored local environment file:

```shell
make setup
```

The setup command generates local database passwords only when `infra/.env` does not
already exist. It never overwrites an existing environment file.

Start PostgreSQL and run all local checks:

```shell
make infra-up
make check
```

Run `make help` to see component-specific checks and development-server commands.
