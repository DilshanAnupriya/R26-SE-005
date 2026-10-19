# Hosting SprintIQ

Where each part runs, and how to put it there, step by step.

```
Browser ──> Vercel (frontend, Next.js)
   │
   └──────> Azure App Service (backend, Spring Boot in Docker)
                 ├──> Neon     (PostgreSQL)
                 ├──> Upstash  (Redis)
                 └──> each member's ai-service (Hugging Face Spaces)
                        M1 story points + velocity   -> Dilshan's Space
                        M2 risk models               -> member 2's Space
                        M3 resource allocation       -> member 3's Space
                        M4 cost models               -> member 4's Space
```

The browser talks to the backend directly (with a short-lived token), and to the
frontend's own server for sign-in (which keeps the refresh token in a secure cookie).
The backend is the only thing that talks to the database, Redis and the AI models.

Do the steps in order: the backend needs the database, Redis and the models' addresses;
the frontend needs the backend's address; and the backend then needs the frontend's.

---

## Step 0 · Before you start

1. **Commit and push the `Latest` branch.** GitHub builds the backend image and Vercel
   builds the frontend straight from GitHub, so anything not pushed is not hosted.
2. **Accounts:** Azure (Azure for Students gives US$100 credit), Vercel (sign in with
   GitHub), Hugging Face (each member, free), and your existing Neon and Upstash.
3. **Make a JWT secret** — a long random key the backend signs sign-in tokens with. In
   Git Bash:

   ```bash
   openssl rand -base64 64 | tr -d '\n'
   ```

   Keep it somewhere private. Never put it, or any password below, in git.

---

## Step 1 · Neon (database)

1. Neon console → your project → **Connection Details**.
2. Turn **Pooled connection off** and copy the host, database name, user and password.
   The backend creates its tables on first start with Flyway (34 migrations), and Flyway
   needs a direct connection, not the pooled one.
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

## Step 3 · Each member hosts their AI models (Hugging Face Spaces)

Every member deploys **the same `ai-service` folder** to their own Space. It contains
everyone's models; the backend only sends each member's models to that member's Space.
Hugging Face's free CPU (2 vCPU, 16 GB) is enough, and `ai-service/Dockerfile` is
already written for it.

For each member:

1. huggingface.co → **New Space**. Name it, e.g. `sprintiq`. **SDK: Docker**, template
   **Blank**, hardware **CPU basic (free)**, visibility **Public**.
2. In the new Space → **Files** → **Add file** → **Upload files**, and upload from
   `ai-service/`: `Dockerfile`, `requirements.txt`, the `app` folder and the `models`
   folder. The browser handles the large model files.
   - **Do not upload `ai-service/README.md`.** The Space's own README holds its settings
     (`sdk: docker`); replacing it breaks the Space.
   - Keep `models/` complete: the service will not start without the story-point model.
3. Open the **Logs** tab and wait for the build to finish (about 10 minutes the first
   time) and the status to show **Running**.
4. Check it: open `https://<owner>-<space>.hf.space/health`. It should show
   `"status":"UP"`.
5. Send the Space's base address, `https://<owner>-<space>.hf.space`, to whoever sets up
   the backend.

---

## Step 4 · Build the backend image (GitHub Actions)

`.github/workflows/backend-image.yml` builds the backend's Docker image on GitHub's
machines whenever `backend/` changes on `Latest` or `main`, and publishes it as
`ghcr.io/<owner>/sprintiq-backend:latest`. Building on GitHub avoids this laptop's
network problems (Norton's HTTPS scanning, the slow connection).

1. Push. On GitHub → **Actions** → **Backend image**, wait for a green tick. (Or click
   **Run workflow** to build without a change.)
2. GitHub → your profile → **Packages** → **sprintiq-backend** → **Package settings** →
   **Change visibility** → **Public**. Azure can then pull it with no password.
   (To keep it private instead, make a personal access token with `read:packages`, and
   give it to Azure in step 5.)

---

## Step 5 · Host the backend (Azure App Service)

1. Azure Portal → **Create a resource** → **Web App**.
   - **Resource group:** new, e.g. `sprintiq`.
   - **Name:** e.g. `sprintiq-api` → the address becomes
     `https://sprintiq-api.azurewebsites.net` (Azure may add a suffix; use what it shows).
   - **Publish:** Container. **Operating system:** Linux.
   - **Region:** the one closest to your Neon region (Neon Singapore → Southeast Asia).
   - **Pricing plan:** **Basic B1**. The free F1 plan cannot run Spring Boot well.
2. **Container** tab: image source **Other container registries**, access **Public**,
   registry server `https://ghcr.io`, image and tag `<owner>/sprintiq-backend:latest`
   (all lowercase). Then **Review + create**.
3. When it is created → **Settings** → **Environment variables** → **Advanced edit**,
   and paste this, with your values:

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
     { "name": "PYTHON_API_URL", "value": "https://<member3-space>.hf.space/predict", "slotSetting": false },
     { "name": "CORS_ALLOWED_ORIGINS", "value": "https://<your-app>.vercel.app", "slotSetting": false },
     { "name": "APP_BASE_URL", "value": "https://<your-app>.vercel.app", "slotSetting": false }
   ]
   ```

   - `AI_PROVIDER=http` makes the backend call the real models; without it, it uses
     made-up mock answers.
   - `PYTHON_API_URL` is for resource allocation (M3), which reads its address from
     here rather than from the model table.
   - You do not know the Vercel address yet. Put a placeholder in and fix it in step 6.
   - Optional: `ADVISOR_API_KEY` (risk advisor and Copilot). Email settings (`MAIL_HOST`
     and so on) are optional too: registration works without email.
4. **Settings** → **Configuration** → **General settings**: **Web sockets: On** (live
   board updates use them), **Always on: On**, **HTTPS Only: On**. Save.
5. **Monitoring** → **Health check**: path `/actuator/health/readiness`. Save.
6. **Overview** → **Restart**, then **Log stream**. The first start takes a minute or two
   while the migrations run; wait for `Started SprintIqApplication`.
7. Check: `https://<your-api>.azurewebsites.net/actuator/health` shows `"status":"UP"`.

