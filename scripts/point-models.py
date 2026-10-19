#!/usr/bin/env python3
"""Points each AI model at the member who hosts it, through the backend's admin API.

Each member's models are served by their own copy of ai-service (an Azure Web App
running the shared sprintiq-ai image). The backend keeps one address per model in its
ai_models table, and caches it in Redis, so an address changed straight in the database
is not seen until that cache is cleared. PATCH /ai/models/{code} updates the row and
clears the cache in one step — this script just calls it for every model whose owner
you name.

Usage (signed in as the organisation's owner, who has the ai:manage permission):

  SPRINTIQ_API=https://<backend-domain>/api/v1 \\
  SPRINTIQ_EMAIL=you@example.com SPRINTIQ_PASSWORD=... \\
  python scripts/point-models.py \\
      M1=https://<ai-dilshan-domain> \\
      M2=https://<ai-risk-domain> \\
      M4=https://<ai-risk-domain>

M1, M2 and M4 name a member's whole set of models; a full code (M2_OVER_BUDGET=...)
points one model on its own. Resource allocation (M3) is not in the table: the backend
reads it from its PYTHON_API_URL setting instead (see docs/hosting.md).

--timeout-ms raises each model's latency budget (default 30000). The backend waits for
the lower of this and its AI_TIMEOUT_MS, and the first prediction after a host restarts
can take longer than the default four seconds.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

# The path each model answers on, the same in every copy of ai-service.
PATHS = {
    "M1_STORY_POINTS": "/predict/story-points",
    "M1_VELOCITY": "/predict/velocity",
    "M2_OVER_BUDGET": "/predict/risk/over-budget",
    "M2_REQUIREMENT_CHANGE": "/predict/risk/requirement-change",
    "M2_COMMUNICATION_COLLABORATION": "/predict/risk/communication-collaboration",
    "M4_COST_INITIAL": "/predict/cost/initial",
    "M4_COST_MID": "/predict/cost/mid-sprint",
}


def call(base, method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            raw = response.read().decode()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as error:
        sys.exit(f"{method} {path} -> {error.code} {error.read().decode()[:300]}")
    except urllib.error.URLError as error:
        sys.exit(f"Cannot reach {base}: {error.reason}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("owners", nargs="+", metavar="CODE=URL",
                        help="M1, M2, M4 or a full model code, and the base URL of that member's ai-service")
    parser.add_argument("--timeout-ms", type=int, default=30000,
                        help="latency budget for each model, 100-60000 (default 30000)")
    args = parser.parse_args()

    api = os.environ.get("SPRINTIQ_API", "").rstrip("/")
    email, password = os.environ.get("SPRINTIQ_EMAIL"), os.environ.get("SPRINTIQ_PASSWORD")
    if not (api and email and password):
        sys.exit("Set SPRINTIQ_API, SPRINTIQ_EMAIL and SPRINTIQ_PASSWORD first.")

    targets = {}
    for pair in args.owners:
        prefix, _, base = pair.partition("=")
        if not base.startswith("http"):
            sys.exit(f"{pair}: expected CODE=https://...")
        codes = [c for c in PATHS if c == prefix or c.startswith(prefix + "_")]
        if not codes:
            sys.exit(f"{prefix}: no model code starts with it ({', '.join(PATHS)})")
        for code in codes:
            targets[code] = base.rstrip("/") + PATHS[code]

    token = call(api, "POST", "/auth/login", {"email": email, "password": password})["accessToken"]
    for code, url in targets.items():
        model = call(api, "PATCH", f"/ai/models/{code}",
                     {"endpointUrl": url, "latencyBudgetMs": args.timeout_ms}, token)
        print(f"{code:32s} -> {model.get('endpointUrl')}  ({model.get('latencyBudgetMs')} ms)")


if __name__ == "__main__":
    main()
