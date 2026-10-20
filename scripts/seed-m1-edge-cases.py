#!/usr/bin/env python3
"""Seeds the M1 edge-case demo: the hard situations from docs/edge-case-testing-m1.md,
built as real projects so the story-point and velocity panels show them in the product.

Three projects, each one situation the velocity model finds hard, and a backlog of
stories the story-point model finds hard:

  M1 Edge · New team      no closed sprints, and a domain the model never saw
  M1 Edge · Erratic team  closed sprints of 5, 60, 3, 58 and 4 points
  M1 Edge · Steady team   five steady sprints, then a normal sprint and a holiday
                          sprint with most of the team away; the backlog holds an
                          epic-sized story, a Sinhala title, a vague one-liner and a
                          typo fix

Everything goes through the API as a signed-in user, for the reason seed-lifecycle.py
gives: a fixture written into the database can express states the product cannot reach,
and the models would then be demonstrated on data production never produces. The
malformed-input cases (NaN, infinity, negative counts) are deliberately absent — the
product cannot send them, which is the point; the tests cover them.

Usage, against the local stack:

  python scripts/seed-m1-edge-cases.py
  python scripts/seed-m1-edge-cases.py --check
  python scripts/seed-m1-edge-cases.py --ai-url http://host.docker.internal:8000

--check     asks both models about every demo sprint and story afterwards and prints the
            answers, so you see what the panel will see before you are in front of it.
            Those answers are cached for six hours, so the panel then shows "cached".
--ai-url    points every model that still targets http://localhost:8000 at this address
            instead. Needed when the backend runs inside docker compose and the model
            service runs on the host: inside the container, localhost is the container.

Settings come from the environment, so the same script works against Render later:
  SPRINTIQ_API       default http://localhost:8080/api/v1
  SPRINTIQ_EMAIL     default the account DemoDataSeeder creates on a local stack
  SPRINTIQ_PASSWORD  default that account's seed password
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = os.environ.get("SPRINTIQ_API", "http://localhost:8080/api/v1").rstrip("/")
# DemoDataSeeder.DEMO_EMAIL / DEMO_PASSWORD: a local, seeded, never-real account.
EMAIL = os.environ.get("SPRINTIQ_EMAIL", "demo@sprintiq.test")
PASSWORD = os.environ.get("SPRINTIQ_PASSWORD", "Str0ng!Passw0rd#2026")

TOKEN = None
LAST_STATUS = None
LAST_DETAIL = ""
FAILURES = []


def fail(message):
    FAILURES.append(message)
    print(f"  !! {message}", file=sys.stderr)


def call(method, path, body=None, quiet=False):
    global LAST_STATUS, LAST_DETAIL
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            LAST_STATUS = response.status
            raw = response.read().decode()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as error:
        LAST_STATUS = error.code
        LAST_DETAIL = error.read().decode()[:200]
        if not quiet:
            fail(f"{method} {path} -> {error.code} {LAST_DETAIL}")
        return None
    except urllib.error.URLError as error:
        sys.exit(f"Cannot reach {BASE}: {error.reason}. Is the backend running?")


MONDAY = date.today() - timedelta(days=date.today().weekday())


def d(offset):
    return (MONDAY + timedelta(days=offset)).isoformat()


# Sprints are two weeks and back to back. History ends the Sunday before this week.
def window(index):
    """Start and end of sprint ``index``, where 0 is the one starting this Monday."""
    return d(index * 14), d(index * 14 + 13)


# ------------------------------------------------------------------ the people

def team_of_four():
    """The signed-in owner and up to three colleagues already in the organisation.

    A local stack seeds three (Nadia, Rui, Amara). Capacity planning needs real members:
    the backend rejects an entry for anyone who is not an active member.
    """
    found = call("GET", "/users?size=50") or {}
    users = [u for u in (found.get("content") or []) if u.get("id")]
    owner = next((u for u in users if (u.get("email") or "").lower() == EMAIL.lower()), None)
    others = [u for u in users if u is not owner]
    people = ([owner] if owner else []) + others
    return [u["id"] for u in people[:4]]


# ------------------------------------------------------------------- projects

def create_project(name, key, **fields):
    """Creates the project, taking the next free key if a previous run holds this one.

    Deleting a project is a soft delete that keeps its key, so a second run of this
    script would otherwise collide with the first for ever.
    """
    ws = (call("GET", "/workspaces") or [{}])[0]
    for suffix in ["", *map(str, range(2, 10))]:
        candidate = (key + suffix)[:10]
        project = call("POST", "/projects", {
            "workspaceId": ws.get("id"), "name": name if not suffix else f"{name} ({suffix})",
            "projectKey": candidate, "currency": "USD", "defaultSprintDays": 14,
            "startDate": d(-70), "targetDate": d(56), **fields}, quiet=True)
        if project:
            print(f"\nproject {candidate} - {project['name']}")
            return project["id"]
        if LAST_STATUS != 409:
            fail(f"POST /projects ({candidate}) -> {LAST_STATUS} {LAST_DETAIL}")
            return None
    fail(f"no free project key left for {key}")
    return None


class Project:
    """The handful of calls a demo project needs, bound to one project and team."""

    def __init__(self, pid, people):
        self.pid = pid
        self.people = people
        self.labels = {}
        for uid in people:
            call("POST", f"/projects/{pid}/members",
                 {"userId": uid, "projectRole": "DEVELOPER", "allocationPct": 100}, quiet=True)

    def label(self, name, colour):
        if name not in self.labels:
            made = call("POST", "/labels", {"name": name, "color": colour, "projectId": self.pid})
            if made:
                self.labels[name] = made["id"]
        return self.labels.get(name)

    def story(self, title, points=None, description=None, criteria=None, stype="STORY",
              priority="MEDIUM", labels=(), tasks=()):
        made = call("POST", "/stories", {
            "projectId": self.pid, "title": title, "description": description,
            "acceptanceCriteria": criteria, "type": stype, "priority": priority,
            "storyPoints": points})
        if not made:
            return None
        chosen = [self.labels[name] for name in labels if name in self.labels]
        if chosen:
            call("POST", f"/stories/{made['id']}/labels", {"labelIds": chosen})
        for task_title, minutes in tasks:
            call("POST", "/tasks", {"projectId": self.pid, "storyId": made["id"],
                                    "title": task_title, "estimateMinutes": minutes})
        return made["id"]

    def sprint(self, name, index, goal):
        start, end = window(index)
        made = call("POST", "/sprints", {"projectId": self.pid, "name": name, "goal": goal,
                                         "startDate": start, "endDate": end})
        return made["id"] if made else None

    def plan_capacity(self, sprint_id, absence_days):
        """Every member at 80 hours, with ``absence_days`` each recorded as leave."""
        call("PUT", f"/sprints/{sprint_id}/capacity", {"entries": [
            {"userId": uid, "availableHours": 80, "absenceDays": absence_days,
             "allocationPct": 100} for uid in self.people]})

    def closed_sprint(self, index, points):
        """A finished sprint whose completed points add up to ``sum(points)``.

        Every story is walked through the states a person would use, so the sprint's
        completed total — the velocity the model reads — is computed by the product,
        not asserted by the fixture.
        """
        sprint_id = self.sprint(f"Sprint {index + 6}", index, "Ship what was planned")
        stories = [self.story(f"Sprint {index + 6} · item {n + 1}", value)
                   for n, value in enumerate(points)]
        stories = [s for s in stories if s]
        if not sprint_id or not stories:
            return
        call("POST", f"/sprints/{sprint_id}/items", {"storyIds": stories})
        call("POST", f"/sprints/{sprint_id}/start", {})
        for story_id in stories:
            for status in ("READY", "IN_PROGRESS", "IN_REVIEW", "DONE"):
                call("PATCH", f"/stories/{story_id}/status", {"status": status}, quiet=True)
        call("POST", f"/sprints/{sprint_id}/complete",
             {"spilloverStrategy": "BACKLOG", "retrospectiveSummary": "Closed by the demo seed."})
        print(f"  closed Sprint {index + 6}: {sum(points)} points")

    def planned_sprint(self, name, index, goal, points, absence_days):
        sprint_id = self.sprint(name, index, goal)
        stories = [self.story(f"{name} · item {n + 1}", value) for n, value in enumerate(points)]
        stories = [s for s in stories if s]
        if sprint_id and stories:
            call("POST", f"/sprints/{sprint_id}/items", {"storyIds": stories})
        if sprint_id:
            self.plan_capacity(sprint_id, absence_days)
            print(f"  planned {name}: {sum(points)} points committed, "
                  f"{absence_days} leave day(s) per person")
        return sprint_id


def add_programme_story(project):
    """SP-35: far past the training data — 20 criteria, 12 tasks and 4 labels make 36
    components against the 7 the model was trained on. Expected: 40 on the deck,
    extrapolated, HIGH, treat it as an epic."""
    criteria = "\n".join(f"- {line}" for line in (
        "Cart rebuilt on the new platform", "Promotions engine migrated", "Tax per region",
        "Every payment method re-certified", "Fraud checks at parity", "Saved cards moved",
        "Guest checkout kept", "Order history readable", "Refund flows unchanged",
        "Gift cards honoured", "Invoices regenerated", "Accessibility audit passed",
        "Load test at Black Friday peak", "Rollback per region", "Analytics events at parity",
        "Customer emails rewritten", "Support tooling updated", "Old checkout switched off",
        "Data retention rules applied", "Every region signed off"))
    return project.story(
        "Replatform checkout across every region",
        description="Move the whole checkout, its payments, promotions and tax, onto the "
                    "new commerce platform, region by region, with no lost orders.",
        criteria=criteria, labels=("security", "integration", "compliance", "performance"),
        tasks=[(f"Workstream {n}", 480) for n in range(1, 13)])


# Five closed sprints in the past, indexes -5..-1, so history ends last week. Each list is
# the points of the stories that sprint finished; the velocity is their sum.
STEADY = [[13, 8, 8, 3], [13, 13, 8, 1], [13, 8, 8, 3, 1], [13, 13, 8], [13, 13, 5, 5]]
ERRATIC = [[5], [13, 13, 13, 13, 8], [3], [13, 13, 13, 8, 8, 3], [3, 1]]


def seed():
    people = team_of_four()
    if not people:
        sys.exit("No users found in this organisation.")
    print(f"team of {len(people)}")

    demo = {"sprints": [], "stories": []}

    # ---------------------------------------------------------------- new team
    # V-01 and V-21: nothing delivered yet, and a domain the model has never seen.
    # Expected on screen: "Thin evidence", and an explanation that this is a starting
    # point from typical values, scored by the universal model.
    pid = create_project("M1 Edge · New team", "EDGNEW",
                         description="Edge case: a team with no closed sprints yet.",
                         domain="manufacturing", techStack=["springboot"], teamSize=4,
                         devExperienceLevel="mid", budgetAmount=60000)
    if pid:
        new = Project(pid, people)
        demo["sprints"].append(("New team - first sprint", new.planned_sprint(
            "Sprint 1", 0, "First sprint for a brand-new team", [8, 5, 5], 0)))
        demo["stories"].append(("Unknown domain", new.story(
            "Ingest machine telemetry from the factory floor",
            description="Collect sensor readings from each production line every minute.",
            criteria="- Readings arrive within a minute\n- A silent sensor raises an alert")))

    # ------------------------------------------------------------ erratic team
    # V-07: the averages look ordinary, the history does not. Expected on screen: a range
    # of roughly 0 to 48 and an explanation that this team varies by about 30 points.
    pid = create_project("M1 Edge · Erratic team", "EDGERR",
                         description="Edge case: velocity swinging between 3 and 60.",
                         domain="ecommerce", techStack=["react"], teamSize=4,
                         devExperienceLevel="mid", budgetAmount=90000,
                         teamVelocityAvg=26, recentCompletionRate=0.6)
    if pid:
        erratic = Project(pid, people)
        for offset, points in enumerate(ERRATIC):
            erratic.closed_sprint(offset - 5, points)
        demo["sprints"].append(("Erratic team - next sprint", erratic.planned_sprint(
            "Next sprint", 0, "Whatever the team can manage", [8, 5, 5, 3], 0)))

    # ------------------------------------------------- steady team, holiday sprint
    # V-08 against V-10: the same team twice. Normal sprint: the model's own ±3.3.
    # Holiday sprint: 12 of 14 days away each, about 14% availability, below the 55% the
    # model was trained on — expected scaled down, with the explanation saying so.
    pid = create_project("M1 Edge · Steady team", "EDGHOL",
                         description="Edge case: a steady team, then a holiday sprint.",
                         # Mid rather than senior, so the in-range stories here size
                         # as a typical team's. (Before the too-big guard, a senior
                         # team's epic came back as 8; it is 13 either way now.)
                         domain="finance", techStack=["springboot"], teamSize=4,
                         devExperienceLevel="mid", budgetAmount=120000,
                         teamVelocityAvg=34, recentCompletionRate=0.85)
    if pid:
        steady = Project(pid, people)
        for name, colour in [("security", "#dc2626"), ("integration", "#2563eb"),
                             ("compliance", "#ea580c"), ("performance", "#65a30d")]:
            steady.label(name, colour)
        for offset, points in enumerate(STEADY):
            steady.closed_sprint(offset - 5, points)
        demo["sprints"].append(("Steady team - normal sprint", steady.planned_sprint(
            "Normal sprint", 0, "Business as usual", [13, 8, 8, 3], 1)))
        demo["sprints"].append(("Steady team - holiday sprint", steady.planned_sprint(
            "Holiday sprint", 1, "Most of the team is on leave", [13, 8, 8, 3], 12)))

        # SP-30/33: bigger than anything in the training data. 8 criteria + 6 tasks +
        # 4 labels is 18 components, past the 7 the model was trained on. Expected: 13,
        # read as "13 or more", HIGH, split it.
        epic = steady.story(
            "Migrate the billing platform to a multi-region datastore",
            description="Zero downtime, dual-write, backfill, reconciliation and a "
                        "compliance audit across every region we bill in.",
            criteria="- Dual-write to both stores\n- Backfill five years of invoices\n"
                     "- Reconciliation job reports zero drift\n- Cut-over with no downtime\n"
                     "- Rollback plan rehearsed\n- Audit trail kept per region\n"
                     "- Latency within 50ms of today\n- Old store decommissioned",
            labels=("security", "integration", "compliance", "performance"),
            tasks=[("Dual-write adapter", 600), ("Backfill job", 480),
                   ("Reconciliation report", 360), ("Cut-over runbook", 240),
                   ("Rollback rehearsal", 240), ("Decommission plan", 180)])
        # Two stories it waits on, so external_apis reads as integration work too.
        for title in ("Provision the second region", "Agree the data-residency rules"):
            other = steady.story(title, 5)
            if epic and other:
                call("POST", f"/stories/{epic}/dependencies",
                     {"targetType": "STORY", "targetId": other,
                      "dependencyType": "BLOCKED_BY"}, quiet=True)
        demo["stories"].append(("Epic-sized story", epic))
        demo["stories"].append(("Programme-sized story", add_programme_story(steady)))

        # SP-22: wording in another language. Expected: the explanation says the model
        # did not recognise any of it. ("Fix the error on the payment page".)
        demo["stories"].append(("Sinhala title", steady.story(
            "ගෙවීම් පිටුවේ දෝෂය නිවැරදි කරන්න", stype="BUG")))
        # SP-03: next to nothing to read.
        demo["stories"].append(("Vague one-liner", steady.story("Misc improvements")))
        # SP-15: the small end, for contrast with the epic.
        demo["stories"].append(("Tiny fix", steady.story(
            "Fix typo in footer", description="The footer says 'Copywrite'.", stype="BUG")))

    return demo


# ------------------------------------------------------------- ai-service target

def point_models_at(base_url):
    """Repoints every model still aimed at the host's own localhost:8000."""
    base_url = base_url.rstrip("/")
    for model in call("GET", "/ai/models") or []:
        url = model.get("endpointUrl") or ""
        if url.startswith("http://localhost:8000"):
            target = base_url + url[len("http://localhost:8000"):]
            if call("PATCH", f"/ai/models/{model['code']}", {"endpointUrl": target}):
                print(f"  {model['code']}: {target}")


