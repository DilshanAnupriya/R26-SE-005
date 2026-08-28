#!/usr/bin/env python3
"""Seeds one project through a complete delivery cycle, over the real API.

Everything goes through HTTP as a signed-in user, not through SQL. A fixture written
straight into the database can express states the application cannot reach — a sprint
completed without its stories being done, points that do not add up — and the models
would then read a shape production never produces. Slower, but every row here is one
the product could have made.
"""
import json, sys, urllib.request, urllib.error
from datetime import date, timedelta

BASE = "http://localhost:8080/api/v1"
EMAIL, PASSWORD = "dilshan@acme.test", "Str0ng!Passw0rd#2026"
MEMBER_ROLE = "7921731b-2784-45a7-b1f2-2bf528818239"
TEAM_PASSWORD = "Str0ng!Passw0rd#2026"

TOKEN = None
FAILURES = []


def call(method, path, body=None, quiet=False, token=None):
    url = path if path.startswith("http") else BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    bearer = token if token is not None else TOKEN
    if bearer:
        req.add_header("Authorization", "Bearer " + bearer)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:200]
        if not quiet:
            FAILURES.append(f"{method} {path} -> {e.code} {detail}")
            print(f"  !! {method} {path} -> {e.code} {detail}", file=sys.stderr)
        return None


MONDAY = date.today() - timedelta(days=date.today().weekday())
def d(off): return (MONDAY + timedelta(days=off)).isoformat()


# ----------------------------------------------------------------- the team
# Four people with different rates and seniorities. The cost model reads a blended
# hourly rate and a seniority mix; a team of identical clones makes every allocation
# suggestion arbitrary and every cost forecast a multiplication.
TEAM = [
    ("maya.reeves@acme.test",  "Maya Reeves",  "SENIOR", 95, 40, 1.0),
    ("tom.okafor@acme.test",   "Tom Okafor",   "MID",    70, 40, 0.9),
    ("ines.duarte@acme.test",  "Ines Duarte",  "SENIOR", 92, 32, 0.8),
    ("kabir.shah@acme.test",   "Kabir Shah",   "JUNIOR", 45, 40, 1.0),
]


def ensure_team():
    people = []
    for email, name, seniority, rate, capacity, availability in TEAM:
        invite = call("POST", "/organizations/current/invitations",
                      {"email": email, "roleId": MEMBER_ROLE}, quiet=True)
        if invite and invite.get("inviteToken"):
            call("POST", "/organizations/invitations/accept",
                 {"token": invite["inviteToken"], "fullName": name,
                  "password": TEAM_PASSWORD}, quiet=True)
        found = call("GET", f"/users?search={email}&size=5") or {}
        match = next((u for u in (found.get("content") or []) if u.get("email") == email), None)
        if not match:
            print(f"  !! could not resolve {email}", file=sys.stderr)
            continue
        uid = match["id"]
        call("PUT", f"/users/{uid}/developer-profile", {
            "seniority": seniority, "hourlyRate": rate,
            "weeklyCapacityHours": capacity, "availabilityRate": availability}, quiet=True)
        people.append((uid, name))
    return people


