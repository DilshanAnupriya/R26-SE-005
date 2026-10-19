# Hosting SprintIQ

Where each part runs, and how to put it there, step by step.

```
Browser ──> Vercel (frontend, Next.js)
   │
   └──────> Azure, one Azure for Students account, resource group "sprintiq"
              │
              ├─ plan sprintiq-backend-plan (B1)
              │     └─ backend Web App (Spring Boot) ──> Neon (PostgreSQL)
              │                                     └──> Upstash (Redis)
              │
              └─ plan sprintiq-ai-plan (B1)
                    ├─ sprintiq-ai-dilshan Web App   M1 story points + velocity
                    └─ sprintiq-ai-risk Web App      M2 risk models
```

The browser talks to the backend directly (with a short-lived token), and to the
frontend's own server for sign-in (which keeps the refresh token in a secure cookie).
The backend is the only thing that talks to the database, Redis and the AI models.

**Why two plans.** Azure charges per *plan*, not per app, so the second AI app costs
nothing extra. The backend has a plan of its own because 1.75 GB shared by Java and two
Python services is too little: when memory runs out the apps restart, and the backend
would go down with them.

**One image, every model.** Both AI apps run the same `ai-service` image, which holds
every member's models. The backend's model table decides which app answers for which
model (step 8). Resource allocation (M3) and cost (M4) have no app of their own here
yet; until their owners host one, step 8 points them at the risk app, which already has
them loaded, so this costs no extra memory.

Do the steps in order: the backend needs the database, Redis and the models' addresses;
the frontend needs the backend's address; and the backend then needs the frontend's.

---

## Step 0 · Before you start

1. **Commit and push the `Latest` branch.** GitHub builds both Docker images and Vercel
   builds the frontend straight from GitHub, so anything not pushed is not hosted.
2. **Accounts:**
   - **Azure for Students with credit left** — one account holds everything. Check that
     the subscription is not **(Disabled)**: that means its credit or its 12 months are
     used up, and Azure creates nothing under it.
   - **Vercel** (sign in with GitHub), and your existing **Neon** and **Upstash**.
3. **Make a JWT secret** — a long random key the backend signs sign-in tokens with. In
   PowerShell (Windows has no `openssl` there):

   ```powershell
   $bytes = New-Object byte[] 64; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); [Convert]::ToBase64String($bytes)
   ```

   or in Git Bash, `openssl rand -base64 64 | tr -d '\n'`. Either prints one line of
   about 88 characters; copy all of it. Keep it somewhere private. Never put it, or any
   password below, in git.

---

## Step 1 · Neon (database)

1. Neon console → your project → **Connection Details**.
2. Turn **Pooled connection off** and copy the host, database name, user and password.
   The backend creates its tables on first start with Flyway, and Flyway needs a direct
   connection, not the pooled one.