---

## Step 6 · Host the frontend (Vercel)

1. vercel.com → **Add New** → **Project** → import `R26-SE-005`.
2. **Root Directory:** `frontend`. The framework is detected as Next.js.
3. **Environment Variables:**

   | Name | Value |
   |---|---|
   | `NEXT_PUBLIC_API_URL` | `https://<your-api>.azurewebsites.net/api/v1` |
   | `NEXT_PUBLIC_WS_URL` | `wss://<your-api>.azurewebsites.net/ws` |
   | `BACKEND_INTERNAL_URL` | `https://<your-api>.azurewebsites.net/api/v1` |
   | `ENABLE_EXPERIMENTAL_COREPACK` | `1` |

   The last one makes Vercel use pnpm 11, the version `package.json` asks for.
4. **Deploy**, and note the production address, e.g. `https://sprintiq.vercel.app`.
5. Back in Azure → **Environment variables**: set `CORS_ALLOWED_ORIGINS` and
   `APP_BASE_URL` to that exact address (`https://`, no slash at the end). Restart.

`NEXT_PUBLIC_*` values are built into the frontend, so after changing one on Vercel,
**redeploy** (Deployments → ⋯ → Redeploy).

---

## Step 7 · First account, and connect each member's models

1. Open the Vercel address → **Create an account**. The first account creates your
   organisation and is its owner.
2. Point each model at its owner's Space. From the repository, in Git Bash:

   ```bash
   SPRINTIQ_API=https://<your-api>.azurewebsites.net/api/v1 SPRINTIQ_EMAIL=<your email> SPRINTIQ_PASSWORD=<your password> ai-service/.venv/Scripts/python.exe scripts/point-models.py M1=https://<dilshan>-sprintiq.hf.space M2=https://<member2>-sprintiq.hf.space M4=https://<member4>-sprintiq.hf.space
   ```

   It prints each model with its new address. It goes through the backend's admin API,
   not straight into the database, on purpose: the backend caches these addresses in
   Redis, and the API clears that cache. It also raises each model's time limit to 30
   seconds, because a free Space answers slowly when it has just woken up.
3. Check in the app: open a story → **Estimate with AI** → **Suggest**; open a sprint →
   **Predict velocity**. A number with an explanation means the whole chain works.

Optional demo data: `scripts/seed-m1-edge-cases.py` builds the M1 edge-case projects
through the same API. Run it with the same three `SPRINTIQ_*` settings (with only one
person in the organisation its team is a team of one).

---

## Presentation-day checklist

Free services go to sleep, and the first request then waits for them to wake up.

| Service | Sleeps after | Do this 10 minutes before |
|---|---|---|
| Hugging Face Spaces | 48 hours without traffic | open each `https://<space>.hf.space/health` and wait for `UP` |
| Neon | 5 minutes idle | nothing — it wakes in about a second |
| Azure B1 | never, with Always on | open `/actuator/health` |
| Vercel | never | — |

Then run one estimate and one velocity prediction yourself.

## Costs

Azure B1 is about US$13 a month, taken from the student credit; stop or delete the Web
App after the presentation to stop it using credit. Neon, Upstash, Vercel, Hugging Face
and GitHub's container registry are free at this size.

## When something does not work

| What you see | Usually |
|---|---|
| The site loads, but sign-in says the API is unreachable, or the browser shows a CORS error | `CORS_ALLOWED_ORIGINS` is not exactly the Vercel address (`https://`, no slash at the end). Fix it and restart the Web App. |
| Changed a `NEXT_PUBLIC_*` value and nothing changed | Redeploy on Vercel: those values are built in. |
| Live board updates do not arrive | Web sockets are off in Azure, or `NEXT_PUBLIC_WS_URL` is not `wss://…/ws`. |
| The backend keeps restarting | Log stream. Usually `DB_URL` (must start `jdbc:postgresql://` and end `?sslmode=require`) or Redis (`SPRING_DATA_REDIS_SSL_ENABLED=true`). |
| Estimates come back "failed" or "degraded" | That member's Space is asleep or its address is wrong. Open its `/health`, then run `point-models.py` again if the address changed. |
| Answers look made up ("against a project baseline") | `AI_PROVIDER` is not `http`. |
| Vercel build fails at install | `ENABLE_EXPERIMENTAL_COREPACK=1` is missing. |
| Uploaded attachments disappear after a restart | Attachments are stored inside the container. Fine for a demo; lasting storage needs S3 (`STORAGE_PROVIDER=s3`). |