def purge_previous_runs():
    """Removes soft-deleted NIM projects so the key is free. See the note at the call site."""
    import subprocess
    # Scoped to this one organisation and this one key. Both halves matter: another
    # tenant may legitimately own a project keyed NIM, and nothing else in this org
    # should ever be touched by a fixture.
    sql = ("DELETE FROM projects p USING organizations o "
           "WHERE o.id = p.organization_id AND o.slug = 'acme-robotics-3' "
           "AND p.project_key = 'NIM';")
    result = subprocess.run(
        ["docker", "exec", "sprintiq-postgres", "psql", "-U", "sprintiq", "-d", "sprintiq",
         "-t", "-A", "-c", sql],
        capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        print(f"  !! purge failed: {result.stderr.strip()[:200]}", file=sys.stderr)
    elif result.stdout.strip() not in ("", "DELETE 0"):
        print(f"purged previous run: {result.stdout.strip()}")


def main():
    global TOKEN
    TOKEN = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})["accessToken"]
    print(f"signed in as {EMAIL}")

    people = ensure_team()
    print(f"team: {', '.join(n for _, n in people)}")
    ids = [p[0] for p in people]
    if len(ids) < 4:
        print("team incomplete — continuing with what resolved", file=sys.stderr)
    while len(ids) < 4:
        ids.append(ids[0] if ids else None)

    ws = (call("GET", "/workspaces") or [{}])[0]

    # Re-runnable on purpose. A seed you can only run against a clean database is one
    # you stop running, and then it rots.
    #
    # This is the one step that goes around the API, and it needs saying why: DELETE
    # on a project is a soft delete into the recycle bin, and there is no purge
    # endpoint — so the project key stays reserved and a second run of this script
    # would collide with the first for ever. The purge is scoped to soft-deleted rows
    # with this exact key, and the schema cascades to sprints, stories and the rest.
    purge_previous_runs()

    project = call("POST", "/projects", {
        "workspaceId": ws["id"], "name": "Nimbus Payments", "projectKey": "NIM",
        "description": "Card acquiring and settlement for mid-market merchants.",
        "startDate": d(-84), "targetDate": d(56),
        "budgetAmount": 180000, "currency": "USD",
        "domain": "finance", "techStack": ["springboot"],
        "teamSize": 4, "devExperienceLevel": "senior", "defaultSprintDays": 14,
        # Starting figures only. Once the sprints below complete, the measured values
        # take over and the difference between the two is the thing worth looking at.
        "teamVelocityAvg": 20, "recentCompletionRate": 0.8,
    })
    if not project:
        return 1
    pid = project["id"]
    print(f"project {project['projectKey']} · {pid}")

    for uid in ids:
        call("POST", f"/projects/{pid}/members",
             {"userId": uid, "projectRole": "DEVELOPER", "allocationPct": 100}, quiet=True)

    labels = {}
    for name, colour in [("security", "#dc2626"), ("integration", "#2563eb"),
                         ("ui", "#7c3aed"), ("compliance", "#ea580c"),
                         ("api", "#0891b2"), ("performance", "#65a30d")]:
        made = call("POST", "/labels", {"name": name, "color": colour, "projectId": pid})
        if made:
            labels[name] = made["id"]

    epics = {}
    for key, title, desc, s, t in [
        ("acquiring", "Card acquiring", "Take a payment end to end.", -84, 0),
        ("settlement", "Settlement and payouts", "Move merchant money on schedule.", -42, 28),
        ("portal", "Merchant portal", "Self-serve reporting and disputes.", -14, 56),
    ]:
        e = call("POST", "/epics", {"projectId": pid, "title": title, "description": desc,
                                    "startDate": d(s), "targetDate": d(t)})
        if e:
            epics[key] = e["id"]
    print(f"{len(labels)} labels, {len(epics)} epics")

    def story(epic, title, description, criteria, points, stype="STORY",
              priority="MEDIUM", tags=(), owner=0):
        body = {"projectId": pid, "epicId": epics.get(epic), "title": title,
                "description": description, "acceptanceCriteria": criteria,
                "type": stype, "priority": priority, "storyPoints": points,
                "assigneeId": ids[owner % len(ids)]}
        s = call("POST", "/stories", body)
        if not s:
            return None
        chosen = [labels[t] for t in tags if t in labels]
        if chosen:
            call("POST", f"/stories/{s['id']}/labels", {"labelIds": chosen})
        return s["id"]

    def task(story_id, title, minutes, owner=0, status=None):
        t = call("POST", "/tasks", {"projectId": pid, "storyId": story_id, "title": title,
                                    "estimateMinutes": minutes,
                                    "assigneeId": ids[owner % len(ids)]})
        if not t:
            return None
        if status:
            call("PATCH", f"/tasks/{t['id']}/status", {"status": status}, quiet=True)
        return t["id"]

    def log(task_id, minutes, day, overtime=False):
        if task_id:
            call("POST", f"/tasks/{task_id}/time-entries",
                 {"minutes": minutes, "loggedOn": d(day),
                  "description": "Implementation", "overtime": overtime}, quiet=True)

    def say(entity_type, entity_id, text):
        call("POST", "/comments", {"entityType": entity_type, "entityId": entity_id,
                                   "body": text}, quiet=True)

    def finish(story_id):
        # Walked through the states a person would use, so the transition history the
        # board and the collaboration model read is a real one.
        for status in ("READY", "IN_PROGRESS", "IN_REVIEW", "DONE"):
            call("PATCH", f"/stories/{story_id}/status", {"status": status}, quiet=True)

    def sprint(name, goal, start, end, capacity_points, capacity_hours, budget):
        return call("POST", "/sprints", {
            "projectId": pid, "name": name, "goal": goal,
            "startDate": d(start), "endDate": d(end),
            "capacityPoints": capacity_points, "capacityHours": capacity_hours,
            "budgetAmount": budget})

    def run_sprint(sp, story_ids, done_ids, retro, spillover="BACKLOG"):
        """Adds the work, starts it, finishes what got finished, closes the sprint."""
        if not sp:
            return
        call("POST", f"/sprints/{sp['id']}/items", {"storyIds": story_ids})
        call("POST", f"/sprints/{sp['id']}/start", {})
        for sid in done_ids:
            finish(sid)
        call("POST", f"/sprints/{sp['id']}/complete",
             {"spilloverStrategy": spillover, "retrospectiveSummary": retro})

    # ------------------------------------------------------- sprint 1 (done)
    tokenise = story("acquiring", "Tokenise card details at capture",
                     "Card numbers must never reach our own storage. Capture goes "
                     "straight to the vault and we keep a token.",
                     "- PAN is never written to our database\n"
                     "- A token round-trips through authorisation\n"
                     "- The vault outage path is covered", 8,
                     priority="HIGH", tags=("security", "compliance"), owner=0)
    auth_api = story("acquiring", "Authorisation API",
                     "Expose POST /payments so a merchant can authorise a card.",
                     "- Returns an authorisation code on success\n"
                     "- Declines carry the issuer reason\n"
                     "- Idempotency key prevents a double charge", 8,
                     priority="HIGH", tags=("api", "integration"), owner=1)
    capture = story("acquiring", "Capture and void",
                    "Settle or release an authorisation.",
                    "- Partial capture supported\n- Void before cut-off", 5,
                    tags=("api",), owner=2)

    for sid, parts in [
        (tokenise, [("Vault client", 240, 0), ("Token exchange endpoint", 300, 0),
                    ("Failure path tests", 180, 3)]),
        (auth_api, [("Request validation", 180, 1), ("Issuer adapter", 360, 1),
                    ("Idempotency store", 240, 2)]),
        (capture, [("Capture handler", 240, 2), ("Void handler", 180, 2)]),
    ]:
        for title, minutes, owner in parts:
            tid = task(sid, title, minutes, owner=owner, status="DONE")
            log(tid, minutes, -70)

    s1 = sprint("Sprint 1", "Take a card payment end to end", -84, -70, 24, 260, 2500)
    run_sprint(s1, [x for x in (tokenise, auth_api, capture) if x],
               [x for x in (tokenise, auth_api, capture) if x],
               "Everything landed. The vault integration was simpler than feared.")

    # ------------------------------------------------------- sprint 2 (done, spillover)
    threeds = story("acquiring", "3-D Secure step-up",
                    "Challenge the cardholder when the issuer asks for it.",
                    "- Frictionless flow when the issuer allows it\n"
                    "- Challenge window renders on mobile\n"
                    "- Liability shift recorded on the payment", 13,
                    priority="HIGH", tags=("security", "ui", "integration"), owner=0)
    refunds = story("acquiring", "Refunds", "Full and partial refunds against a capture.",
                    "- Refund cannot exceed the captured amount\n"
                    "- Refund shows on the merchant statement", 5,
                    tags=("api",), owner=3)
    ledger = story("settlement", "Settlement ledger",
                   "Double-entry ledger behind every movement of money.",
                   "- Every entry balances\n- Ledger reconciles to the acquirer file", 8,
                   priority="HIGH", tags=("compliance",), owner=1)

    for sid, parts, done in [
        (threeds, [("ACS redirect handling", 420, 0), ("Challenge UI", 360, 3),
                   ("Liability shift mapping", 240, 0)], False),
        (refunds, [("Refund endpoint", 240, 3), ("Statement line", 120, 3)], True),
        (ledger, [("Entry model", 300, 1), ("Reconciliation job", 420, 1)], True),
    ]:
        for title, minutes, owner in parts:
            tid = task(sid, title, minutes, owner=owner, status="DONE" if done else "IN_PROGRESS")
            log(tid, minutes if done else minutes // 2, -56)

    s2 = sprint("Sprint 2", "3DS and the money trail", -70, -56, 26, 260, 2000)
    run_sprint(s2, [x for x in (threeds, refunds, ledger) if x],
               [x for x in (refunds, ledger) if x],
               "3DS slipped — the ACS sandbox was down for four days.")

    # ------------------------------------------------------- sprint 3 (done)
    payouts = story("settlement", "Daily merchant payouts",
                    "Pay merchants on a schedule they can rely on.",
                    "- Payout file generated at the cut-off\n"
                    "- Failed payouts retry once\n- Merchant sees the payout reference", 8,
                    priority="HIGH", tags=("integration",), owner=1)
    fees = story("settlement", "Fee calculation",
                 "Interchange, scheme and our own margin, itemised.",
                 "- Fees itemised per transaction\n- Rounding matches the scheme rules", 5,
                 tags=("compliance",), owner=2)

    for sid, parts in [
        (payouts, [("Payout file writer", 360, 1), ("Retry policy", 180, 1),
                   ("Reference on the portal", 120, 3)]),
        (fees, [("Fee engine", 300, 2), ("Rounding tests", 120, 2)]),
    ]:
        for title, minutes, owner in parts:
            tid = task(sid, title, minutes, owner=owner, status="DONE")
            log(tid, minutes, -42)

    s3 = sprint("Sprint 3", "Merchants get paid", -56, -42, 22, 240, 2700)
    run_sprint(s3, [x for x in (payouts, fees, threeds) if x],
               [x for x in (payouts, fees, threeds) if x],
               "Caught up on 3DS and shipped payouts.")

    # ------------------------------------------------------- sprint 4 (running)
    # The interesting one. Everything the risk and cost models look for is here on
    # purpose: work over its estimate, a blocked story, a silent task, an unanswered
    # question, and one story whose task breakdown has outgrown its points.
    disputes = story("portal", "Dispute and chargeback handling",
                     "Merchants respond to a chargeback without emailing support.",
                     "- Evidence upload before the deadline\n"
                     "- Deadline shown in the merchant's timezone\n"
                     "- Scheme response recorded against the payment", 8,
                     priority="HIGH", tags=("ui", "compliance"), owner=0)
    reporting = story("portal", "Settlement reporting",
                      "Downloadable settlement report per payout.",
                      "- CSV matches the payout to the penny\n- 24 months retained", 5,
                      tags=("ui",), owner=2)
    webhooks = story("portal", "Merchant webhooks",
                     "Tell a merchant's system when a payment changes state.",
                     "- At-least-once delivery with signed payloads\n"
                     "- Replay from the portal\n- Backoff on repeated failure", 8,
                     priority="HIGH", tags=("integration", "api"), owner=1)
    perf = story("acquiring", "Authorisation latency under load",
                 "p99 authorisation must stay under 400ms at 300 tps.",
                 "- Load test in CI\n- p99 under 400ms at 300 tps", 5,
                 stype="TECH_DEBT", tags=("performance",), owner=1)

    # Disputes is deliberately over budget: 8 points is 8 hours at the project's
    # default, and the breakdown below comes to 20. That is the story-budget rule
    # and the over-budget risk adjustment, both visible on one story.
    for title, minutes, owner, status in [
        ("Evidence upload", 300, 0, "DONE"),
        ("Deadline calculator", 240, 0, "IN_PROGRESS"),
        ("Scheme response webhook", 360, 3, "TODO"),
        ("Merchant notification email", 300, 3, "TODO"),
    ]:
        tid = task(disputes, title, minutes, owner=owner, status=status)
        if status == "DONE":
            log(tid, 330, -5, overtime=True)
        elif status == "IN_PROGRESS":
            log(tid, 180, -3)

    for title, minutes, owner, status in [
        ("Report query", 240, 2, "DONE"), ("CSV writer", 180, 2, "IN_PROGRESS")]:
        tid = task(reporting, title, minutes, owner=owner, status=status)
        log(tid, minutes if status == "DONE" else 90, -4)

    for title, minutes, owner, status in [
        ("Delivery worker", 360, 1, "IN_PROGRESS"), ("Signing", 180, 1, "TODO"),
        ("Replay endpoint", 240, 1, "TODO")]:
        tid = task(webhooks, title, minutes, owner=owner, status=status)
        if status == "IN_PROGRESS":
            log(tid, 300, -2, overtime=True)

    task(perf, "Load harness", 300, owner=1, status="TODO")

    s4 = sprint("Sprint 4", "Merchants can self-serve", -7, 7, 26, 240, 2600)
    if s4:
        call("POST", f"/sprints/{s4['id']}/items",
             {"storyIds": [x for x in (disputes, reporting, webhooks, perf) if x]})
        call("POST", f"/sprints/{s4['id']}/start", {})
        for sid in (disputes, reporting, webhooks):
            call("PATCH", f"/stories/{sid}/status", {"status": "IN_PROGRESS"}, quiet=True)
        # One blocked item, because a board where nothing is ever stuck is not a board
        # anybody recognises — and the collaboration model reads blocked time directly.
        call("POST", f"/stories/{perf}/block",
             {"reason": "Waiting on a load-test environment"}, quiet=True)

    # ------------------------------------------------------- conversation
    # The collaboration model reads comment threads and whether questions get answers.
    # A backlog with no conversation on it scores as a silent team, which is a real
    # signal — so some threads here are answered and one deliberately is not.
    say("STORY", disputes, "Which timezone do we show the deadline in — merchant or scheme?")
    say("STORY", disputes, "Merchant. The scheme deadline is UTC but nobody reads it that way.")
    say("STORY", webhooks, "Are we signing with HMAC or asymmetric keys?")
    say("STORY", reporting, "Finance want 24 months, not 12. Confirmed on Tuesday.")
    say("STORY", reporting, "Updated the criteria.")

    # A dependency, so the story-point model sees a story that waits on other work.
    if webhooks and ledger:
        call("POST", f"/stories/{webhooks}/dependencies",
             {"targetType": "STORY", "targetId": ledger,
              "dependencyType": "BLOCKED_BY"}, quiet=True)

    # ------------------------------------------------------- backlog beyond the sprint
    story("portal", "Merchant user management",
          "Invite colleagues and set what they can see.",
          "- Owner can invite\n- Read-only role available", 5, tags=("security",), owner=0)
    story("portal", "Saved report schedules",
          "Email a settlement report on a schedule.", "- Daily or monthly\n- Unsubscribe", 3,
          owner=2)
    story("settlement", "Multi-currency settlement",
          "Settle to a merchant in the currency they sell in.",
          "- FX rate captured at cut-off\n- Rate shown on the payout", 13,
          priority="HIGH", tags=("integration",), owner=1)
    # Deliberately unpointed and thin, so the estimation panel has something to size
    # and the "story with no budget" path stays reachable.
    story("portal", "Bulk export", "Merchants keep asking for one.", None, None, owner=3)
    story("acquiring", "Apple Pay", "Accept Apple Pay at checkout.", None, None,
          priority="HIGH", tags=("integration", "ui"), owner=0)

    return pid, s4


if __name__ == "__main__":
    r = main()
    if isinstance(r, int):
        sys.exit(r)
    pid, s4 = r
    print(f"\nseeded project {pid}")
    if s4:
        print(f"active sprint  {s4['id']}")
    if FAILURES:
        print(f"{len(FAILURES)} failure(s):")
        for f in FAILURES[:8]:
            print("  " + f)
