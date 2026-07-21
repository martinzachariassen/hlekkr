# Common tasks. `make` or `make help` lists them.
.DEFAULT_GOAL := help
.PHONY: help up down logs rebuild db test run fmt clean

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## Start the full stack (Postgres + migrations + API) at http://localhost:8080
	docker compose up --build

down: ## Stop the stack and remove volumes
	docker compose down -v

logs: ## Tail the API logs
	docker compose logs -f api

rebuild: ## Rebuild and restart just the API
	docker compose up -d --build api

db: ## Start only Postgres (for `make run`)
	docker compose up -d db

test: ## Run the full test suite (unit + Testcontainers + e2e)
	cd backend && ./gradlew test

run: db ## Run the API from source against the local Postgres
	cd backend && \
		DATABASE_URL=jdbc:postgresql://localhost:5432/shortener \
		DATABASE_USER=shortener_app DATABASE_PASSWORD=dev_app_pw \
		DATABASE_MIGRATION_USER=shortener_admin DATABASE_MIGRATION_PASSWORD=dev_admin_pw \
		BASE_URL=http://localhost:8080 \
		./gradlew run

fmt: ## Compile with all warnings as errors (the project's lint gate)
	cd backend && ./gradlew compileKotlin compileTestKotlin

clean: ## Remove build outputs
	cd backend && ./gradlew clean