3. Write the backend values down:

   | Setting | Value |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<host>/<database>?sslmode=require` |
   | `DB_USERNAME` | the user |
   | `DB_PASSWORD` | the password |
   | `DB_POOL_MAX` | `5` (Neon's free tier has few connections to spare) |

   The database can be empty. If an earlier deployment already used it, Flyway carries
   on from where it is.

---

## Step 2 · Upstash (Redis)

1. Upstash console → your Redis database → **Details**.
2. Copy the endpoint (host), the port (6379) and the password (token). Upstash needs TLS.

   | Setting | Value |
   |---|---|
   | `REDIS_HOST` | the endpoint, e.g. `xxxx.upstash.io` |
   | `REDIS_PORT` | `6379` |
   | `SPRING_DATA_REDIS_USERNAME` | `default` |
   | `SPRING_DATA_REDIS_PASSWORD` | the password |
   | `SPRING_DATA_REDIS_SSL_ENABLED` | `true` |

---

## Step 3 · Get access to the Azure account

Everything goes into one resource group on the account with the credit. Its owner gives
the others access with their own logins — never share a password (Microsoft's terms
forbid it, and nobody can tell afterwards who did what).

1. The account's owner: Azure Portal → **Resource groups** → **Create**. Subscription:
   **Azure for Students**, name `sprintiq`, region **Southeast Asia** (or whichever is
   closest to your Neon region). **Review + create**.
2. Open `sprintiq` → **Access control (IAM)** → **Add** → **Add role assignment** →
   **Privileged administrator roles** → **Contributor** → **Next** → **Select members**
   → the other members' SLIIT emails → **Review + assign**.
3. The others sign in with their own accounts. The **Subscription** box in every
   "Create" form now offers that subscription, and what they create is paid from its
   credit.

If the role assignment is refused (some university directories block it), the owner
creates the resources in steps 5 and 6 while the others follow along.

---

## Step 4 · Build the images (GitHub Actions)

Two workflows build the Docker images on GitHub's machines and publish them to GitHub's
container registry, where Azure pulls them from. Building on GitHub avoids this laptop's
network problems (Norton's HTTPS scanning, the slow connection).

| Workflow | Builds when this changes on `Latest` or `main` | Image |
|---|---|---|
| `.github/workflows/backend-image.yml` | `backend/` | `ghcr.io/<owner>/sprintiq-backend:latest` |
| `.github/workflows/ai-service-image.yml` | `ai-service/app`, `models`, `requirements.txt`, `Dockerfile` | `ghcr.io/<owner>/sprintiq-ai:latest` |

`<owner>` is the GitHub account that owns the repository, in lower case
(`dilshananupriya`).

1. Push. On GitHub → **Actions**, wait for a green tick on **Backend image** and on
   **AI service image**. The first AI build takes about 10 minutes. To build without a
   change, open the workflow and click **Run workflow**.
2. GitHub → your profile → **Packages** → **sprintiq-backend** → **Package settings** →
   **Change visibility** → **Public**. Do the same for **sprintiq-ai**. Azure can then
   pull them with no password. (To keep them private instead, make a personal access
   token with `read:packages`, and give it to Azure on the **Container** tab.)

---

## Step 5 · Host the two AI apps (plan `sprintiq-ai-plan`)

**First AI app — M1 story points and velocity:**

1. Azure Portal → **Create a resource** → **Web App**.
   - **Subscription:** the Azure for Students one. **Resource group:** `sprintiq`.
   - **Name:** `sprintiq-ai-dilshan`.
   - **Publish:** **Container**. **Operating system:** **Linux**.
   - **Region:** the same as the resource group.
   - **Linux plan:** **Create new** → `sprintiq-ai-plan`. **Pricing plan:** **Basic B1**.
   - **Zone redundancy:** Disabled.
2. **Database** tab: nothing — the AI service has no database.
3. **Container** tab: image source **Other container registries**, access type
   **Public**, registry server URL `https://ghcr.io`, image and tag
   `dilshananupriya/sprintiq-ai:latest`. Leave the startup command empty.
4. **Monitoring** tab: Application Insights **No** (it costs extra, and Log stream is
   enough). Then **Review + create** → **Create**.
5. When it is created, open the app:
   - **Settings** → **Environment variables** → **Add**: `WEBSITES_PORT` = `7860` (the
     port the service listens on), and `WEBSITES_CONTAINER_START_TIME_LIMIT` = `600`
     (loading every model on one CPU takes a while the first time). **Apply**.
   - **Settings** → **Configuration** → **General settings**: **Always on: On**,
     **HTTPS Only: On**. **Save**.
   - **Monitoring** → **Health check** → **Enable**, path `/health`. **Save**.
6. **Overview** → copy the **Default domain**. Azure adds a random ending to the name, so
   it looks like `sprintiq-ai-dilshan-abc123xyz.southeastasia-01.azurewebsites.net`.
   That whole name is `<ai-dilshan-domain>` below.
7. Check: open `https://<ai-dilshan-domain>/health`. The first time it can take two or
   three minutes; then it shows `"status":"UP"`, with `"velocity": {"loaded": true …}`.

**Second AI app — M2 risk models:** the same steps, with two differences:

