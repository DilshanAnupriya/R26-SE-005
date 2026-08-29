# 'infra' must be listed here because a directory of that name exists in the repo.
# Without it, make sees the directory, decides the target is already satisfied, and
# prints "`infra' is up to date" while starting no containers at all — a success
# message for work it never did, and one that surfaces minutes later as "connection
# to localhost:5432 refused". Every target in this file is a command rather than a
# file it produces, so all of them belong on this line whether or not a directory
# happens to shadow them today.
.PHONY: help up down logs build test backend frontend ai infra migrate psql redis clean

# The interpreter that has the model service's dependencies. A bare `python3` resolves
# to whatever PATH hands make's shell, which on a machine carrying anaconda, pyenv and a
# python.org build at once is frequently not the one uvicorn was installed into — the
# failure reads "No module named uvicorn" and sends you looking at the install rather
# than at the PATH. Pick the first candidate that can actually import it; override with
# `make ai PYTHON=/path/to/python`.
#
# Note this resolves to an absolute path rather than echoing the candidate name back.
# Emitting a bare "python3" would leave the recipe to resolve it a second time, and the
# two resolutions are not guaranteed to agree — which produces the worst version of this
# bug: a check that passes against one interpreter and a run that fails against another.
PYTHON ?= $(shell for p in python3 python "$$HOME/.pyenv/shims/python3" /opt/anaconda3/bin/python3 /usr/local/bin/python3 /opt/homebrew/bin/python3; do \
	r=$$(command -v "$$p" 2>/dev/null) || continue; \
	"$$r" -c 'import uvicorn' >/dev/null 2>&1 && { echo "$$r"; break; }; \
	done)

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

ai:        ## Run the model service locally (not in compose — every model needs it)
	@test -n "$(PYTHON)" || { \
		echo "No Python on this machine can import uvicorn."; \
		echo "  Install:  python3 -m pip install -r ai-service/requirements.txt"; \
		echo "  Or point at one:  make ai PYTHON=/opt/anaconda3/bin/python3"; \
		exit 1; }
	cd ai-service && $(PYTHON) -m uvicorn app.main:app --port 8000

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
