.PHONY: help up down logs build test backend frontend migrate psql redis clean

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up:        ## Start the full stack
	docker compose up -d --build

down:      ## Stop everything
	docker compose down

logs:      ## Tail backend logs
	docker compose logs -f backend

infra:     ## Start only postgres, redis and mailpit (for local IDE runs)
	docker compose up -d postgres redis mailpit

backend:   ## Run the backend locally
	cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

frontend:  ## Run the frontend locally
	cd frontend && pnpm dev

test:      ## Unit + integration tests
	cd backend && ./mvnw clean verify

migrate:   ## Apply Flyway migrations
	cd backend && ./mvnw flyway:migrate

psql:      ## Open a database shell
	docker compose exec postgres psql -U sprintiq -d sprintiq

clean:     ## Remove containers and volumes (destroys local data)
	docker compose down -v