# ------------------------------------------------------------------- the check

def check(demo):
    """What the panels will show, printed, so nothing about the demo is a surprise."""
    print("\n--- what the models say ---")
    for name, sprint_id in demo["sprints"]:
        if not sprint_id:
            continue
        result = call("POST", "/ai/estimation/velocity", {"sprintId": sprint_id}) or {}
        p = result.get("prediction") or {}
        print(f"\n{name}: {result.get('predictedVelocity')} points "
              f"(likely {p.get('lowerBound')} to {p.get('upperBound')}), "
              f"confidence {p.get('confidence')}, verdict {result.get('commitmentVerdict')}")
        print(f"  {p.get('explanation')}")
    for name, story_id in demo["stories"]:
        if not story_id:
            continue
        result = call("POST", "/ai/estimation/story-points", {"storyId": story_id}) or {}
        p = result.get("prediction") or {}
        explanation = p.get("explanation") or ""
        print(f"\n{name}: {result.get('suggestedPoints')} points "
              f"(likely {p.get('lowerBound')} to {p.get('upperBound')}), "
              f"confidence {p.get('confidence')}")
        print(f"  {explanation}")
        if "against a project baseline" in explanation:
            print("  !! That is the MOCK model, not yours. Set AI_PROVIDER=http for the "
                  "backend and start the model service.")


def main():
    global TOKEN
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true",
                        help="ask both models about every demo item and print the answers")
    parser.add_argument("--ai-url", help="repoint models from http://localhost:8000 to this")
    args = parser.parse_args()

    # Sinhala in a Windows console would otherwise stop the run at the first print.
    sys.stdout.reconfigure(errors="replace")

    login = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})
    if not login:
        sys.exit(f"Could not sign in as {EMAIL}. Set SPRINTIQ_EMAIL and SPRINTIQ_PASSWORD.")
    TOKEN = login["accessToken"]
    print(f"signed in as {EMAIL} at {BASE}")

    if args.ai_url:
        print(f"\npointing models at {args.ai_url}")
        point_models_at(args.ai_url)

    demo = seed()
    if args.check:
        check(demo)

    print("\nDone. In SprintIQ: open a project above, then")
    print("  velocity  - Sprints, pick the sprint, AI assessment, Predict velocity")
    print("  estimates - pick a story, Estimate with AI, Suggest")
    if FAILURES:
        print(f"\n{len(FAILURES)} call(s) failed:")
        for failure in FAILURES[:10]:
            print("  " + failure)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