- **Name:** `sprintiq-ai-risk` (or the risk member's name).
- **Linux plan:** choose the **existing** `sprintiq-ai-plan`. Do not create a new one —
  that would be a second bill.

Its default domain is `<ai-risk-domain>` below. Its `/health` shows
`"risk": {"over_budget": {"loaded": true …}, "requirement_change": {"loaded": true …}}`.

**Is the plan big enough?** Open `sprintiq-ai-plan` → **Monitoring** → **Metrics** →
**Memory Percentage**. Below about 85% is fine. If it stays higher, or the apps restart
on their own (each app's **Log stream** shows it starting again), scale the plan up to
**B2** (plan → **Scale up**, about twice the price), or delete the second app and point
both members' models at the first.

---

## Step 6 · Host the backend (plan `sprintiq-backend-plan`)

1. Azure Portal → **Create a resource** → **Web App**.
   - **Subscription:** the Azure for Students one. **Resource group:** `sprintiq`.
   - **Name:** e.g. `sprintiq-api`.
   - **Publish:** **Container**. **Operating system:** **Linux**. **Region:** the same.
   - **Linux plan:** **Create new** → `sprintiq-backend-plan`. **Pricing plan:**
     **Basic B1**. (F1 Free cannot run Spring Boot well.)
2. **Database** tab: nothing — the database is Neon.
3. **Container** tab: **Other container registries**, **Public**, `https://ghcr.io`,
   image and tag `dilshananupriya/sprintiq-backend:latest`.
4. **Monitoring** tab: Application Insights **No**. **Review + create** → **Create**.
5. **Overview** → copy the **Default domain** — that is `<backend-domain>` below.
6. **Settings** → **Environment variables** → **Advanced edit**, and paste this with
   your values in place of the `<…>`:

   ```json
   [
     { "name": "SPRING_PROFILES_ACTIVE", "value": "prod", "slotSetting": false },
     { "name": "WEBSITES_PORT", "value": "8080", "slotSetting": false },
     { "name": "DB_URL", "value": "jdbc:postgresql://<neon-host>/<database>?sslmode=require", "slotSetting": false },
     { "name": "DB_USERNAME", "value": "<neon-user>", "slotSetting": false },
     { "name": "DB_PASSWORD", "value": "<neon-password>", "slotSetting": false },
     { "name": "DB_POOL_MAX", "value": "5", "slotSetting": false },
     { "name": "REDIS_HOST", "value": "<upstash-endpoint>", "slotSetting": false },
     { "name": "REDIS_PORT", "value": "6379", "slotSetting": false },
     { "name": "SPRING_DATA_REDIS_USERNAME", "value": "default", "slotSetting": false },
     { "name": "SPRING_DATA_REDIS_PASSWORD", "value": "<upstash-password>", "slotSetting": false },
     { "name": "SPRING_DATA_REDIS_SSL_ENABLED", "value": "true", "slotSetting": false },
     { "name": "JWT_SECRET", "value": "<the secret from step 0>", "slotSetting": false },
     { "name": "AI_PROVIDER", "value": "http", "slotSetting": false },
     { "name": "AI_TIMEOUT_MS", "value": "30000", "slotSetting": false },
     { "name": "PYTHON_API_URL", "value": "https://<ai-risk-domain>/predict", "slotSetting": false },
     { "name": "ADVISOR_API_KEY", "value": "<Gemini key, or leave empty>", "slotSetting": false },
     { "name": "CORS_ALLOWED_ORIGINS", "value": "https://<your-app>.vercel.app", "slotSetting": false },
     { "name": "APP_BASE_URL", "value": "https://<your-app>.vercel.app", "slotSetting": false }
   ]
   ```

   - `AI_PROVIDER=http` makes the backend call the real models; without it, it uses
     made-up mock answers.
   - `PYTHON_API_URL` is resource allocation (M3), which reads its address from here
     rather than from the model table. It points at the risk app until M3's owner hosts
     their own; then change it to `https://<their-domain>/predict` and restart.
   - `ADVISOR_API_KEY` turns on the risk advisor and Copilot (a Gemini key from
     aistudio.google.com). Empty is fine: those two features then say they are off.
   - You do not know the Vercel address yet. Leave the placeholder and fix it in step 7.
   - Email settings (`MAIL_HOST` and so on) are optional: registration works without
     email.
7. **Settings** → **Configuration** → **General settings**: **Web sockets: On** (live
   board updates use them), **Always on: On**, **HTTPS Only: On**. **Save**.
8. **Monitoring** → **Health check** → **Enable**, path `/actuator/health/readiness`.
   **Save**.
9. **Overview** → **Restart**, then **Monitoring** → **Log stream**. The first start
   takes a minute or two while the migrations run; wait for
   `Started SprintIqApplication`.
10. Check: `https://<backend-domain>/actuator/health` shows `"status":"UP"`.

---

## Step 7 · Host the frontend (Vercel)

1. vercel.com → **Add New** → **Project** → import `R26-SE-005`.
2. **Root Directory:** `frontend`. The framework is detected as Next.js.
3. **Environment Variables:**

   | Name | Value |
   |---|---|
   | `NEXT_PUBLIC_API_URL` | `https://<backend-domain>/api/v1` |
   | `NEXT_PUBLIC_WS_URL` | `wss://<backend-domain>/ws` |
   | `BACKEND_INTERNAL_URL` | `https://<backend-domain>/api/v1` |
   | `ENABLE_EXPERIMENTAL_COREPACK` | `1` |

   The last one makes Vercel use pnpm 11, the version `package.json` asks for.
4. **Deploy**, and note the production address, e.g. `https://sprintiq.vercel.app`.
5. Back in Azure → the backend app → **Environment variables**: set
   `CORS_ALLOWED_ORIGINS` and `APP_BASE_URL` to that exact address (`https://`, no slash
   at the end). **Apply**, then **Restart**.

`NEXT_PUBLIC_*` values are built into the frontend, so after changing one on Vercel,
**redeploy** (Deployments → ⋯ → Redeploy).

---

## Step 8 · First account, and connect each member's models

1. Open the Vercel address → **Create an account**. The first account creates your
   organisation and is its owner. The password needs 12 characters or more, with upper
   and lower case letters, a number and a symbol.
2. Point each model at the app that serves it. From the repository, in PowerShell:

   ```powershell
   $env:SPRINTIQ_API = "https://<backend-domain>/api/v1"
   $env:SPRINTIQ_EMAIL = "<your email>"
   $env:SPRINTIQ_PASSWORD = "<your password>"
   ai-service\.venv\Scripts\python.exe scripts\point-models.py M1=https://<ai-dilshan-domain> M2=https://<ai-risk-domain> M4=https://<ai-risk-domain>
   ```

   (In Git Bash, put the three settings in front of the command instead:
   `SPRINTIQ_API=... SPRINTIQ_EMAIL=... SPRINTIQ_PASSWORD=... ai-service/.venv/Scripts/python.exe scripts/point-models.py ...`.)

   - `M1` is story points and velocity, `M2` the three risk models, `M4` the two cost
     models. M4 goes to the risk app until its owner hosts their own; then run the
     script again with just `M4=https://<their-domain>`.
   - It prints each model with its new address. It goes through the backend's admin
     API, not straight into the database, on purpose: the backend caches these addresses
     in Redis, and the API clears that cache. It also gives each model 30 seconds to
     answer, because the first prediction after a restart is slow.
3. Check in the app: open a story → **Estimate with AI** → **Suggest**; open a sprint →
   **Predict velocity**; open **Risk Analysis**. A number with an explanation in each
   means the whole chain works.

Optional demo data: `scripts/seed-m1-edge-cases.py` builds the M1 edge-case projects
through the same API. Run it with the same three `SPRINTIQ_*` settings (with only one
person in the organisation its team is a team of one).

---

## Updating after a change

Push. GitHub builds a new image (step 4), but Azure keeps running the old one until the
app restarts: open the app → **Overview** → **Restart**, and it pulls the new `latest`.
For the frontend, Vercel redeploys on every push by itself.

---

## Presentation-day checklist

| Service | Sleeps? | Do this 10 minutes before |
|---|---|---|
| Azure B1 (all three apps) | no, with **Always on** | open the backend's `/actuator/health` and each AI app's `/health` |
| Neon | after 5 minutes idle | nothing — it wakes in about a second |
| Upstash, Vercel | no | — |

Then run one estimate, one velocity prediction and one risk analysis yourself.

## Costs

Azure bills each **App Service plan**, not each Web App: a B1 plan is about US$13 a
month, however many apps it holds. Two plans are about **US$26 a month**, so US$100 of
student credit lasts about three and a half months.

**Stopping a Web App does not stop the charge** — the plan keeps running. To pause a
plan, turn **Always on** off in its apps, then scale the plan down to **F1 (Free)**
(plan → **Scale up**); scaling back up to B1 later keeps every setting. After the final
presentation, delete the `sprintiq` resource group to stop everything at once.

Neon, Upstash, Vercel and GitHub's container registry are free at this size.

## When something does not work

| What you see | Usually |
|---|---|
| The Subscription box says **(Disabled)** | That account's credit or its 12 months are used up. Use an account with credit (step 3). |
| An app's Log stream says the image could not be pulled, or "unauthorized" | The package is still private on GitHub (step 4.2), or the image name is not all lower case. |
| An AI app's Log stream says the container "didn't respond to HTTP pings" | `WEBSITES_PORT` is not `7860` on that app. |
| The AI apps keep restarting | The plan is out of memory: see "Is the plan big enough?" in step 5. |
| The site loads, but sign-in says the API is unreachable, or the browser shows a CORS error | `CORS_ALLOWED_ORIGINS` is not exactly the Vercel address (`https://`, no slash at the end). Fix it and restart the backend. |
| Changed a `NEXT_PUBLIC_*` value and nothing changed | Redeploy on Vercel: those values are built in. |
| Live board updates do not arrive | Web sockets are off on the backend app, or `NEXT_PUBLIC_WS_URL` is not `wss://…/ws`. |
| The backend keeps restarting | Log stream. Usually `DB_URL` (must start `jdbc:postgresql://` and end `?sslmode=require`) or Redis (`SPRING_DATA_REDIS_SSL_ENABLED=true`). |
| Estimates or risk come back "failed" or "degraded" | That model's app is down or its address is wrong. Open its `/health`, then run `point-models.py` again with the right address. |
| Answers look made up ("against a project baseline") | `AI_PROVIDER` is not `http`. |
| The risk page says no advisor API key is configured | `ADVISOR_API_KEY` is empty on the backend app. Optional; set it and restart to turn the advisor on. |
| Vercel build fails at install | `ENABLE_EXPERIMENTAL_COREPACK=1` is missing. |
| Uploaded attachments disappear after a restart | Attachments are stored inside the container. Fine for a demo; lasting storage needs S3 (`STORAGE_PROVIDER=s3`). |
