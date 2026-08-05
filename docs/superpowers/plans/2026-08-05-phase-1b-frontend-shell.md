# Phase 1b Frontend Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the SprintIQ web app — marketing landing page, auth pages, signed-in app shell, and the client infrastructure (design system, API client, single-flight token refresh, query cache) that every later feature slice builds on.

**Architecture:** Next.js App Router. A thin BFF of Next route handlers owns `/auth/*` and is the only place the refresh token appears — it lives in an httpOnly cookie, while the access token is held in memory. All other data calls go straight to the Spring backend at `:8080` with a bearer token. TanStack Query owns every byte of server state; Zustand holds only client-local UI state.

**Tech Stack:** Next.js 16, React 19, TypeScript, Tailwind CSS 4 (CSS-first `@theme`), shadcn/ui (Radix), TanStack Query v5, Zustand 5, Zod 4, react-hook-form, GSAP + ScrollTrigger (marketing only, code-split), Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-05-phase-1b-frontend-shell-design.md`

---

## Conventions for every task

- Work in `frontend/`. All paths below are relative to the repository root.
- The package manager is `pnpm`, enabled through Corepack (Task 1). Never `npm install`.
- Commit at the end of every task. Never commit with failing tests.
- Backend must be running for e2e work: `make infra && make backend`.

## File structure

| File | Responsibility |
|---|---|
| `frontend/src/app/globals.css` | The entire token layer — Tailwind 4 `@theme`, light/dark palettes, semantic tokens |
| `frontend/src/lib/env.ts` | Validated environment access; fails loudly at boot, not at first fetch |
| `frontend/src/lib/api/schemas.ts` | Zod schemas mirroring the backend's `ApiError`, `PageResponse<T>`, and auth DTOs |
| `frontend/src/lib/api/errors.ts` | `ApiError` class + normalisation of any failure into it |
| `frontend/src/lib/api/token-store.ts` | In-memory access token; the single-flight refresh queue |
| `frontend/src/lib/api/client.ts` | Typed fetch wrapper: headers, correlation id, envelope parsing, 401 retry |
| `frontend/src/lib/api/query-keys.ts` | One key factory so invalidation is greppable |
| `frontend/src/server/session.ts` | Server-only refresh-cookie read/write/clear |
| `frontend/src/server/backend.ts` | Server-side calls from route handlers to the Spring backend |
| `frontend/src/app/api/auth/*/route.ts` | The BFF |
| `frontend/src/middleware.ts` | Cookie-presence gate for `(app)` routes |
| `frontend/src/stores/ui-store.ts` | Zustand: sidebar, command palette, theme |
| `frontend/src/components/providers.tsx` | Query client, session context, theme provider |
| `frontend/src/features/auth/*` | Schemas, hooks and forms for the auth pages |
| `frontend/src/components/layout/*` | Sidebar, Topbar, OrgSwitcher, UserMenu, CommandPalette |
| `frontend/src/components/marketing/*` | Landing sections and their motion |
| `frontend/src/lib/motion.ts` | Shared durations/easings + `prefers-reduced-motion` helper |

Files that change together live together: each auth page's schema, hook and form sit in `features/auth/`, not scattered across three technical layers.

---

## Task 1: Scaffold the application

**Files:**
- Create: `frontend/` (via CLI), `frontend/.npmrc`, `frontend/.env.local`, `frontend/.env.example`
- Modify: `.gitignore`

- [ ] **Step 1: Enable pnpm through Corepack**

`pnpm` is not installed but `corepack` is. Do not `brew install pnpm` — Corepack pins the version per-project so CI and this machine agree.

```bash
corepack enable
corepack prepare pnpm@latest --activate
pnpm --version
```

Expected: a version number prints (10.x or later).

- [ ] **Step 2: Scaffold Next.js**

Run from the repository root. `frontend/` exists but is empty, so target it directly.

```bash
cd /Users/dilshan/Documents/GitHub/R26-SE-005
pnpm create next-app@latest frontend \
  --typescript --tailwind --eslint --app --src-dir \
  --import-alias "@/*" --use-pnpm --turbopack --no-git
```

Expected: `frontend/package.json`, `frontend/src/app/layout.tsx`, `frontend/src/app/globals.css` exist. If the CLI asks anything interactively, accept the flag values above.

- [ ] **Step 3: Verify the versions we chose actually landed**

```bash
cd frontend && pnpm ls next react tailwindcss --depth 0
```

Expected: `next@16.x`, `react@19.x`, `tailwindcss@4.x`. If Tailwind resolved to 3.x, stop — the token layer in Task 2 assumes CSS-first `@theme` and the plan diverges here.

- [ ] **Step 4: Install runtime dependencies**

```bash
cd frontend
pnpm add @tanstack/react-query@^5 zustand@^5 zod@^4 react-hook-form @hookform/resolvers \
  next-themes lucide-react class-variance-authority clsx tailwind-merge cmdk sonner
pnpm add -D @tanstack/eslint-plugin-query
```

- [ ] **Step 5: Environment files**

Create `frontend/.env.example`:

```bash
NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
BACKEND_INTERNAL_URL=http://localhost:8080/api/v1
```

`NEXT_PUBLIC_API_URL` is what the browser calls directly. `BACKEND_INTERNAL_URL` is what route handlers call server-side — identical in development, different in Docker where the browser sees `localhost` but the container sees `backend`.

Copy it: `cp .env.example .env.local`

- [ ] **Step 6: Ignore build artefacts**

Append to the repository-root `.gitignore` (it already ignores `node_modules/` and `.next/`; add the rest):

```
# Next.js
frontend/.next/
frontend/out/
frontend/next-env.d.ts
frontend/.env*.local
```

- [ ] **Step 7: Verify the dev server boots**

```bash
cd frontend && pnpm dev
```

Expected: `Ready in …` and `http://localhost:3000` serves the starter page. Stop the server (Ctrl-C) before committing.

- [ ] **Step 8: Commit**

```bash
git add frontend .gitignore
git commit -m "chore(frontend): scaffold Next.js 16 app with pnpm via corepack"
```

---

## Task 2: Design tokens

**Files:**
- Modify: `frontend/src/app/globals.css` (replace wholesale)

Tailwind 4 has no `tailwind.config.ts`. Tokens are declared in CSS with `@theme`, which is why the whole design system fits in one reviewable file.

- [ ] **Step 1: Replace `globals.css`**

```css
@import "tailwindcss";

/* Tailwind 4 has no `darkMode: 'class'` config option; the variant is declared here. */
@custom-variant dark (&:where(.dark, .dark *));

/*
 * Palette in OKLCH so light and dark stay perceptually even — the same lightness
 * number means the same apparent brightness across hues, which HSL does not give you.
 */
@theme {
  --font-sans: var(--font-inter), ui-sans-serif, system-ui, sans-serif;
  --font-mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;

  /* Tight scale: a dense product needs small confident steps, not a display ramp. */
  --text-2xs: 0.6875rem;
  --text-xs: 0.75rem;
  --text-sm: 0.8125rem;
  --text-base: 0.875rem;
  --text-lg: 1rem;
  --text-xl: 1.25rem;
  --text-2xl: 1.5rem;
  --text-3xl: 2rem;
  --text-4xl: 3rem;
  --text-5xl: 3.75rem;

  --radius-sm: 0.25rem;
  --radius-md: 0.375rem;
  --radius-lg: 0.5rem;

  --ease-out-quart: cubic-bezier(0.25, 1, 0.5, 1);
  --ease-in-quart: cubic-bezier(0.5, 0, 0.75, 0);
  --ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1);
}

:root {
  --background: oklch(1 0 0);
  --foreground: oklch(0.21 0.02 264);
  --card: oklch(1 0 0);
  --card-foreground: oklch(0.21 0.02 264);
  --popover: oklch(1 0 0);
  --popover-foreground: oklch(0.21 0.02 264);
  --primary: oklch(0.55 0.19 264);
  --primary-foreground: oklch(0.99 0 0);
  --secondary: oklch(0.97 0.005 264);
  --secondary-foreground: oklch(0.28 0.02 264);
  --muted: oklch(0.97 0.005 264);
  --muted-foreground: oklch(0.55 0.015 264);
  --accent: oklch(0.96 0.01 264);
  --accent-foreground: oklch(0.28 0.02 264);
  --destructive: oklch(0.58 0.22 27);
  --destructive-foreground: oklch(0.99 0 0);
  --border: oklch(0.92 0.005 264);
  --input: oklch(0.92 0.005 264);
  --ring: oklch(0.55 0.19 264);

  /*
   * Semantic tokens. Colour that MEANS something never shares a name with colour
   * that decorates — that separation is what stops a designer restyling the chrome
   * and silently changing what "at risk" looks like.
   */
  --status-todo: oklch(0.62 0.02 264);
  --status-progress: oklch(0.62 0.17 250);
  --status-review: oklch(0.62 0.18 300);
  --status-done: oklch(0.62 0.16 150);
  --status-blocked: oklch(0.62 0.20 27);

  --priority-lowest: oklch(0.65 0.02 264);
  --priority-low: oklch(0.65 0.10 220);
  --priority-medium: oklch(0.68 0.13 90);
  --priority-high: oklch(0.65 0.17 45);
  --priority-critical: oklch(0.58 0.22 27);

  --risk-low: oklch(0.62 0.16 150);
  --risk-medium: oklch(0.68 0.15 75);
  --risk-high: oklch(0.58 0.22 27);

  --ai-accent: oklch(0.60 0.19 295);
  --ai-confidence-high: oklch(0.62 0.16 150);
  --ai-confidence-low: oklch(0.68 0.15 75);
}

.dark {
  --background: oklch(0.16 0.012 264);
  --foreground: oklch(0.96 0.003 264);
  --card: oklch(0.19 0.014 264);
  --card-foreground: oklch(0.96 0.003 264);
  --popover: oklch(0.20 0.014 264);
  --popover-foreground: oklch(0.96 0.003 264);
  --primary: oklch(0.68 0.17 264);
  --primary-foreground: oklch(0.16 0.012 264);
  --secondary: oklch(0.25 0.015 264);
  --secondary-foreground: oklch(0.96 0.003 264);
  --muted: oklch(0.25 0.015 264);
  --muted-foreground: oklch(0.68 0.012 264);
  --accent: oklch(0.27 0.017 264);
  --accent-foreground: oklch(0.96 0.003 264);
  --destructive: oklch(0.62 0.20 27);
  --destructive-foreground: oklch(0.98 0 0);
  --border: oklch(0.28 0.015 264);
  --input: oklch(0.30 0.015 264);
  --ring: oklch(0.68 0.17 264);

  --status-todo: oklch(0.70 0.02 264);
  --status-progress: oklch(0.70 0.16 250);
  --status-review: oklch(0.70 0.16 300);
  --status-done: oklch(0.70 0.15 150);
  --status-blocked: oklch(0.70 0.18 27);

  --priority-lowest: oklch(0.70 0.02 264);
  --priority-low: oklch(0.70 0.10 220);
  --priority-medium: oklch(0.75 0.13 90);
  --priority-high: oklch(0.72 0.16 45);
  --priority-critical: oklch(0.68 0.20 27);

  --risk-low: oklch(0.70 0.15 150);
  --risk-medium: oklch(0.75 0.14 75);
  --risk-high: oklch(0.68 0.20 27);

  --ai-accent: oklch(0.70 0.17 295);
  --ai-confidence-high: oklch(0.70 0.15 150);
  --ai-confidence-low: oklch(0.75 0.14 75);
}

/* Expose the CSS variables above to Tailwind utilities (bg-background, text-status-done…). */
@theme inline {
  --color-background: var(--background);
  --color-foreground: var(--foreground);
  --color-card: var(--card);
  --color-card-foreground: var(--card-foreground);
  --color-popover: var(--popover);
  --color-popover-foreground: var(--popover-foreground);
  --color-primary: var(--primary);
  --color-primary-foreground: var(--primary-foreground);
  --color-secondary: var(--secondary);
  --color-secondary-foreground: var(--secondary-foreground);
  --color-muted: var(--muted);
  --color-muted-foreground: var(--muted-foreground);
  --color-accent: var(--accent);
  --color-accent-foreground: var(--accent-foreground);
  --color-destructive: var(--destructive);
  --color-destructive-foreground: var(--destructive-foreground);
  --color-border: var(--border);
  --color-input: var(--input);
  --color-ring: var(--ring);

  --color-status-todo: var(--status-todo);
  --color-status-progress: var(--status-progress);
  --color-status-review: var(--status-review);
  --color-status-done: var(--status-done);
  --color-status-blocked: var(--status-blocked);

  --color-priority-lowest: var(--priority-lowest);
  --color-priority-low: var(--priority-low);
  --color-priority-medium: var(--priority-medium);
  --color-priority-high: var(--priority-high);
  --color-priority-critical: var(--priority-critical);

  --color-risk-low: var(--risk-low);
  --color-risk-medium: var(--risk-medium);
  --color-risk-high: var(--risk-high);

  --color-ai-accent: var(--ai-accent);
  --color-ai-confidence-high: var(--ai-confidence-high);
  --color-ai-confidence-low: var(--ai-confidence-low);
}

@layer base {
  * {
    border-color: var(--border);
  }
  body {
    background-color: var(--background);
    color: var(--foreground);
    font-family: var(--font-sans);
    -webkit-font-smoothing: antialiased;
  }
  /*
   * One global reduced-motion rule so no component can forget it. Individual GSAP
   * timelines still check the media query (lib/motion.ts) because JS-driven
   * animation is not reachable from CSS.
   */
  @media (prefers-reduced-motion: reduce) {
    *,
    *::before,
    *::after {
      animation-duration: 0.01ms !important;
      animation-iteration-count: 1 !important;
      transition-duration: 0.01ms !important;
      scroll-behavior: auto !important;
    }
  }
}
```

- [ ] **Step 2: Load the font in the root layout**

Replace `frontend/src/app/layout.tsx`:

```tsx
import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter" });

export const metadata: Metadata = {
  title: "SprintIQ — AI-powered agile delivery",
  description:
    "Plan, execute and track sprints with AI estimation, risk and cost forecasting attached to the decisions you are actually making.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className={`${inter.variable} font-sans antialiased`}>{children}</body>
    </html>
  );
}
```

`suppressHydrationWarning` is required because the theme provider (Task 12) writes `class="dark"` onto `<html>` before React hydrates.

- [ ] **Step 3: Verify tokens compile**

Temporarily replace `frontend/src/app/page.tsx` with:

```tsx
export default function Page() {
  return (
    <main className="bg-background text-foreground p-8">
      <p className="text-status-done">done</p>
      <p className="text-risk-high">at risk</p>
      <p className="text-ai-accent">ai</p>
    </main>
  );
}
```

```bash
cd frontend && pnpm build
```

Expected: build succeeds. If a `text-status-done` class does not exist, the `@theme inline` block is wrong — Tailwind only generates utilities for names declared there.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app
git commit -m "feat(frontend): OKLCH design tokens with semantic status, risk and AI colours"
```

---

## Task 3: shadcn/ui primitives

**Files:**
- Create: `frontend/components.json`, `frontend/src/lib/utils.ts`, `frontend/src/components/ui/*`

- [ ] **Step 1: Initialise shadcn**

```bash
cd frontend && pnpm dlx shadcn@latest init
```

Answer: style `new-york`, base colour `slate`, CSS variables **yes**. It detects Tailwind 4 and writes `components.json`.

- [ ] **Step 2: Confirm it did not overwrite the token layer**

```bash
git diff --stat src/app/globals.css
```

If shadcn replaced the `:root` palette, restore ours: `git checkout src/app/globals.css` and re-run init choosing "no" when asked to overwrite CSS variables. Our palette is deliberate; the default slate one is not.

- [ ] **Step 3: Add exactly the primitives this phase needs**

```bash
pnpm dlx shadcn@latest add button input label select dialog dropdown-menu \
  popover tooltip avatar badge skeleton tabs separator scroll-area \
  command sheet form sonner
```

Nothing speculative — this is the set the auth pages, shell and command palette consume.

- [ ] **Step 4: Verify build**

```bash
pnpm build
```

Expected: success.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): vendor shadcn/ui primitives for the shell"
```

---

## Task 4: Test harness

**Files:**
- Create: `frontend/vitest.config.ts`, `frontend/src/tests/setup.ts`
- Modify: `frontend/package.json`

Set the harness up before the code it tests, so Task 5 onward can be genuinely test-first.

- [ ] **Step 1: Install**

```bash
cd frontend
pnpm add -D vitest @vitejs/plugin-react jsdom @testing-library/react \
  @testing-library/user-event @testing-library/jest-dom
```

- [ ] **Step 2: Create `frontend/vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/tests/setup.ts"],
    // Playwright specs live in src/tests/e2e and are driven by Playwright, not Vitest.
    exclude: ["node_modules", ".next", "src/tests/e2e/**"],
  },
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
});
```

- [ ] **Step 3: Create `frontend/src/tests/setup.ts`**

```ts
import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// jsdom implements neither of these, and Radix and the theme provider both use them.
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }),
});

globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
```

- [ ] **Step 4: Add scripts to `frontend/package.json`**

```json
{
  "scripts": {
    "dev": "next dev --turbopack",
    "build": "next build",
    "start": "next start",
    "lint": "next lint",
    "test": "vitest run",
    "test:watch": "vitest",
    "typecheck": "tsc --noEmit"
  }
}
```

- [ ] **Step 5: Prove the harness runs**

Create `frontend/src/tests/harness.test.ts`:

```ts
import { describe, expect, it } from "vitest";

describe("test harness", () => {
  it("runs", () => {
    expect(true).toBe(true);
  });
});
```

Run: `pnpm test`
Expected: `1 passed`.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "test(frontend): vitest + testing-library harness"
```

---

## Task 5: API contract schemas

**Files:**
- Create: `frontend/src/lib/api/schemas.ts`
- Test: `frontend/src/lib/api/schemas.test.ts`

These mirror the backend exactly. `ApiError` comes from `common/error/ApiError.java`; `PageResponse` from `common/web/PageResponse.java`; the auth types from `modules/auth/web/dto/AuthDtos.java`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/api/schemas.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { apiErrorSchema, authTokensSchema, pageResponseSchema } from "./schemas";
import { z } from "zod";

describe("apiErrorSchema", () => {
  it("parses the backend error envelope including field errors", () => {
    const parsed = apiErrorSchema.parse({
      timestamp: "2026-08-05T10:00:00Z",
      status: 422,
      code: "VALIDATION_ERROR",
      message: "Request validation failed",
      path: "/api/v1/auth/register",
      correlationId: "abc-123",
      fieldErrors: [{ field: "password", message: "too short" }],
    });
    expect(parsed.code).toBe("VALIDATION_ERROR");
    expect(parsed.fieldErrors?.[0].field).toBe("password");
  });

  it("accepts an error with no field errors", () => {
    const parsed = apiErrorSchema.parse({
      timestamp: "2026-08-05T10:00:00Z",
      status: 404,
      code: "NOT_FOUND",
      message: "Project not found",
      path: "/api/v1/projects/1",
      correlationId: "abc-124",
    });
    expect(parsed.fieldErrors).toBeUndefined();
  });
});

describe("pageResponseSchema", () => {
  it("parses the paging envelope with a typed content array", () => {
    const schema = pageResponseSchema(z.object({ id: z.string() }));
    const parsed = schema.parse({
      content: [{ id: "a" }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });
    expect(parsed.content).toHaveLength(1);
  });
});

describe("authTokensSchema", () => {
  it("parses a login response with its organization list", () => {
    const parsed = authTokensSchema.parse({
      accessToken: "jwt",
      refreshToken: "opaque",
      tokenType: "Bearer",
      expiresIn: 900,
      user: {
        id: "11111111-1111-1111-1111-111111111111",
        email: "a@b.com",
        fullName: "A B",
        avatarUrl: null,
        timezone: "UTC",
        locale: "en",
        emailVerified: true,
        mfaEnabled: false,
        lastLoginAt: null,
        activeOrganizationId: "22222222-2222-2222-2222-222222222222",
        activeOrganizationName: "Acme",
        activeRole: "OWNER",
        permissions: ["project:read"],
        organizations: [
          { id: "22222222-2222-2222-2222-222222222222", name: "Acme", slug: "acme", logoUrl: null, roleCode: "OWNER" },
        ],
      },
    });
    expect(parsed.user.organizations[0].slug).toBe("acme");
    expect(parsed.user.permissions).toContain("project:read");
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && pnpm vitest run src/lib/api/schemas.test.ts`
Expected: FAIL — `Failed to resolve import "./schemas"`.

- [ ] **Step 3: Create `frontend/src/lib/api/schemas.ts`**

```ts
import { z } from "zod";

/** Mirrors common/error/ApiError.java. */
export const fieldErrorSchema = z.object({
  field: z.string(),
  message: z.string(),
});

export const apiErrorSchema = z.object({
  timestamp: z.string(),
  status: z.number(),
  code: z.string(),
  message: z.string(),
  path: z.string(),
  correlationId: z.string().nullable().optional(),
  fieldErrors: z.array(fieldErrorSchema).optional(),
});

export type FieldError = z.infer<typeof fieldErrorSchema>;
export type ApiErrorBody = z.infer<typeof apiErrorSchema>;

/**
 * Mirrors common/web/PageResponse.java. A factory rather than a fixed schema so the
 * content type is checked too — an untyped `unknown[]` here would defeat the point of
 * validating at the boundary.
 */
export const pageResponseSchema = <T extends z.ZodTypeAny>(content: T) =>
  z.object({
    content: z.array(content),
    page: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
    hasNext: z.boolean(),
    hasPrevious: z.boolean(),
  });

/** Mirrors AuthDtos.OrganizationSummary. */
export const organizationSummarySchema = z.object({
  id: z.string(),
  name: z.string(),
  slug: z.string(),
  logoUrl: z.string().nullable(),
  roleCode: z.string(),
});

/** Mirrors AuthDtos.AuthenticatedUser. */
export const authenticatedUserSchema = z.object({
  id: z.string(),
  email: z.string(),
  fullName: z.string(),
  avatarUrl: z.string().nullable(),
  timezone: z.string(),
  locale: z.string(),
  emailVerified: z.boolean(),
  mfaEnabled: z.boolean(),
  lastLoginAt: z.string().nullable(),
  activeOrganizationId: z.string().nullable(),
  activeOrganizationName: z.string().nullable(),
  activeRole: z.string().nullable(),
  permissions: z.array(z.string()),
  organizations: z.array(organizationSummarySchema),
});

/** Mirrors AuthDtos.AuthTokens. */
export const authTokensSchema = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  tokenType: z.string(),
  expiresIn: z.number(),
  user: authenticatedUserSchema,
});

/**
 * What the BFF returns to the browser: the same payload minus refreshToken, which
 * stays in the httpOnly cookie and must never reach client JavaScript.
 */
export const clientSessionSchema = authTokensSchema.omit({ refreshToken: true });

export type OrganizationSummary = z.infer<typeof organizationSummarySchema>;
export type AuthenticatedUser = z.infer<typeof authenticatedUserSchema>;
export type AuthTokens = z.infer<typeof authTokensSchema>;
export type ClientSession = z.infer<typeof clientSessionSchema>;
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/lib/api/schemas.test.ts`
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/api
git commit -m "feat(frontend): Zod schemas mirroring the backend API envelopes"
```

---

## Task 6: Error normalisation

**Files:**
- Create: `frontend/src/lib/api/errors.ts`
- Test: `frontend/src/lib/api/errors.test.ts`

Every failure — a backend `ApiError`, an HTML 502 from a proxy, a dropped connection — becomes one `ApiError` instance, so no consumer ever has to guess what it caught.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/api/errors.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { ApiError, toApiError } from "./errors";

describe("toApiError", () => {
  it("preserves a well-formed backend error", async () => {
    const response = new Response(
      JSON.stringify({
        timestamp: "2026-08-05T10:00:00Z",
        status: 422,
        code: "VALIDATION_ERROR",
        message: "Request validation failed",
        path: "/api/v1/auth/register",
        correlationId: "cid-1",
        fieldErrors: [{ field: "email", message: "must be a well-formed email address" }],
      }),
      { status: 422, headers: { "content-type": "application/json" } },
    );

    const error = await toApiError(response);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("VALIDATION_ERROR");
    expect(error.correlationId).toBe("cid-1");
    expect(error.fieldErrors).toHaveLength(1);
  });

  it("synthesises an error when the body is not our envelope", async () => {
    const response = new Response("<html>502 Bad Gateway</html>", {
      status: 502,
      headers: { "content-type": "text/html" },
    });

    const error = await toApiError(response);
    expect(error.status).toBe(502);
    expect(error.code).toBe("UNKNOWN_ERROR");
    // The raw HTML must not become the user-facing message.
    expect(error.message).not.toContain("<html>");
  });

  it("marks 401 TOKEN_EXPIRED as refreshable and other 401s as not", async () => {
    const expired = await toApiError(
      new Response(
        JSON.stringify({
          timestamp: "t", status: 401, code: "TOKEN_EXPIRED",
          message: "Token has expired", path: "/p", correlationId: "c",
        }),
        { status: 401, headers: { "content-type": "application/json" } },
      ),
    );
    const badCredentials = await toApiError(
      new Response(
        JSON.stringify({
          timestamp: "t", status: 401, code: "INVALID_CREDENTIALS",
          message: "Invalid email or password", path: "/p", correlationId: "c",
        }),
        { status: 401, headers: { "content-type": "application/json" } },
      ),
    );

    expect(expired.isRefreshable).toBe(true);
    expect(badCredentials.isRefreshable).toBe(false);
  });

  it("exposes field errors as a record for react-hook-form", async () => {
    const error = await toApiError(
      new Response(
        JSON.stringify({
          timestamp: "t", status: 422, code: "VALIDATION_ERROR", message: "m",
          path: "/p", correlationId: "c",
          fieldErrors: [
            { field: "password", message: "too weak" },
            { field: "email", message: "already taken" },
          ],
        }),
        { status: 422, headers: { "content-type": "application/json" } },
      ),
    );

    expect(error.fieldErrorMap()).toEqual({ password: "too weak", email: "already taken" });
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `pnpm vitest run src/lib/api/errors.test.ts`
Expected: FAIL — cannot resolve `./errors`.

- [ ] **Step 3: Create `frontend/src/lib/api/errors.ts`**

```ts
import { apiErrorSchema, type FieldError } from "./schemas";

/**
 * Every failure the client can see, in one shape.
 *
 * The alternative — letting callers handle `Response`, `TypeError` and thrown
 * strings — pushes the same defensive branching into every hook, and one of them
 * always forgets. Normalising once means `catch (e)` has a known type everywhere.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly path: string;
  readonly correlationId: string | null;
  readonly fieldErrors: FieldError[];

  constructor(init: {
    status: number;
    code: string;
    message: string;
    path?: string;
    correlationId?: string | null;
    fieldErrors?: FieldError[];
  }) {
    super(init.message);
    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.path = init.path ?? "";
    this.correlationId = init.correlationId ?? null;
    this.fieldErrors = init.fieldErrors ?? [];
  }

  /**
   * True only for an expired access token. A wrong password is also a 401 but
   * retrying it after a refresh would be nonsense — and would burn a rotation.
   */
  get isRefreshable(): boolean {
    return this.status === 401 && this.code === "TOKEN_EXPIRED";
  }

  get isValidation(): boolean {
    return this.code === "VALIDATION_ERROR" || this.fieldErrors.length > 0;
  }

  /** Permission and missing-resource failures render in place, not as a toast. */
  get isInline(): boolean {
    return this.status === 403 || this.status === 404;
  }

  fieldErrorMap(): Record<string, string> {
    return Object.fromEntries(this.fieldErrors.map((e) => [e.field, e.message]));
  }
}

const FALLBACK_MESSAGES: Record<number, string> = {
  0: "Could not reach the server. Check your connection and try again.",
  500: "Something went wrong on our side. Please try again.",
  502: "The server is temporarily unavailable. Please try again.",
  503: "The server is temporarily unavailable. Please try again.",
  504: "The server took too long to respond. Please try again.",
};

/** Turns any non-OK Response into an ApiError. Never throws. */
export async function toApiError(response: Response): Promise<ApiError> {
  let body: unknown;
  try {
    body = await response.clone().json();
  } catch {
    body = null;
  }

  const parsed = apiErrorSchema.safeParse(body);
  if (parsed.success) {
    return new ApiError({
      status: parsed.data.status,
      code: parsed.data.code,
      message: parsed.data.message,
      path: parsed.data.path,
      correlationId: parsed.data.correlationId ?? null,
      fieldErrors: parsed.data.fieldErrors,
    });
  }

  // Not our envelope — a proxy, a gateway, a crash. Never surface the raw body:
  // it is usually HTML and always unhelpful to the person reading it.
  return new ApiError({
    status: response.status,
    code: "UNKNOWN_ERROR",
    message:
      FALLBACK_MESSAGES[response.status] ??
      `Request failed with status ${response.status}.`,
    path: new URL(response.url || "http://localhost").pathname,
  });
}

/** For network failures, where there is no Response at all. */
export function toNetworkError(cause: unknown): ApiError {
  return new ApiError({
    status: 0,
    code: "NETWORK_ERROR",
    message: FALLBACK_MESSAGES[0],
    correlationId: null,
    fieldErrors: [],
  });
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/lib/api/errors.test.ts`
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/api
git commit -m "feat(frontend): normalise every failure into a single ApiError type"
```

---

## Task 7: Single-flight token refresh

**Files:**
- Create: `frontend/src/lib/api/token-store.ts`
- Test: `frontend/src/lib/api/token-store.test.ts`

**This is the highest-risk unit in the phase.** The backend rotates refresh tokens *and* revokes the whole family on reuse. If six parallel queries 401 at once and each fires its own refresh, the backend sees one rotated token presented six times, correctly reads it as theft, and signs the user out of every device. The test below is the guarantee that cannot regress.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/api/token-store.test.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TokenStore } from "./token-store";

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe("TokenStore", () => {
  let store: TokenStore;

  beforeEach(() => {
    store = new TokenStore();
  });

  it("holds the access token in memory", () => {
    expect(store.get()).toBeNull();
    store.set("token-1");
    expect(store.get()).toBe("token-1");
    store.clear();
    expect(store.get()).toBeNull();
  });

  it("collapses concurrent refreshes into one call", async () => {
    const gate = deferred<string>();
    const refresh = vi.fn(() => gate.promise);
    store.configure({ refresh });

    // Six requests 401 at the same time — the exact shape of a board mounting.
    const all = Promise.all([
      store.refresh(), store.refresh(), store.refresh(),
      store.refresh(), store.refresh(), store.refresh(),
    ]);

    gate.resolve("fresh-token");
    const results = await all;

    expect(refresh).toHaveBeenCalledTimes(1);
    expect(results).toEqual(Array(6).fill("fresh-token"));
    expect(store.get()).toBe("fresh-token");
  });

  it("allows a new refresh after the previous one settles", async () => {
    const refresh = vi.fn().mockResolvedValueOnce("a").mockResolvedValueOnce("b");
    store.configure({ refresh });

    expect(await store.refresh()).toBe("a");
    expect(await store.refresh()).toBe("b");
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("fails every queued waiter and clears the token when refresh fails", async () => {
    const gate = deferred<string>();
    const refresh = vi.fn(() => gate.promise);
    const onExpired = vi.fn();
    store.configure({ refresh, onSessionExpired: onExpired });
    store.set("stale");

    const waiters = [
      store.refresh().catch((e) => e),
      store.refresh().catch((e) => e),
      store.refresh().catch((e) => e),
    ];
    gate.reject(new Error("refresh rejected"));
    const results = await Promise.all(waiters);

    expect(refresh).toHaveBeenCalledTimes(1);
    results.forEach((r) => expect(r).toBeInstanceOf(Error));
    expect(store.get()).toBeNull();
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it("recovers: a failed refresh does not poison later attempts", async () => {
    const refresh = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValueOnce("recovered");
    store.configure({ refresh });

    await expect(store.refresh()).rejects.toThrow("boom");
    await expect(store.refresh()).resolves.toBe("recovered");
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `pnpm vitest run src/lib/api/token-store.test.ts`
Expected: FAIL — cannot resolve `./token-store`.

- [ ] **Step 3: Create `frontend/src/lib/api/token-store.ts`**

```ts
type RefreshFn = () => Promise<string>;

interface TokenStoreConfig {
  refresh: RefreshFn;
  onSessionExpired?: () => void;
}

/**
 * Holds the access token in memory and serialises refreshes.
 *
 * In memory, not localStorage: an XSS that can read storage can steal a long-lived
 * credential and replay it anywhere. Memory limits the blast radius to one
 * 15-minute token, and the httpOnly refresh cookie is unreadable from JS entirely.
 *
 * Single-flight, because the backend rotates refresh tokens and treats a reused one
 * as theft by revoking the whole family. Concurrent refreshes would look exactly
 * like an attack and log the user out everywhere. So the first caller starts the
 * refresh and every other caller parks on the same promise.
 */
export class TokenStore {
  private accessToken: string | null = null;
  private inFlight: Promise<string> | null = null;
  private config: TokenStoreConfig | null = null;

  configure(config: TokenStoreConfig): void {
    this.config = config;
  }

  get(): string | null {
    return this.accessToken;
  }

  set(token: string): void {
    this.accessToken = token;
  }

  clear(): void {
    this.accessToken = null;
    this.inFlight = null;
  }

  /**
   * Returns a fresh access token. Concurrent callers share one network round trip.
   * Rejects for every waiter if the refresh fails, after clearing the session.
   */
  refresh(): Promise<string> {
    if (this.inFlight) {
      return this.inFlight;
    }
    if (!this.config) {
      return Promise.reject(new Error("TokenStore.configure() was never called"));
    }

    const attempt = this.config
      .refresh()
      .then((token) => {
        this.accessToken = token;
        return token;
      })
      .catch((error: unknown) => {
        this.accessToken = null;
        this.config?.onSessionExpired?.();
        throw error;
      })
      .finally(() => {
        // Released whether it resolved or rejected, so one failure never wedges
        // the store into a state where no refresh can ever be attempted again.
        this.inFlight = null;
      });

    this.inFlight = attempt;
    return attempt;
  }
}

/** Module-level instance: one token per browser tab. */
export const tokenStore = new TokenStore();
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/lib/api/token-store.test.ts`
Expected: 5 passed. The `collapses concurrent refreshes into one call` case is the one that must never be deleted.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/api
git commit -m "feat(frontend): single-flight token refresh to avoid tripping reuse detection"
```

---

## Task 8: The API client

**Files:**
- Create: `frontend/src/lib/env.ts`, `frontend/src/lib/api/client.ts`, `frontend/src/lib/api/query-keys.ts`
- Test: `frontend/src/lib/api/client.test.ts`

- [ ] **Step 1: Create `frontend/src/lib/env.ts`**

```ts
/**
 * Environment access, validated at module load. A missing API URL should fail on
 * boot with a clear message, not as a fetch to "undefined/projects" much later.
 */
const required = (name: string, value: string | undefined): string => {
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
};

export const env = {
  apiUrl: required("NEXT_PUBLIC_API_URL", process.env.NEXT_PUBLIC_API_URL),
  wsUrl: process.env.NEXT_PUBLIC_WS_URL ?? "",
};

/** Server-only. Differs from apiUrl inside Docker, where the browser and the
 *  container disagree about what "localhost" means. */
export const backendUrl = (): string =>
  process.env.BACKEND_INTERNAL_URL ?? required("NEXT_PUBLIC_API_URL", process.env.NEXT_PUBLIC_API_URL);
```

- [ ] **Step 2: Write the failing test**

Create `frontend/src/lib/api/client.test.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./client";
import { ApiError } from "./errors";
import { TokenStore } from "./token-store";
import { z } from "zod";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const errorResponse = (status: number, code: string) =>
  jsonResponse(
    { timestamp: "t", status, code, message: "m", path: "/p", correlationId: "cid" },
    status,
  );

describe("ApiClient", () => {
  let store: TokenStore;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    store = new TokenStore();
    store.set("access-1");
    fetchMock = vi.fn();
  });

  const client = () =>
    new ApiClient({
      baseUrl: "http://api.test/api/v1",
      tokenStore: store,
      fetchImpl: fetchMock as unknown as typeof fetch,
      getOrganizationId: () => "org-1",
    });

  it("sends the bearer token, organization header and a correlation id", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: "p1" }));

    await client().get("/projects/p1", z.object({ id: z.string() }));

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/v1/projects/p1");
    const headers = new Headers(init.headers);
    expect(headers.get("Authorization")).toBe("Bearer access-1");
    expect(headers.get("X-Organization-Id")).toBe("org-1");
    expect(headers.get("X-Correlation-Id")).toBeTruthy();
  });

  it("validates the response body against the schema", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 42 }));

    await expect(
      client().get("/projects/p1", z.object({ id: z.string() })),
    ).rejects.toThrow(/schema/i);
  });

  it("refreshes once and replays the request on TOKEN_EXPIRED", async () => {
    const refresh = vi.fn(async () => "access-2");
    store.configure({ refresh });

    fetchMock
      .mockResolvedValueOnce(errorResponse(401, "TOKEN_EXPIRED"))
      .mockResolvedValueOnce(jsonResponse({ id: "p1" }));

    const result = await client().get("/projects/p1", z.object({ id: z.string() }));

    expect(result).toEqual({ id: "p1" });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // The replay must carry the NEW token, not the stale one.
    const replayHeaders = new Headers(fetchMock.mock.calls[1][1].headers);
    expect(replayHeaders.get("Authorization")).toBe("Bearer access-2");
  });

  it("does not refresh on a non-expiry 401", async () => {
    const refresh = vi.fn(async () => "access-2");
    store.configure({ refresh });
    fetchMock.mockResolvedValue(errorResponse(401, "INVALID_CREDENTIALS"));

    await expect(
      client().get("/projects/p1", z.object({ id: z.string() })),
    ).rejects.toBeInstanceOf(ApiError);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("retries a refreshed request only once", async () => {
    const refresh = vi.fn(async () => "access-2");
    store.configure({ refresh });
    fetchMock.mockResolvedValue(errorResponse(401, "TOKEN_EXPIRED"));

    await expect(
      client().get("/projects/p1", z.object({ id: z.string() })),
    ).rejects.toBeInstanceOf(ApiError);
    // One original + one replay. Never a loop.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("converts a network failure into an ApiError", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const error = await client()
      .get("/projects", z.object({ id: z.string() }))
      .catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("NETWORK_ERROR");
  });

  it("returns undefined for 204 responses", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(client().delete("/projects/p1")).resolves.toBeUndefined();
  });
});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `pnpm vitest run src/lib/api/client.test.ts`
Expected: FAIL — cannot resolve `./client`.

- [ ] **Step 4: Create `frontend/src/lib/api/client.ts`**

```ts
import { z } from "zod";
import { ApiError, toApiError, toNetworkError } from "./errors";
import { tokenStore, TokenStore } from "./token-store";
import { env } from "../env";

interface ApiClientOptions {
  baseUrl: string;
  tokenStore: TokenStore;
  fetchImpl?: typeof fetch;
  getOrganizationId?: () => string | null;
}

interface RequestOptions {
  query?: Record<string, string | number | boolean | undefined | null>;
  signal?: AbortSignal;
  /** Internal: set on the replay after a refresh so a retry can never loop. */
  isRetry?: boolean;
}

/**
 * The single door to the backend.
 *
 * Responses are Zod-parsed at this boundary, so a contract change shows up here as
 * one loud error naming the endpoint — rather than as `undefined` three components
 * deep, where it looks like a rendering bug.
 */
export class ApiClient {
  private readonly baseUrl: string;
  private readonly tokens: TokenStore;
  private readonly fetchImpl: typeof fetch;
  private readonly getOrganizationId: () => string | null;

  constructor(options: ApiClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.tokens = options.tokenStore;
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.getOrganizationId = options.getOrganizationId ?? (() => null);
  }

  get<T extends z.ZodTypeAny>(path: string, schema: T, options?: RequestOptions) {
    return this.request("GET", path, schema, undefined, options);
  }

  post<T extends z.ZodTypeAny>(path: string, schema: T, body?: unknown, options?: RequestOptions) {
    return this.request("POST", path, schema, body, options);
  }

  patch<T extends z.ZodTypeAny>(path: string, schema: T, body?: unknown, options?: RequestOptions) {
    return this.request("PATCH", path, schema, body, options);
  }

  put<T extends z.ZodTypeAny>(path: string, schema: T, body?: unknown, options?: RequestOptions) {
    return this.request("PUT", path, schema, body, options);
  }

  async delete(path: string, options?: RequestOptions): Promise<void> {
    await this.send("DELETE", path, undefined, options);
  }

  private async request<T extends z.ZodTypeAny>(
    method: string,
    path: string,
    schema: T,
    body?: unknown,
    options?: RequestOptions,
  ): Promise<z.infer<T>> {
    const response = await this.send(method, path, body, options);
    if (response.status === 204) {
      return undefined as z.infer<T>;
    }

    const json = await response.json();
    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new ApiError({
        status: response.status,
        code: "SCHEMA_MISMATCH",
        message: `Response from ${method} ${path} did not match its schema: ${parsed.error.message}`,
        path,
      });
    }
    return parsed.data;
  }

  private async send(
    method: string,
    path: string,
    body?: unknown,
    options?: RequestOptions,
  ): Promise<Response> {
    const url = this.buildUrl(path, options?.query);
    const headers = new Headers({
      Accept: "application/json",
      "X-Correlation-Id": crypto.randomUUID(),
    });

    const token = this.tokens.get();
    if (token) headers.set("Authorization", `Bearer ${token}`);

    const organizationId = this.getOrganizationId();
    if (organizationId) headers.set("X-Organization-Id", organizationId);

    if (body !== undefined) headers.set("Content-Type", "application/json");

    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: options?.signal,
      });
    } catch (cause) {
      throw toNetworkError(cause);
    }

    if (response.ok) return response;

    const error = await toApiError(response);

    // Refresh and replay exactly once. `isRetry` is the loop guard: without it, a
    // backend that keeps answering 401 after a successful refresh would spin here.
    if (error.isRefreshable && !options?.isRetry) {
      await this.tokens.refresh();
      return this.send(method, path, body, { ...options, isRetry: true });
    }

    throw error;
  }

  private buildUrl(path: string, query?: RequestOptions["query"]): string {
    const url = new URL(`${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
    }
    return url.toString();
  }
}

let activeOrganizationId: string | null = null;

/** Set by the session provider when the active organization resolves or changes. */
export const setActiveOrganizationId = (id: string | null) => {
  activeOrganizationId = id;
};

export const api = new ApiClient({
  baseUrl: env.apiUrl,
  tokenStore,
  getOrganizationId: () => activeOrganizationId,
});
```

- [ ] **Step 5: Run the tests**

Run: `pnpm vitest run src/lib/api/client.test.ts`
Expected: 7 passed.

- [ ] **Step 6: Create `frontend/src/lib/api/query-keys.ts`**

```ts
/**
 * One factory for every query key.
 *
 * Invalidation is the hard part of a client cache, and it is only tractable if the
 * keys are greppable. When Phase 4 adds a WebSocket, its handler needs exactly one
 * place to look up "what do I invalidate when a story changes".
 */
export const keys = {
  session: ["session"] as const,

  organizations: {
    all: ["organizations"] as const,
    detail: (id: string) => ["organizations", id] as const,
    members: (id: string) => ["organizations", id, "members"] as const,
  },

  workspaces: {
    all: ["workspaces"] as const,
    detail: (id: string) => ["workspaces", id] as const,
  },

  projects: {
    all: ["projects"] as const,
    list: (filters?: Record<string, unknown>) => ["projects", "list", filters ?? {}] as const,
    detail: (id: string) => ["projects", id] as const,
    overview: (id: string) => ["projects", id, "overview"] as const,
    members: (id: string) => ["projects", id, "members"] as const,
    board: (id: string) => ["projects", id, "board"] as const,
  },
} as const;
```

- [ ] **Step 7: Typecheck and commit**

```bash
cd frontend && pnpm typecheck && pnpm test
git add frontend/src/lib
git commit -m "feat(frontend): typed API client with schema validation and 401 replay"
```

---

## Task 9: Server-side session helpers

**Files:**
- Create: `frontend/src/server/session.ts`, `frontend/src/server/backend.ts`
- Test: `frontend/src/server/session.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/server/session.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { NextResponse } from "next/server";
import { REFRESH_COOKIE, clearRefreshCookie, setRefreshCookie } from "./session";

describe("refresh cookie", () => {
  it("is httpOnly, lax and path-wide", () => {
    const response = NextResponse.json({ ok: true });
    setRefreshCookie(response, "opaque-token");

    const cookie = response.cookies.get(REFRESH_COOKIE);
    expect(cookie?.value).toBe("opaque-token");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
    expect(cookie?.path).toBe("/");
  });

  it("clears by expiring rather than deleting silently", () => {
    const response = NextResponse.json({ ok: true });
    clearRefreshCookie(response);

    const cookie = response.cookies.get(REFRESH_COOKIE);
    expect(cookie?.value).toBe("");
    expect(cookie?.maxAge).toBe(0);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `pnpm vitest run src/server/session.test.ts`
Expected: FAIL — cannot resolve `./session`.

- [ ] **Step 3: Create `frontend/src/server/session.ts`**

```ts
import "server-only";
import { cookies } from "next/headers";
import type { NextResponse } from "next/server";

export const REFRESH_COOKIE = "sq_rt";

/** Matches the backend's 30-day refresh token lifetime. */
const MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

/**
 * httpOnly so no script can read it — that is the entire point of the BFF.
 * SameSite=Lax rather than Strict: Strict would drop the cookie on the return leg
 * of an emailed verification or password-reset link, silently signing the user out
 * at the exact moment they followed our own link.
 */
export function setRefreshCookie(response: NextResponse, token: string): void {
  response.cookies.set({
    name: REFRESH_COOKIE,
    value: token,
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: MAX_AGE_SECONDS,
  });
}

export function clearRefreshCookie(response: NextResponse): void {
  response.cookies.set({
    name: REFRESH_COOKIE,
    value: "",
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 0,
  });
}

/** Reads the refresh token inside a route handler. `cookies()` is async in Next 15+. */
export async function readRefreshToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(REFRESH_COOKIE)?.value ?? null;
}
```

- [ ] **Step 4: Create `frontend/src/server/backend.ts`**

```ts
import "server-only";
import { backendUrl } from "@/lib/env";

/**
 * Server-side call from a route handler to the Spring backend.
 *
 * Returns the raw Response so handlers can forward the backend's status and error
 * envelope untouched — the client already knows how to read an ApiError, and
 * re-wrapping it here would lose the correlation id.
 */
export async function callBackend(
  path: string,
  init: { method: string; body?: unknown; headers?: Record<string, string> },
): Promise<Response> {
  return fetch(`${backendUrl()}${path}`, {
    method: init.method,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...init.headers,
    },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    cache: "no-store",
  });
}
```

- [ ] **Step 5: Install `server-only` and run the tests**

```bash
cd frontend && pnpm add server-only
pnpm vitest run src/server/session.test.ts
```

Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/server frontend/package.json frontend/pnpm-lock.yaml
git commit -m "feat(frontend): httpOnly refresh cookie helpers and backend caller"
```

---

## Task 10: BFF auth route handlers

**Files:**
- Create: `frontend/src/app/api/auth/login/route.ts`, `session/route.ts`, `refresh/route.ts`, `switch-org/route.ts`, `logout/route.ts`, `register/route.ts`, `password/forgot/route.ts`, `password/reset/route.ts`, `email/verify/route.ts`
- Create: `frontend/src/app/api/auth/_shared.ts`

- [ ] **Step 1: Create the shared helper `frontend/src/app/api/auth/_shared.ts`**

```ts
import "server-only";
import { NextResponse } from "next/server";
import { callBackend } from "@/server/backend";
import { setRefreshCookie } from "@/server/session";
import { authTokensSchema } from "@/lib/api/schemas";

/** Forwards a backend failure verbatim, preserving status, code and correlation id. */
export async function forwardFailure(response: Response): Promise<NextResponse> {
  const body = await response.text();
  return new NextResponse(body, {
    status: response.status,
    headers: { "content-type": response.headers.get("content-type") ?? "application/json" },
  });
}

/**
 * Splits an AuthTokens payload: the refresh token goes into the httpOnly cookie and
 * is stripped from the body, so it never reaches client JavaScript. Everything the
 * client legitimately needs — access token, user, memberships — is returned.
 */
export async function issueSession(backendResponse: Response): Promise<NextResponse> {
  if (!backendResponse.ok) return forwardFailure(backendResponse);

  const parsed = authTokensSchema.safeParse(await backendResponse.json());
  if (!parsed.success) {
    return NextResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 502,
        code: "SCHEMA_MISMATCH",
        message: "The authentication service returned an unexpected payload.",
        path: "/api/auth",
        correlationId: null,
      },
      { status: 502 },
    );
  }

  const { refreshToken, ...clientSafe } = parsed.data;
  const response = NextResponse.json(clientSafe);
  setRefreshCookie(response, refreshToken);
  return response;
}

/** Plain proxy for endpoints that involve no cookie at all. */
export async function proxy(request: Request, path: string): Promise<NextResponse> {
  const body = await request.json().catch(() => undefined);
  const backendResponse = await callBackend(path, { method: "POST", body });

  if (!backendResponse.ok) return forwardFailure(backendResponse);
  if (backendResponse.status === 204) return new NextResponse(null, { status: 204 });
  return NextResponse.json(await backendResponse.json());
}
```

- [ ] **Step 2: `frontend/src/app/api/auth/login/route.ts`**

```ts
import { callBackend } from "@/server/backend";
import { issueSession } from "../_shared";

export async function POST(request: Request) {
  const body = await request.json();
  return issueSession(await callBackend("/auth/login", { method: "POST", body }));
}
```

- [ ] **Step 3: `frontend/src/app/api/auth/session/route.ts`**

```ts
import { NextResponse } from "next/server";
import { callBackend } from "@/server/backend";
import { readRefreshToken } from "@/server/session";
import { issueSession } from "../_shared";

/**
 * Boot and hard-refresh path. The access token lives only in memory, so on a page
 * reload there is nothing to restore from — this exchanges the cookie for a fresh
 * one. Returns 401 rather than an error page when there is no cookie, because "not
 * signed in" is a normal answer here, not a failure.
 */
export async function POST() {
  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return NextResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 401,
        code: "UNAUTHENTICATED",
        message: "No active session.",
        path: "/api/auth/session",
        correlationId: null,
      },
      { status: 401 },
    );
  }
  return issueSession(
    await callBackend("/auth/refresh", { method: "POST", body: { refreshToken } }),
  );
}
```

- [ ] **Step 4: `frontend/src/app/api/auth/refresh/route.ts`**

Identical behaviour to `session` — the client calls this one on 401 and `session` on boot, and keeping them separate lets them diverge later (for example, telemetry on unexpected refreshes) without a flag.

```ts
import { NextResponse } from "next/server";
import { callBackend } from "@/server/backend";
import { readRefreshToken } from "@/server/session";
import { issueSession } from "../_shared";

export async function POST() {
  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return NextResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 401,
        code: "UNAUTHENTICATED",
        message: "No active session.",
        path: "/api/auth/refresh",
        correlationId: null,
      },
      { status: 401 },
    );
  }
  return issueSession(
    await callBackend("/auth/refresh", { method: "POST", body: { refreshToken } }),
  );
}
```

- [ ] **Step 5: `frontend/src/app/api/auth/switch-org/route.ts`**

```ts
import { NextResponse } from "next/server";
import { callBackend } from "@/server/backend";
import { readRefreshToken } from "@/server/session";
import { issueSession } from "../_shared";

/** Organization switching needs the refresh token, which the client cannot read. */
export async function POST(request: Request) {
  const { organizationId } = await request.json();
  const refreshToken = await readRefreshToken();

  if (!refreshToken) {
    return NextResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 401,
        code: "UNAUTHENTICATED",
        message: "No active session.",
        path: "/api/auth/switch-org",
        correlationId: null,
      },
      { status: 401 },
    );
  }

  return issueSession(
    await callBackend("/auth/switch-organization", {
      method: "POST",
      body: { refreshToken, organizationId },
    }),
  );
}
```

- [ ] **Step 6: `frontend/src/app/api/auth/logout/route.ts`**

```ts
import { NextResponse } from "next/server";
import { callBackend } from "@/server/backend";
import { clearRefreshCookie, readRefreshToken } from "@/server/session";

export async function POST() {
  const refreshToken = await readRefreshToken();

  // Best effort server-side revocation. Even if it fails, the cookie must go —
  // leaving a usable credential in the browser after the user asked to sign out
  // is worse than an orphaned server-side row.
  if (refreshToken) {
    await callBackend("/auth/logout", { method: "POST", body: { refreshToken } }).catch(
      () => undefined,
    );
  }

  const response = new NextResponse(null, { status: 204 });
  clearRefreshCookie(response);
  return response;
}
```

- [ ] **Step 7: The four passthroughs**

`frontend/src/app/api/auth/register/route.ts`:

```ts
import { proxy } from "../_shared";

export async function POST(request: Request) {
  return proxy(request, "/auth/register");
}
```

`frontend/src/app/api/auth/password/forgot/route.ts`:

```ts
import { proxy } from "../../_shared";

export async function POST(request: Request) {
  return proxy(request, "/auth/password/forgot");
}
```

`frontend/src/app/api/auth/password/reset/route.ts`:

```ts
import { proxy } from "../../_shared";

export async function POST(request: Request) {
  return proxy(request, "/auth/password/reset");
}
```

`frontend/src/app/api/auth/email/verify/route.ts`:

```ts
import { proxy } from "../../_shared";

export async function POST(request: Request) {
  return proxy(request, "/auth/email/verify");
}
```

Registration deliberately does **not** issue a session cookie even though the backend returns tokens: the user must verify their email, and the login page is where they land. Task 15's form calls `/api/auth/register` and then `/api/auth/login`.

- [ ] **Step 8: Verify against the live backend**

```bash
make infra && make backend   # in another terminal
cd frontend && pnpm dev      # in another terminal

curl -i -X POST http://localhost:3000/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"bff@example.com","password":"Str0ng!Passw0rd#2026","fullName":"BFF Test","organizationName":"BFF Org"}'
```

Expected: `201`.

```bash
curl -i -c /tmp/sq-cookies.txt -X POST http://localhost:3000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"bff@example.com","password":"Str0ng!Passw0rd#2026"}'
```

Expected: `200`; the response body contains `accessToken` and `user` but **no** `refreshToken`; the headers include `Set-Cookie: sq_rt=…; HttpOnly; SameSite=Lax`.

```bash
curl -i -b /tmp/sq-cookies.txt -X POST http://localhost:3000/api/auth/session
```

Expected: `200` with a *different* `accessToken` — proving rotation works end to end.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/api
git commit -m "feat(frontend): BFF auth handlers keeping the refresh token in an httpOnly cookie"
```

---

## Task 11: Route middleware

**Files:**
- Create: `frontend/src/middleware.ts`

- [ ] **Step 1: Create `frontend/src/middleware.ts`**

```ts
import { NextResponse, type NextRequest } from "next/server";
import { REFRESH_COOKIE } from "@/server/session";

const AUTH_PAGES = ["/login", "/register", "/forgot-password", "/reset-password"];

/**
 * Presence check only. Middleware cannot validate the token without a backend call
 * on every navigation, and it does not need to — the point here is to stop the
 * signed-out flash of an app page. Real validation is the first /api/auth/session
 * call, and every backend endpoint enforces auth independently.
 */
export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hasSession = Boolean(request.cookies.get(REFRESH_COOKIE)?.value);

  const isAuthPage = AUTH_PAGES.some((page) => pathname.startsWith(page));
  const isAppRoute = pathname.startsWith("/app/") || /^\/[^/]+\/(dashboard|projects|sprints|settings)/.test(pathname);

  if (isAppRoute && !hasSession) {
    const url = request.nextUrl.clone();
    url.pathname = "/login";
    // Preserve the destination so login can return the user where they were headed.
    url.search = `?next=${encodeURIComponent(pathname + search)}`;
    return NextResponse.redirect(url);
  }

  if (isAuthPage && hasSession) {
    const url = request.nextUrl.clone();
    url.pathname = "/";
    url.search = "";
    return NextResponse.redirect(url);
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    // Everything except Next internals, the API (which does its own auth) and static files.
    "/((?!api|_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|woff2?)$).*)",
  ],
};
```

- [ ] **Step 2: Verify manually**

With the dev server running and no cookie, visit `http://localhost:3000/acme/dashboard`.
Expected: redirected to `/login?next=%2Facme%2Fdashboard`.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/middleware.ts
git commit -m "feat(frontend): middleware gate for app routes"
```

---

## Task 12: Providers and UI store

**Files:**
- Create: `frontend/src/stores/ui-store.ts`, `frontend/src/components/providers.tsx`, `frontend/src/features/auth/session-context.tsx`
- Test: `frontend/src/stores/ui-store.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/stores/ui-store.test.ts`:

```ts
import { beforeEach, describe, expect, it } from "vitest";
import { useUiStore } from "./ui-store";

describe("useUiStore", () => {
  beforeEach(() => {
    useUiStore.setState({ sidebarCollapsed: false, commandPaletteOpen: false });
  });

  it("toggles the sidebar", () => {
    useUiStore.getState().toggleSidebar();
    expect(useUiStore.getState().sidebarCollapsed).toBe(true);
    useUiStore.getState().toggleSidebar();
    expect(useUiStore.getState().sidebarCollapsed).toBe(false);
  });

  it("opens and closes the command palette", () => {
    useUiStore.getState().setCommandPaletteOpen(true);
    expect(useUiStore.getState().commandPaletteOpen).toBe(true);
    useUiStore.getState().setCommandPaletteOpen(false);
    expect(useUiStore.getState().commandPaletteOpen).toBe(false);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `pnpm vitest run src/stores/ui-store.test.ts`
Expected: FAIL — cannot resolve `./ui-store`.

- [ ] **Step 3: Create `frontend/src/stores/ui-store.ts`**

```ts
import { create } from "zustand";
import { persist } from "zustand/middleware";

/**
 * Client-only UI state. Nothing here came from the server and nothing here is
 * server state — that all belongs to TanStack Query. Mixing the two is how a cache
 * ends up with two sources of truth that disagree after a mutation.
 */
interface UiState {
  sidebarCollapsed: boolean;
  commandPaletteOpen: boolean;
  toggleSidebar: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setCommandPaletteOpen: (open: boolean) => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      commandPaletteOpen: false,
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      setCommandPaletteOpen: (commandPaletteOpen) => set({ commandPaletteOpen }),
    }),
    {
      name: "sprintiq-ui",
      // The palette must never restore as open — a page that loads with a modal
      // over it looks broken.
      partialize: (state) => ({ sidebarCollapsed: state.sidebarCollapsed }),
    },
  ),
);
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/stores/ui-store.test.ts`
Expected: 2 passed.

- [ ] **Step 5: Create `frontend/src/features/auth/session-context.tsx`**

```tsx
"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { clientSessionSchema, type ClientSession } from "@/lib/api/schemas";
import { tokenStore } from "@/lib/api/token-store";
import { setActiveOrganizationId } from "@/lib/api/client";

interface SessionContextValue {
  session: ClientSession | null;
  status: "loading" | "authenticated" | "unauthenticated";
  setSession: (session: ClientSession) => void;
  signOut: () => Promise<void>;
  switchOrganization: (organizationId: string) => Promise<ClientSession>;
  hasPermission: (permission: string) => boolean;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/** Calls the BFF, which reads the httpOnly cookie the client cannot see. */
async function postJson(path: string, body?: unknown): Promise<unknown> {
  const response = await fetch(path, {
    method: "POST",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`${path} failed with ${response.status}`);
  return response.status === 204 ? undefined : response.json();
}

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [session, setSessionState] = useState<ClientSession | null>(null);
  const [status, setStatus] = useState<SessionContextValue["status"]>("loading");
  const router = useRouter();

  const applySession = useCallback((next: ClientSession) => {
    tokenStore.set(next.accessToken);
    setActiveOrganizationId(next.user.activeOrganizationId);
    setSessionState(next);
    setStatus("authenticated");
  }, []);

  // Boot: the access token lives only in memory, so a reload starts with nothing.
  // Exchange the cookie for a fresh one before rendering anything that needs auth.
  useEffect(() => {
    let cancelled = false;
    postJson("/api/auth/session")
      .then((data) => {
        if (cancelled) return;
        applySession(clientSessionSchema.parse(data));
      })
      .catch(() => {
        if (!cancelled) setStatus("unauthenticated");
      });
    return () => {
      cancelled = true;
    };
  }, [applySession]);

  // Wire the refresh queue to the BFF. Everything that 401s ends up here, and
  // TokenStore guarantees only one of them actually calls the endpoint.
  useEffect(() => {
    tokenStore.configure({
      refresh: async () => {
        const data = clientSessionSchema.parse(await postJson("/api/auth/refresh"));
        setActiveOrganizationId(data.user.activeOrganizationId);
        setSessionState(data);
        return data.accessToken;
      },
      onSessionExpired: () => {
        setSessionState(null);
        setStatus("unauthenticated");
        router.push("/login");
      },
    });
  }, [router]);

  const signOut = useCallback(async () => {
    await postJson("/api/auth/logout").catch(() => undefined);
    tokenStore.clear();
    setActiveOrganizationId(null);
    setSessionState(null);
    setStatus("unauthenticated");
    router.push("/login");
  }, [router]);

  const switchOrganization = useCallback(
    async (organizationId: string) => {
      const next = clientSessionSchema.parse(
        await postJson("/api/auth/switch-org", { organizationId }),
      );
      applySession(next);
      return next;
    },
    [applySession],
  );

  const hasPermission = useCallback(
    (permission: string) => session?.user.permissions.includes(permission) ?? false,
    [session],
  );

  const value = useMemo(
    () => ({ session, status, setSession: applySession, signOut, switchOrganization, hasPermission }),
    [session, status, applySession, signOut, switchOrganization, hasPermission],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext);
  if (!context) throw new Error("useSession must be used inside <SessionProvider>");
  return context;
}
```

- [ ] **Step 6: Create `frontend/src/components/providers.tsx`**

```tsx
"use client";

import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { Toaster } from "@/components/ui/sonner";
import { SessionProvider } from "@/features/auth/session-context";
import { ApiError } from "@/lib/api/errors";

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false,
            retry: (failureCount, error) => {
              // Never retry a deliberate refusal — a 403 will still be a 403 in
              // 200ms, and retrying it just delays the empty state.
              if (error instanceof ApiError && error.status < 500 && error.status !== 0) {
                return false;
              }
              return failureCount < 2;
            },
          },
          mutations: { retry: false },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <SessionProvider>
          {children}
          <Toaster position="bottom-right" />
        </SessionProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
```

`disableTransitionOnChange` stops every transitioned property on the page from animating at once when the theme flips, which reads as a flash rather than a transition.

- [ ] **Step 7: Mount providers in the root layout**

In `frontend/src/app/layout.tsx`, wrap the body content:

```tsx
import { Providers } from "@/components/providers";
// …
      <body className={`${inter.variable} font-sans antialiased`}>
        <Providers>{children}</Providers>
      </body>
```

- [ ] **Step 8: Typecheck, test and commit**

```bash
cd frontend && pnpm typecheck && pnpm test && pnpm build
git add frontend/src
git commit -m "feat(frontend): query client, session provider and UI store"
```

---

## Task 13: Auth feature schemas and hooks

**Files:**
- Create: `frontend/src/features/auth/schema.ts`, `frontend/src/features/auth/api.ts`, `frontend/src/features/auth/hooks.ts`
- Test: `frontend/src/features/auth/schema.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/auth/schema.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { loginSchema, registerSchema, resetPasswordSchema } from "./schema";

describe("registerSchema", () => {
  it("mirrors the backend password rule", () => {
    // Backend: ^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,128}$
    const base = {
      email: "a@b.com",
      fullName: "Ada Lovelace",
      organizationName: "Analytical Engines",
    };
    expect(registerSchema.safeParse({ ...base, password: "Str0ng!Passw0rd#2026" }).success).toBe(true);
    expect(registerSchema.safeParse({ ...base, password: "Sh0rt!Aa" }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: "nouppercase1!xxxx" }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: "NOLOWERCASE1!XXXX" }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: "NoDigitsHere!!!!" }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: "NoSymbol1234abcd" }).success).toBe(false);
  });

  it("rejects a malformed email", () => {
    const result = registerSchema.safeParse({
      email: "not-an-email",
      password: "Str0ng!Passw0rd#2026",
      fullName: "Ada Lovelace",
      organizationName: "Analytical Engines",
    });
    expect(result.success).toBe(false);
  });
});

describe("loginSchema", () => {
  it("requires an email and a non-empty password but does not re-check strength", () => {
    // Applying the strength rule at login would lock out anyone whose password
    // predates a rule change, and tell an attacker what the rule is.
    expect(loginSchema.safeParse({ email: "a@b.com", password: "short" }).success).toBe(true);
    expect(loginSchema.safeParse({ email: "a@b.com", password: "" }).success).toBe(false);
  });
});

describe("resetPasswordSchema", () => {
  it("requires the confirmation to match", () => {
    const ok = resetPasswordSchema.safeParse({
      token: "t",
      newPassword: "Str0ng!Passw0rd#2026",
      confirmPassword: "Str0ng!Passw0rd#2026",
    });
    const mismatch = resetPasswordSchema.safeParse({
      token: "t",
      newPassword: "Str0ng!Passw0rd#2026",
      confirmPassword: "Different!Passw0rd#26",
    });
    expect(ok.success).toBe(true);
    expect(mismatch.success).toBe(false);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && pnpm vitest run src/features/auth/schema.test.ts`
Expected: FAIL — cannot resolve `./schema`.

- [ ] **Step 3: Create `frontend/src/features/auth/schema.ts`**

```ts
import { z } from "zod";

/**
 * Copied deliberately from AuthDtos.PASSWORD_RULE. Duplicating a rule across the
 * stack is normally a smell, but the alternative here is that the user types a
 * password, waits for a round trip, and is then told it is too weak. The backend
 * remains the authority — this only moves the feedback earlier.
 */
const PASSWORD_RULE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,128}$/;
const PASSWORD_MESSAGE =
  "At least 12 characters, with lower case, upper case, a digit and a symbol";

export const passwordSchema = z.string().regex(PASSWORD_RULE, PASSWORD_MESSAGE);

export const loginSchema = z.object({
  email: z.string().email("Enter a valid email address"),
  password: z.string().min(1, "Enter your password"),
});

export const registerSchema = z.object({
  email: z.string().email("Enter a valid email address").max(255),
  password: passwordSchema,
  fullName: z.string().min(2, "Enter your full name").max(150),
  organizationName: z.string().min(2, "Enter your organization name").max(150),
});

export const forgotPasswordSchema = z.object({
  email: z.string().email("Enter a valid email address"),
});

export const resetPasswordSchema = z
  .object({
    token: z.string().min(1),
    newPassword: passwordSchema,
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
  });

export type LoginInput = z.infer<typeof loginSchema>;
export type RegisterInput = z.infer<typeof registerSchema>;
export type ForgotPasswordInput = z.infer<typeof forgotPasswordSchema>;
export type ResetPasswordInput = z.infer<typeof resetPasswordSchema>;
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/features/auth/schema.test.ts`
Expected: 4 passed.

- [ ] **Step 5: Create `frontend/src/features/auth/api.ts`**

```ts
import { clientSessionSchema, type ClientSession } from "@/lib/api/schemas";
import { ApiError, toApiError, toNetworkError } from "@/lib/api/errors";
import type { ForgotPasswordInput, LoginInput, RegisterInput, ResetPasswordInput } from "./schema";

/**
 * Auth calls go to the BFF, not the backend — the refresh cookie only exists there.
 * Everything else in the app uses `api` from lib/api/client.
 */
async function post<T>(path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: body ? { "Content-Type": "application/json" } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (cause) {
    throw toNetworkError(cause);
  }
  if (!response.ok) throw await toApiError(response);
  return (response.status === 204 ? undefined : await response.json()) as T;
}

export const authApi = {
  login: async (input: LoginInput): Promise<ClientSession> =>
    clientSessionSchema.parse(await post("/api/auth/login", input)),

  register: async (input: RegisterInput): Promise<void> => {
    await post("/api/auth/register", { ...input, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone });
  },

  forgotPassword: (input: ForgotPasswordInput) => post<void>("/api/auth/password/forgot", input),

  resetPassword: (input: Omit<ResetPasswordInput, "confirmPassword">) =>
    post<void>("/api/auth/password/reset", input),

  verifyEmail: (token: string) => post<void>("/api/auth/email/verify", { token }),
};

export { ApiError };
```

- [ ] **Step 6: Create `frontend/src/features/auth/hooks.ts`**

```ts
"use client";

import { useMutation } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import type { UseFormSetError, FieldValues, Path } from "react-hook-form";
import { authApi } from "./api";
import { ApiError } from "@/lib/api/errors";
import { useSession } from "./session-context";

/**
 * Puts a backend 422 onto the inputs that caused it. Anything the form does not
 * have a field for falls back to a toast, so a validation error can never vanish.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  knownFields: readonly string[],
): boolean {
  if (!(error instanceof ApiError) || !error.isValidation) return false;

  let handled = false;
  for (const [field, message] of Object.entries(error.fieldErrorMap())) {
    if (knownFields.includes(field)) {
      setError(field as Path<T>, { type: "server", message });
      handled = true;
    }
  }
  return handled;
}

export function reportError(error: unknown): void {
  if (error instanceof ApiError) {
    toast.error(error.message, {
      description: error.correlationId ? `Reference: ${error.correlationId}` : undefined,
    });
    return;
  }
  toast.error("Something went wrong. Please try again.");
}

export function useLogin() {
  const { setSession } = useSession();
  const router = useRouter();
  const params = useSearchParams();

  return useMutation({
    mutationFn: authApi.login,
    onSuccess: (session) => {
      setSession(session);
      const next = params.get("next");
      const slug = session.user.organizations[0]?.slug;
      router.push(next ?? (slug ? `/${slug}/dashboard` : "/"));
    },
  });
}

export function useRegister() {
  const router = useRouter();
  return useMutation({
    mutationFn: authApi.register,
    onSuccess: () => {
      toast.success("Account created. Check your email to verify it.");
      router.push("/login");
    },
  });
}

export function useForgotPassword() {
  return useMutation({ mutationFn: authApi.forgotPassword });
}

export function useResetPassword() {
  const router = useRouter();
  return useMutation({
    mutationFn: authApi.resetPassword,
    onSuccess: () => {
      toast.success("Password updated. Sign in with your new password.");
      router.push("/login");
    },
  });
}
```

- [ ] **Step 7: Typecheck and commit**

```bash
cd frontend && pnpm typecheck && pnpm test
git add frontend/src/features/auth
git commit -m "feat(frontend): auth schemas, BFF client and mutation hooks"
```

---

## Task 14: Auth layout

**Files:**
- Create: `frontend/src/app/(auth)/layout.tsx`, `frontend/src/components/brand/logo.tsx`

- [ ] **Step 1: Create `frontend/src/components/brand/logo.tsx`**

```tsx
import { cn } from "@/lib/utils";

export function Logo({ className, showWordmark = true }: { className?: string; showWordmark?: boolean }) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <span
        aria-hidden
        className="grid size-7 place-items-center rounded-md bg-primary text-primary-foreground font-semibold text-sm"
      >
        S
      </span>
      {showWordmark && <span className="font-semibold tracking-tight">SprintIQ</span>}
    </span>
  );
}
```

- [ ] **Step 2: Create `frontend/src/app/(auth)/layout.tsx`**

```tsx
import Link from "next/link";
import { Logo } from "@/components/brand/logo";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-2">
      <div className="flex flex-col px-6 py-10 sm:px-12">
        <Link href="/" className="mb-auto">
          <Logo />
        </Link>
        <main className="mx-auto w-full max-w-sm py-12">{children}</main>
        <p className="mt-auto text-xs text-muted-foreground">
          &copy; {new Date().getFullYear()} SprintIQ
        </p>
      </div>

      {/* Decorative panel. aria-hidden so a screen reader is not read a gradient. */}
      <aside
        aria-hidden
        className="relative hidden overflow-hidden border-l bg-secondary/40 lg:block"
      >
        <div className="absolute inset-0 bg-[radial-gradient(60%_50%_at_70%_20%,var(--primary)_0%,transparent_60%)] opacity-[0.12]" />
        <div className="absolute inset-0 [background-image:linear-gradient(to_right,var(--border)_1px,transparent_1px),linear-gradient(to_bottom,var(--border)_1px,transparent_1px)] [background-size:32px_32px] opacity-40" />
        <div className="relative flex h-full flex-col justify-end p-12">
          <blockquote className="max-w-md">
            <p className="text-xl font-medium leading-snug text-balance">
              Every prediction attached to a decision you are about to make — never a
              floating number on a dashboard.
            </p>
            <footer className="mt-4 text-sm text-muted-foreground">
              The idea behind SprintIQ
            </footer>
          </blockquote>
        </div>
      </aside>
    </div>
  );
}
```

- [ ] **Step 3: Verify build and commit**

```bash
cd frontend && pnpm build
git add frontend/src
git commit -m "feat(frontend): auth layout"
```

---

## Task 15: Login and register pages

**Files:**
- Create: `frontend/src/app/(auth)/login/page.tsx`, `frontend/src/app/(auth)/register/page.tsx`
- Create: `frontend/src/features/auth/components/login-form.tsx`, `register-form.tsx`, `submit-button.tsx`
- Test: `frontend/src/features/auth/components/login-form.test.tsx`

- [ ] **Step 1: Create the shared submit button `frontend/src/features/auth/components/submit-button.tsx`**

```tsx
"use client";

import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";

export function SubmitButton({
  pending,
  children,
}: {
  pending: boolean;
  children: React.ReactNode;
}) {
  return (
    <Button type="submit" className="w-full" disabled={pending}>
      {/* aria-hidden on the spinner; the accessible state comes from aria-busy. */}
      {pending && <Loader2 aria-hidden className="mr-2 size-4 animate-spin" />}
      <span aria-busy={pending}>{children}</span>
    </Button>
  );
}
```

- [ ] **Step 2: Write the failing test**

Create `frontend/src/features/auth/components/login-form.test.tsx`:

```tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LoginForm } from "./login-form";

const mutate = vi.fn();
let isPending = false;

vi.mock("../hooks", async () => {
  const actual = await vi.importActual<typeof import("../hooks")>("../hooks");
  return {
    ...actual,
    useLogin: () => ({ mutate, isPending, error: null }),
  };
});

describe("LoginForm", () => {
  beforeEach(() => {
    mutate.mockClear();
    isPending = false;
  });

  it("blocks submission and shows a message when the email is malformed", async () => {
    const user = userEvent.setup();
    render(<LoginForm />);

    await user.type(screen.getByLabelText(/email/i), "not-an-email");
    await user.type(screen.getByLabelText(/password/i), "anything");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText(/valid email/i)).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
  });

  it("submits valid credentials", async () => {
    const user = userEvent.setup();
    render(<LoginForm />);

    await user.type(screen.getByLabelText(/email/i), "ada@example.com");
    await user.type(screen.getByLabelText(/password/i), "Str0ng!Passw0rd#2026");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() =>
      expect(mutate).toHaveBeenCalledWith(
        { email: "ada@example.com", password: "Str0ng!Passw0rd#2026" },
        expect.anything(),
      ),
    );
  });
});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `pnpm vitest run src/features/auth/components/login-form.test.tsx`
Expected: FAIL — cannot resolve `./login-form`.

- [ ] **Step 4: Create `frontend/src/features/auth/components/login-form.tsx`**

```tsx
"use client";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { loginSchema, type LoginInput } from "../schema";
import { applyFieldErrors, reportError, useLogin } from "../hooks";
import { SubmitButton } from "./submit-button";

const FIELDS = ["email", "password"] as const;

export function LoginForm() {
  const login = useLogin();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<LoginInput>({ resolver: zodResolver(loginSchema) });

  const onSubmit = handleSubmit((values) => {
    login.mutate(values, {
      onError: (error) => {
        if (!applyFieldErrors(error, setError, FIELDS)) reportError(error);
      },
    });
  });

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">Sign in</h1>
        <p className="text-sm text-muted-foreground">Welcome back to SprintIQ.</p>
      </header>

      <div className="space-y-2">
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          autoFocus
          aria-invalid={Boolean(errors.email)}
          aria-describedby={errors.email ? "email-error" : undefined}
          {...register("email")}
        />
        {errors.email && (
          <p id="email-error" role="alert" className="text-xs text-destructive">
            {errors.email.message}
          </p>
        )}
      </div>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <Label htmlFor="password">Password</Label>
          <Link
            href="/forgot-password"
            className="text-xs text-muted-foreground underline-offset-4 hover:underline"
          >
            Forgot password?
          </Link>
        </div>
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          aria-invalid={Boolean(errors.password)}
          aria-describedby={errors.password ? "password-error" : undefined}
          {...register("password")}
        />
        {errors.password && (
          <p id="password-error" role="alert" className="text-xs text-destructive">
            {errors.password.message}
          </p>
        )}
      </div>

      <SubmitButton pending={login.isPending}>Sign in</SubmitButton>

      <p className="text-center text-sm text-muted-foreground">
        No account?{" "}
        <Link href="/register" className="text-foreground underline-offset-4 hover:underline">
          Create one
        </Link>
      </p>
    </form>
  );
}
```

- [ ] **Step 5: Run the tests**

Run: `pnpm vitest run src/features/auth/components/login-form.test.tsx`
Expected: 2 passed.

- [ ] **Step 6: Create `frontend/src/features/auth/components/register-form.tsx`**

```tsx
"use client";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { registerSchema, type RegisterInput } from "../schema";
import { applyFieldErrors, reportError, useRegister } from "../hooks";
import { SubmitButton } from "./submit-button";

const FIELDS = ["email", "password", "fullName", "organizationName"] as const;

export function RegisterForm() {
  const signUp = useRegister();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RegisterInput>({ resolver: zodResolver(registerSchema) });

  const onSubmit = handleSubmit((values) => {
    signUp.mutate(values, {
      onError: (error) => {
        if (!applyFieldErrors(error, setError, FIELDS)) reportError(error);
      },
    });
  });

  const field = (
    name: keyof RegisterInput,
    label: string,
    props: React.ComponentProps<typeof Input>,
  ) => (
    <div className="space-y-2">
      <Label htmlFor={name}>{label}</Label>
      <Input
        id={name}
        aria-invalid={Boolean(errors[name])}
        aria-describedby={errors[name] ? `${name}-error` : undefined}
        {...props}
        {...register(name)}
      />
      {errors[name] && (
        <p id={`${name}-error`} role="alert" className="text-xs text-destructive">
          {errors[name]?.message}
        </p>
      )}
    </div>
  );

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">Create your workspace</h1>
        <p className="text-sm text-muted-foreground">
          Free to start. No card required.
        </p>
      </header>

      {field("fullName", "Full name", { autoComplete: "name", autoFocus: true })}
      {field("organizationName", "Organization", { autoComplete: "organization" })}
      {field("email", "Work email", { type: "email", autoComplete: "email" })}
      {field("password", "Password", { type: "password", autoComplete: "new-password" })}

      <p className="text-xs text-muted-foreground">
        At least 12 characters, with lower case, upper case, a digit and a symbol.
      </p>

      <SubmitButton pending={signUp.isPending}>Create account</SubmitButton>

      <p className="text-center text-sm text-muted-foreground">
        Already have an account?{" "}
        <Link href="/login" className="text-foreground underline-offset-4 hover:underline">
          Sign in
        </Link>
      </p>
    </form>
  );
}
```

- [ ] **Step 7: Create the two pages**

`frontend/src/app/(auth)/login/page.tsx`:

```tsx
import { Suspense } from "react";
import type { Metadata } from "next";
import { LoginForm } from "@/features/auth/components/login-form";

export const metadata: Metadata = { title: "Sign in — SprintIQ" };

export default function LoginPage() {
  // useSearchParams (for ?next=) requires a Suspense boundary during prerender.
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}
```

`frontend/src/app/(auth)/register/page.tsx`:

```tsx
import type { Metadata } from "next";
import { RegisterForm } from "@/features/auth/components/register-form";

export const metadata: Metadata = { title: "Create your workspace — SprintIQ" };

export default function RegisterPage() {
  return <RegisterForm />;
}
```

- [ ] **Step 8: Verify against the live backend**

With `make backend` and `pnpm dev` running, visit `http://localhost:3000/register`, create an account, then sign in at `/login`.
Expected: registration succeeds, login redirects to `/<org-slug>/dashboard` (404 until Task 19 — that is fine, the URL is what matters here).

- [ ] **Step 9: Commit**

```bash
cd frontend && pnpm typecheck && pnpm test
git add frontend/src
git commit -m "feat(frontend): login and register pages with server-driven field errors"
```

---

## Task 16: Password reset and email verification pages

**Files:**
- Create: `frontend/src/app/(auth)/forgot-password/page.tsx`, `reset-password/page.tsx`, `verify-email/page.tsx`
- Create: `frontend/src/features/auth/components/forgot-password-form.tsx`, `reset-password-form.tsx`, `verify-email-panel.tsx`

- [ ] **Step 1: Create `frontend/src/features/auth/components/forgot-password-form.tsx`**

```tsx
"use client";

import { useState } from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { forgotPasswordSchema, type ForgotPasswordInput } from "../schema";
import { reportError, useForgotPassword } from "../hooks";
import { SubmitButton } from "./submit-button";

export function ForgotPasswordForm() {
  const [sent, setSent] = useState(false);
  const forgot = useForgotPassword();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ForgotPasswordInput>({ resolver: zodResolver(forgotPasswordSchema) });

  const onSubmit = handleSubmit((values) => {
    forgot.mutate(values, {
      // The backend answers 204 whether or not the address exists, so the UI must
      // say the same thing either way — anything else turns this into an account
      // enumeration oracle.
      onSuccess: () => setSent(true),
      onError: (error) => reportError(error),
    });
  });

  if (sent) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold tracking-tight">Check your email</h1>
        <p className="text-sm text-muted-foreground">
          If an account exists for that address, a reset link is on its way. The link
          expires in one hour.
        </p>
        <Link href="/login" className="text-sm underline-offset-4 hover:underline">
          Back to sign in
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">Reset your password</h1>
        <p className="text-sm text-muted-foreground">
          We&apos;ll email you a link to choose a new one.
        </p>
      </header>

      <div className="space-y-2">
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          autoFocus
          aria-invalid={Boolean(errors.email)}
          {...register("email")}
        />
        {errors.email && (
          <p role="alert" className="text-xs text-destructive">
            {errors.email.message}
          </p>
        )}
      </div>

      <SubmitButton pending={forgot.isPending}>Send reset link</SubmitButton>

      <p className="text-center text-sm text-muted-foreground">
        <Link href="/login" className="underline-offset-4 hover:underline">
          Back to sign in
        </Link>
      </p>
    </form>
  );
}
```

- [ ] **Step 2: Create `frontend/src/features/auth/components/reset-password-form.tsx`**

```tsx
"use client";

import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { resetPasswordSchema, type ResetPasswordInput } from "../schema";
import { applyFieldErrors, reportError, useResetPassword } from "../hooks";
import { SubmitButton } from "./submit-button";

export function ResetPasswordForm() {
  const token = useSearchParams().get("token") ?? "";
  const reset = useResetPassword();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ResetPasswordInput>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { token },
  });

  if (!token) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold tracking-tight">Link is incomplete</h1>
        <p className="text-sm text-muted-foreground">
          This reset link is missing its token. Request a new one.
        </p>
        <Link href="/forgot-password" className="text-sm underline-offset-4 hover:underline">
          Request a new link
        </Link>
      </div>
    );
  }

  const onSubmit = handleSubmit(({ confirmPassword: _ignored, ...values }) => {
    reset.mutate(values, {
      onError: (error) => {
        if (!applyFieldErrors(error, setError, ["newPassword"])) reportError(error);
      },
    });
  });

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <input type="hidden" {...register("token")} />

      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">Choose a new password</h1>
      </header>

      <div className="space-y-2">
        <Label htmlFor="newPassword">New password</Label>
        <Input
          id="newPassword"
          type="password"
          autoComplete="new-password"
          autoFocus
          aria-invalid={Boolean(errors.newPassword)}
          {...register("newPassword")}
        />
        {errors.newPassword && (
          <p role="alert" className="text-xs text-destructive">
            {errors.newPassword.message}
          </p>
        )}
      </div>

      <div className="space-y-2">
        <Label htmlFor="confirmPassword">Confirm password</Label>
        <Input
          id="confirmPassword"
          type="password"
          autoComplete="new-password"
          aria-invalid={Boolean(errors.confirmPassword)}
          {...register("confirmPassword")}
        />
        {errors.confirmPassword && (
          <p role="alert" className="text-xs text-destructive">
            {errors.confirmPassword.message}
          </p>
        )}
      </div>

      <SubmitButton pending={reset.isPending}>Update password</SubmitButton>
    </form>
  );
}
```

- [ ] **Step 3: Create `frontend/src/features/auth/components/verify-email-panel.tsx`**

```tsx
"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { CheckCircle2, XCircle, Loader2 } from "lucide-react";
import { authApi } from "../api";

export function VerifyEmailPanel() {
  const token = useSearchParams().get("token") ?? "";
  const [state, setState] = useState<"pending" | "done" | "failed">("pending");
  // Guards against React 18+ StrictMode double-invoking the effect and consuming
  // the single-use token twice — the second call would legitimately fail.
  const attempted = useRef(false);

  useEffect(() => {
    if (!token || attempted.current) {
      if (!token) setState("failed");
      return;
    }
    attempted.current = true;
    authApi
      .verifyEmail(token)
      .then(() => setState("done"))
      .catch(() => setState("failed"));
  }, [token]);

  if (state === "pending") {
    return (
      <div className="flex items-center gap-3 text-sm text-muted-foreground">
        <Loader2 aria-hidden className="size-4 animate-spin" />
        <span role="status">Verifying your email…</span>
      </div>
    );
  }

  const done = state === "done";
  return (
    <div className="space-y-4">
      {done ? (
        <CheckCircle2 aria-hidden className="size-8 text-status-done" />
      ) : (
        <XCircle aria-hidden className="size-8 text-destructive" />
      )}
      <h1 className="text-2xl font-semibold tracking-tight">
        {done ? "Email verified" : "That link did not work"}
      </h1>
      <p className="text-sm text-muted-foreground">
        {done
          ? "Your address is confirmed. You can sign in now."
          : "The link may have expired or already been used. Sign in and request a new one."}
      </p>
      <Link href="/login" className="text-sm underline-offset-4 hover:underline">
        Go to sign in
      </Link>
    </div>
  );
}
```

- [ ] **Step 4: Create the three pages**

`frontend/src/app/(auth)/forgot-password/page.tsx`:

```tsx
import type { Metadata } from "next";
import { ForgotPasswordForm } from "@/features/auth/components/forgot-password-form";

export const metadata: Metadata = { title: "Reset password — SprintIQ" };

export default function ForgotPasswordPage() {
  return <ForgotPasswordForm />;
}
```

`frontend/src/app/(auth)/reset-password/page.tsx`:

```tsx
import { Suspense } from "react";
import type { Metadata } from "next";
import { ResetPasswordForm } from "@/features/auth/components/reset-password-form";

export const metadata: Metadata = { title: "Choose a new password — SprintIQ" };

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetPasswordForm />
    </Suspense>
  );
}
```

`frontend/src/app/(auth)/verify-email/page.tsx`:

```tsx
import { Suspense } from "react";
import type { Metadata } from "next";
import { VerifyEmailPanel } from "@/features/auth/components/verify-email-panel";

export const metadata: Metadata = { title: "Verify email — SprintIQ" };

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={null}>
      <VerifyEmailPanel />
    </Suspense>
  );
}
```

- [ ] **Step 5: Verify the reset round trip**

Trigger a reset from `/forgot-password`, open Mailpit at `http://localhost:8025`, follow the link, and set a new password. Sign in with it.
Expected: the whole loop works, and the confirm-mismatch case shows an inline error.

- [ ] **Step 6: Commit**

```bash
cd frontend && pnpm typecheck && pnpm test && pnpm build
git add frontend/src
git commit -m "feat(frontend): password reset and email verification pages"
```

---

## Task 17: Organization slug resolution

**Files:**
- Create: `frontend/src/features/organizations/resolve-org.ts`
- Test: `frontend/src/features/organizations/resolve-org.test.ts`

The URL carries a slug; the backend wants a UUID. `AuthenticatedUser.organizations[]` already has both, so this is a lookup rather than a fetch.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/organizations/resolve-org.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { resolveOrganization } from "./resolve-org";
import type { OrganizationSummary } from "@/lib/api/schemas";

const orgs: OrganizationSummary[] = [
  { id: "id-acme", name: "Acme Robotics", slug: "acme-robotics", logoUrl: null, roleCode: "OWNER" },
  { id: "id-globex", name: "Globex Media", slug: "globex-media", logoUrl: null, roleCode: "MEMBER" },
];

describe("resolveOrganization", () => {
  it("matches a slug to its organization", () => {
    expect(resolveOrganization(orgs, "globex-media")).toEqual({
      status: "found",
      organization: orgs[1],
    });
  });

  it("reports an unknown slug rather than silently falling back", () => {
    // Falling back to the first org would show someone another tenant's dashboard
    // under a URL that says otherwise — confusing at best, alarming at worst.
    expect(resolveOrganization(orgs, "not-mine")).toEqual({ status: "not-a-member" });
  });

  it("reports when the user belongs to no organization at all", () => {
    expect(resolveOrganization([], "anything")).toEqual({ status: "no-organizations" });
  });

  it("is case-insensitive, because URLs get typed by hand", () => {
    expect(resolveOrganization(orgs, "ACME-Robotics")).toEqual({
      status: "found",
      organization: orgs[0],
    });
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && pnpm vitest run src/features/organizations/resolve-org.test.ts`
Expected: FAIL — cannot resolve `./resolve-org`.

- [ ] **Step 3: Create `frontend/src/features/organizations/resolve-org.ts`**

```ts
import type { OrganizationSummary } from "@/lib/api/schemas";

export type OrganizationResolution =
  | { status: "found"; organization: OrganizationSummary }
  | { status: "not-a-member" }
  | { status: "no-organizations" };

/**
 * Maps the `[orgSlug]` route segment onto the organization the backend knows about.
 *
 * Deliberately does not fall back to "the first organization" on a miss: rendering
 * another tenant's data under a URL naming a different one is worse than an error,
 * and it is the kind of bug nobody notices until it matters.
 */
export function resolveOrganization(
  organizations: OrganizationSummary[],
  slug: string,
): OrganizationResolution {
  if (organizations.length === 0) return { status: "no-organizations" };

  const normalized = slug.trim().toLowerCase();
  const organization = organizations.find((org) => org.slug.toLowerCase() === normalized);

  return organization ? { status: "found", organization } : { status: "not-a-member" };
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/features/organizations/resolve-org.test.ts`
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/organizations
git commit -m "feat(frontend): resolve the org slug without falling back across tenants"
```

---

## Task 18: App shell layout

**Files:**
- Create: `frontend/src/app/(app)/[orgSlug]/layout.tsx`
- Create: `frontend/src/components/layout/app-shell.tsx`, `sidebar.tsx`, `topbar.tsx`, `org-switcher.tsx`, `user-menu.tsx`, `theme-toggle.tsx`
- Create: `frontend/src/components/feedback/empty-state.tsx`

- [ ] **Step 1: Create `frontend/src/components/feedback/empty-state.tsx`**

```tsx
import { cn } from "@/lib/utils";

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon?: React.ComponentType<{ className?: string }>;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center px-6 py-16 text-center", className)}>
      {Icon && <Icon className="mb-4 size-8 text-muted-foreground" />}
      <h2 className="text-base font-medium">{title}</h2>
      {description && (
        <p className="mt-1 max-w-sm text-sm text-muted-foreground text-balance">{description}</p>
      )}
      {action && <div className="mt-6">{action}</div>}
    </div>
  );
}
```

- [ ] **Step 2: Create `frontend/src/components/layout/org-switcher.tsx`**

```tsx
"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Check, ChevronsUpDown, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useSession } from "@/features/auth/session-context";
import { reportError } from "@/features/auth/hooks";
import { cn } from "@/lib/utils";

export function OrgSwitcher({ collapsed }: { collapsed: boolean }) {
  const { session, switchOrganization } = useSession();
  const router = useRouter();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [, startTransition] = useTransition();

  const organizations = session?.user.organizations ?? [];
  const activeId = session?.user.activeOrganizationId;
  const active = organizations.find((org) => org.id === activeId) ?? organizations[0];

  if (!active) return null;

  const onSelect = async (id: string, slug: string) => {
    if (id === activeId) return;
    setBusyId(id);
    try {
      // Switching mints a new token pair with a different org claim, so the route
      // must change only after the session has actually swapped — otherwise the
      // first request on the new page still carries the old organization.
      await switchOrganization(id);
      startTransition(() => router.push(`/${slug}/dashboard`));
    } catch (error) {
      reportError(error);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          className={cn("w-full justify-between gap-2 px-2", collapsed && "px-0 justify-center")}
          aria-label={`Current organization: ${active.name}. Switch organization`}
        >
          <span className="flex min-w-0 items-center gap-2">
            <span
              aria-hidden
              className="grid size-6 shrink-0 place-items-center rounded bg-primary/10 text-xs font-semibold text-primary"
            >
              {active.name.charAt(0).toUpperCase()}
            </span>
            {!collapsed && <span className="truncate text-sm font-medium">{active.name}</span>}
          </span>
          {!collapsed && <ChevronsUpDown aria-hidden className="size-4 shrink-0 opacity-50" />}
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="w-64">
        <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
          Organizations
        </DropdownMenuLabel>
        {organizations.map((org) => (
          <DropdownMenuItem
            key={org.id}
            onSelect={(event) => {
              event.preventDefault();
              void onSelect(org.id, org.slug);
            }}
            className="gap-2"
          >
            <span className="grid size-6 shrink-0 place-items-center rounded bg-muted text-xs font-semibold">
              {org.name.charAt(0).toUpperCase()}
            </span>
            <span className="min-w-0 flex-1 truncate">{org.name}</span>
            <span className="text-2xs uppercase tracking-wide text-muted-foreground">
              {org.roleCode}
            </span>
            {busyId === org.id ? (
              <Loader2 aria-hidden className="size-4 animate-spin" />
            ) : (
              org.id === activeId && <Check aria-hidden className="size-4" />
            )}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
```

- [ ] **Step 3: Create `frontend/src/components/layout/sidebar.tsx`**

```tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  FolderKanban,
  CalendarRange,
  ListTodo,
  BarChart3,
  Users,
  Sparkles,
  Settings,
  PanelLeftClose,
  PanelLeft,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { OrgSwitcher } from "./org-switcher";
import { useUiStore } from "@/stores/ui-store";
import { cn } from "@/lib/utils";

/**
 * The full navigation is present from the start even though only Dashboard has a
 * page in this phase — the shell's shape is what is being reviewed, and adding
 * items later would change every screenshot. Unbuilt destinations are marked and
 * not clickable rather than 404ing.
 */
const NAV = [
  { href: "dashboard", label: "Dashboard", icon: LayoutDashboard, ready: true },
  { href: "projects", label: "Projects", icon: FolderKanban, ready: false },
  { href: "sprints", label: "Sprints", icon: CalendarRange, ready: false },
  { href: "backlog", label: "Backlog", icon: ListTodo, ready: false },
  { href: "ai-insights", label: "AI insights", icon: Sparkles, ready: false },
  { href: "reports", label: "Reports", icon: BarChart3, ready: false },
  { href: "team", label: "Team", icon: Users, ready: false },
  { href: "settings", label: "Settings", icon: Settings, ready: false },
] as const;

export function Sidebar({ orgSlug }: { orgSlug: string }) {
  const pathname = usePathname();
  const { sidebarCollapsed, toggleSidebar } = useUiStore();

  return (
    <aside
      className={cn(
        "flex h-dvh shrink-0 flex-col border-r bg-card transition-[width] duration-200 ease-[var(--ease-out-quart)]",
        sidebarCollapsed ? "w-[60px]" : "w-60",
      )}
    >
      <div className="p-2">
        <OrgSwitcher collapsed={sidebarCollapsed} />
      </div>

      <nav aria-label="Main" className="flex-1 space-y-0.5 overflow-y-auto p-2">
        {NAV.map(({ href, label, icon: Icon, ready }) => {
          const target = `/${orgSlug}/${href}`;
          const active = pathname === target;

          const content = (
            <span
              className={cn(
                "flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm transition-colors",
                active
                  ? "bg-accent font-medium text-accent-foreground"
                  : "text-muted-foreground",
                ready ? "hover:bg-accent hover:text-accent-foreground" : "cursor-not-allowed opacity-45",
                sidebarCollapsed && "justify-center px-0",
              )}
            >
              <Icon aria-hidden className="size-4 shrink-0" />
              {!sidebarCollapsed && <span className="truncate">{label}</span>}
            </span>
          );

          const item = ready ? (
            <Link key={href} href={target} aria-current={active ? "page" : undefined}>
              {content}
            </Link>
          ) : (
            <span key={href} aria-disabled="true" title={`${label} — coming soon`}>
              {content}
            </span>
          );

          // A collapsed rail is unusable without labels on hover.
          return sidebarCollapsed ? (
            <Tooltip key={href}>
              <TooltipTrigger asChild>{item}</TooltipTrigger>
              <TooltipContent side="right">{label}</TooltipContent>
            </Tooltip>
          ) : (
            item
          );
        })}
      </nav>

      <div className="border-t p-2">
        <Button
          variant="ghost"
          size="sm"
          onClick={toggleSidebar}
          className={cn("w-full justify-start gap-2.5", sidebarCollapsed && "justify-center")}
          aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
        >
          {sidebarCollapsed ? (
            <PanelLeft aria-hidden className="size-4" />
          ) : (
            <>
              <PanelLeftClose aria-hidden className="size-4" />
              <span className="text-sm">Collapse</span>
            </>
          )}
        </Button>
      </div>
    </aside>
  );
}
```

- [ ] **Step 4: Create `frontend/src/components/layout/theme-toggle.tsx`**

```tsx
"use client";

import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { Moon, Sun } from "lucide-react";
import { Button } from "@/components/ui/button";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  // The server cannot know the resolved theme, so rendering the icon before mount
  // guarantees a hydration mismatch. A fixed-size placeholder also stops the
  // topbar shifting when the real button appears.
  useEffect(() => setMounted(true), []);
  if (!mounted) return <div className="size-9" aria-hidden />;

  const dark = resolvedTheme === "dark";
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={() => setTheme(dark ? "light" : "dark")}
      aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
    >
      {dark ? <Sun aria-hidden className="size-4" /> : <Moon aria-hidden className="size-4" />}
    </Button>
  );
}
```

- [ ] **Step 5: Create `frontend/src/components/layout/user-menu.tsx`**

```tsx
"use client";

import { LogOut, User as UserIcon } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useSession } from "@/features/auth/session-context";

const initials = (name: string) =>
  name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join("");

export function UserMenu() {
  const { session, signOut } = useSession();
  const user = session?.user;
  if (!user) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="rounded-full" aria-label="Account menu">
          <Avatar className="size-7">
            {user.avatarUrl && <AvatarImage src={user.avatarUrl} alt="" />}
            <AvatarFallback className="text-xs">{initials(user.fullName)}</AvatarFallback>
          </Avatar>
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="font-normal">
          <p className="text-sm font-medium">{user.fullName}</p>
          <p className="truncate text-xs text-muted-foreground">{user.email}</p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem disabled className="gap-2">
          <UserIcon aria-hidden className="size-4" />
          Profile settings
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void signOut()} className="gap-2">
          <LogOut aria-hidden className="size-4" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
```

- [ ] **Step 6: Create `frontend/src/components/layout/topbar.tsx`**

```tsx
"use client";

import { Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { UserMenu } from "./user-menu";
import { ThemeToggle } from "./theme-toggle";
import { useUiStore } from "@/stores/ui-store";

export function Topbar({ title }: { title: string }) {
  const setCommandPaletteOpen = useUiStore((s) => s.setCommandPaletteOpen);

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b px-4">
      <h1 className="text-sm font-medium">{title}</h1>

      <Button
        variant="outline"
        size="sm"
        onClick={() => setCommandPaletteOpen(true)}
        className="ml-auto h-8 w-56 justify-start gap-2 px-2 text-muted-foreground font-normal"
      >
        <Search aria-hidden className="size-3.5" />
        <span className="text-xs">Search…</span>
        <kbd className="ml-auto rounded border bg-muted px-1.5 font-mono text-2xs">⌘K</kbd>
      </Button>

      <ThemeToggle />
      <UserMenu />
    </header>
  );
}
```

- [ ] **Step 7: Create `frontend/src/components/layout/app-shell.tsx`**

```tsx
"use client";

import Link from "next/link";
import { Building2, Loader2 } from "lucide-react";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Button } from "@/components/ui/button";
import { Sidebar } from "./sidebar";
import { Topbar } from "./topbar";
import { CommandPalette } from "./command-palette";
import { EmptyState } from "@/components/feedback/empty-state";
import { useSession } from "@/features/auth/session-context";
import { resolveOrganization } from "@/features/organizations/resolve-org";

export function AppShell({
  orgSlug,
  title,
  children,
}: {
  orgSlug: string;
  title: string;
  children: React.ReactNode;
}) {
  const { session, status } = useSession();

  if (status === "loading") {
    return (
      <div className="grid h-dvh place-items-center">
        <Loader2 aria-hidden className="size-5 animate-spin text-muted-foreground" />
        <span className="sr-only" role="status">
          Loading your workspace
        </span>
      </div>
    );
  }

  // Middleware already redirects when the cookie is absent; this covers the case
  // where the cookie exists but the backend rejected it.
  if (status === "unauthenticated" || !session) {
    return (
      <EmptyState
        title="Your session has ended"
        description="Sign in again to continue."
        action={
          <Button asChild>
            <Link href="/login">Sign in</Link>
          </Button>
        }
      />
    );
  }

  const resolution = resolveOrganization(session.user.organizations, orgSlug);

  if (resolution.status === "no-organizations") {
    return (
      <EmptyState
        icon={Building2}
        title="You are not in any organization yet"
        description="Ask an administrator to invite you, or create your own workspace."
      />
    );
  }

  if (resolution.status === "not-a-member") {
    return (
      <EmptyState
        icon={Building2}
        title="That workspace is not available to you"
        description="You may have followed a link for a different organization."
        action={
          <div className="flex flex-wrap justify-center gap-2">
            {session.user.organizations.map((org) => (
              <Button key={org.id} variant="outline" size="sm" asChild>
                <Link href={`/${org.slug}/dashboard`}>{org.name}</Link>
              </Button>
            ))}
          </div>
        }
      />
    );
  }

  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex h-dvh overflow-hidden">
        <Sidebar orgSlug={orgSlug} />
        <div className="flex min-w-0 flex-1 flex-col">
          <Topbar title={title} />
          <main className="flex-1 overflow-y-auto">{children}</main>
        </div>
      </div>
      <CommandPalette orgSlug={orgSlug} />
    </TooltipProvider>
  );
}
```

- [ ] **Step 8: Create `frontend/src/app/(app)/[orgSlug]/layout.tsx`**

```tsx
import { AppShell } from "@/components/layout/app-shell";

/** `params` is a Promise in Next 15+. */
export default async function OrgLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ orgSlug: string }>;
}) {
  const { orgSlug } = await params;
  return (
    <AppShell orgSlug={orgSlug} title="Dashboard">
      {children}
    </AppShell>
  );
}
```

- [ ] **Step 9: Commit** (build will fail until Task 19 supplies `CommandPalette` — do that first, then commit both)

---

## Task 19: Command palette

**Files:**
- Create: `frontend/src/components/layout/command-palette.tsx`

- [ ] **Step 1: Create `frontend/src/components/layout/command-palette.tsx`**

```tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import {
  LayoutDashboard,
  FolderKanban,
  Building2,
  Moon,
  Sun,
  LogOut,
  PanelLeft,
} from "lucide-react";
import { useTheme } from "next-themes";
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { useUiStore } from "@/stores/ui-store";
import { useSession } from "@/features/auth/session-context";
import { reportError } from "@/features/auth/hooks";

/**
 * A primary navigation path, not a power-user shortcut — which is why it carries
 * actions (theme, sign out, collapse) as well as destinations. Registering ⌘K here,
 * once, keeps every page from having to remember to wire it up.
 */
export function CommandPalette({ orgSlug }: { orgSlug: string }) {
  const { commandPaletteOpen, setCommandPaletteOpen, toggleSidebar } = useUiStore();
  const { session, signOut, switchOrganization } = useSession();
  const { resolvedTheme, setTheme } = useTheme();
  const router = useRouter();

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "k" && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        setCommandPaletteOpen(!useUiStore.getState().commandPaletteOpen);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [setCommandPaletteOpen]);

  const run = (action: () => void | Promise<void>) => {
    setCommandPaletteOpen(false);
    void Promise.resolve(action()).catch(reportError);
  };

  const otherOrgs = (session?.user.organizations ?? []).filter(
    (org) => org.id !== session?.user.activeOrganizationId,
  );

  return (
    <CommandDialog
      open={commandPaletteOpen}
      onOpenChange={setCommandPaletteOpen}
      title="Command palette"
      description="Search for a page or run a command"
    >
      <CommandInput placeholder="Search pages and commands…" />
      <CommandList>
        <CommandEmpty>No results.</CommandEmpty>

        <CommandGroup heading="Go to">
          <CommandItem onSelect={() => run(() => router.push(`/${orgSlug}/dashboard`))}>
            <LayoutDashboard aria-hidden className="mr-2 size-4" />
            Dashboard
          </CommandItem>
          <CommandItem disabled>
            <FolderKanban aria-hidden className="mr-2 size-4" />
            Projects — coming soon
          </CommandItem>
        </CommandGroup>

        {otherOrgs.length > 0 && (
          <>
            <CommandSeparator />
            <CommandGroup heading="Switch organization">
              {otherOrgs.map((org) => (
                <CommandItem
                  key={org.id}
                  onSelect={() =>
                    run(async () => {
                      await switchOrganization(org.id);
                      router.push(`/${org.slug}/dashboard`);
                    })
                  }
                >
                  <Building2 aria-hidden className="mr-2 size-4" />
                  {org.name}
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        )}

        <CommandSeparator />
        <CommandGroup heading="Actions">
          <CommandItem
            onSelect={() => run(() => setTheme(resolvedTheme === "dark" ? "light" : "dark"))}
          >
            {resolvedTheme === "dark" ? (
              <Sun aria-hidden className="mr-2 size-4" />
            ) : (
              <Moon aria-hidden className="mr-2 size-4" />
            )}
            Toggle theme
          </CommandItem>
          <CommandItem onSelect={() => run(toggleSidebar)}>
            <PanelLeft aria-hidden className="mr-2 size-4" />
            Toggle sidebar
          </CommandItem>
          <CommandItem onSelect={() => run(signOut)}>
            <LogOut aria-hidden className="mr-2 size-4" />
            Sign out
          </CommandItem>
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}
```

- [ ] **Step 2: Build and commit the shell together**

```bash
cd frontend && pnpm typecheck && pnpm build
git add frontend/src
git commit -m "feat(frontend): app shell with sidebar, org switcher and command palette"
```

---

## Task 20: Dashboard skeleton

**Files:**
- Create: `frontend/src/app/(app)/[orgSlug]/dashboard/page.tsx`
- Create: `frontend/src/app/(app)/[orgSlug]/dashboard/dashboard-content.tsx`

This is the Phase 1b exit screen. It proves the session, the org context and the shell — not the delivery data, which has no UI yet.

- [ ] **Step 1: Create `frontend/src/app/(app)/[orgSlug]/dashboard/dashboard-content.tsx`**

```tsx
"use client";

import { Sparkles } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { useSession } from "@/features/auth/session-context";

const PLACEHOLDER_CARDS = [
  { label: "Active sprint", hint: "Sprint board and burndown" },
  { label: "My work", hint: "Assigned stories and tasks" },
  { label: "Velocity", hint: "Rolling six-sprint average" },
  { label: "Risk", hint: "Over-budget and requirement change" },
];

export function DashboardContent() {
  const { session } = useSession();
  const firstName = session?.user.fullName.split(" ")[0] ?? "there";
  const orgName = session?.user.activeOrganizationName ?? "your workspace";

  return (
    <div className="mx-auto max-w-6xl space-y-8 p-6">
      <header className="space-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Welcome back, {firstName}</h2>
        <p className="text-sm text-muted-foreground">
          You are signed in to <span className="text-foreground">{orgName}</span> as{" "}
          <span className="text-foreground">{session?.user.activeRole?.toLowerCase()}</span>.
        </p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {PLACEHOLDER_CARDS.map((card) => (
          <div key={card.label} className="rounded-lg border bg-card p-4">
            <p className="text-xs font-medium text-muted-foreground">{card.label}</p>
            <Skeleton className="mt-3 h-7 w-20" />
            <p className="mt-3 text-2xs text-muted-foreground">{card.hint}</p>
          </div>
        ))}
      </div>

      <div className="rounded-lg border border-dashed p-8 text-center">
        <Sparkles aria-hidden className="mx-auto mb-3 size-6 text-ai-accent" />
        <h3 className="text-sm font-medium">The delivery views land next</h3>
        <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground text-balance">
          Projects, backlog, boards, sprints and the AI panels are built on the API this
          shell already talks to. Press{" "}
          <kbd className="rounded border bg-muted px-1.5 font-mono text-2xs">⌘K</kbd> to
          move around.
        </p>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create `frontend/src/app/(app)/[orgSlug]/dashboard/page.tsx`**

```tsx
import type { Metadata } from "next";
import { DashboardContent } from "./dashboard-content";

export const metadata: Metadata = { title: "Dashboard — SprintIQ" };

export default function DashboardPage() {
  return <DashboardContent />;
}
```

- [ ] **Step 3: Verify the whole loop by hand**

With the backend and dev server running: register, sign in, confirm you land on `/<slug>/dashboard`, that your name and org appear, that ⌘K opens the palette, that the sidebar collapses and the choice survives a reload, and that Sign out returns you to `/login`.

- [ ] **Step 4: Commit**

```bash
cd frontend && pnpm typecheck && pnpm build
git add frontend/src
git commit -m "feat(frontend): dashboard skeleton completing the signed-in loop"
```

---

## Task 21: Motion foundation

**Files:**
- Create: `frontend/src/lib/motion.ts`, `frontend/src/components/marketing/reveal.tsx`
- Test: `frontend/src/lib/motion.test.ts`

- [ ] **Step 1: Install GSAP**

```bash
cd frontend && pnpm add gsap @gsap/react
```

- [ ] **Step 2: Write the failing test**

Create `frontend/src/lib/motion.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import { DURATION, EASE, prefersReducedMotion } from "./motion";

const mockMatchMedia = (matches: boolean) => {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
};

describe("motion", () => {
  it("exposes a shared vocabulary rather than magic numbers", () => {
    // App-side durations must stay imperceptible; anything slower reads as lag.
    expect(DURATION.feedback).toBeLessThanOrEqual(0.2);
    expect(DURATION.enter).toBeLessThanOrEqual(0.3);
    expect(EASE.out).toContain("cubic-bezier");
  });

  it("detects the reduced-motion preference", () => {
    mockMatchMedia(true);
    expect(prefersReducedMotion()).toBe(true);
    mockMatchMedia(false);
    expect(prefersReducedMotion()).toBe(false);
  });

});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `pnpm vitest run src/lib/motion.test.ts`
Expected: FAIL — cannot resolve `./motion`.

- [ ] **Step 4: Create `frontend/src/lib/motion.ts`**

```ts
/**
 * One vocabulary for motion, so durations are chosen once rather than guessed at
 * each call site. The app numbers are deliberately small: anything a user can
 * perceive as waiting, while they are working, is a defect.
 */
export const DURATION = {
  feedback: 0.15,
  enter: 0.25,
  exit: 0.2,
  reveal: 0.6,
  sequence: 0.9,
} as const;

export const EASE = {
  out: "cubic-bezier(0.25, 1, 0.5, 1)",
  in: "cubic-bezier(0.5, 0, 0.75, 0)",
  inOut: "cubic-bezier(0.76, 0, 0.24, 1)",
  spring: "cubic-bezier(0.34, 1.56, 0.64, 1)",
} as const;

/** GSAP takes named eases; these are the same curves as the CSS ones above. */
export const GSAP_EASE = {
  out: "power3.out",
  in: "power3.in",
  inOut: "power3.inOut",
} as const;

export function prefersReducedMotion(): boolean {
  if (typeof matchMedia !== "function") return false;
  return matchMedia("(prefers-reduced-motion: reduce)").matches;
}

```

- [ ] **Step 5: Run the tests**

Run: `pnpm vitest run src/lib/motion.test.ts`
Expected: 4 passed.

- [ ] **Step 6: Create `frontend/src/components/marketing/reveal.tsx`**

```tsx
"use client";

import { useEffect, useRef } from "react";
import { DURATION, GSAP_EASE, prefersReducedMotion } from "@/lib/motion";
import { cn } from "@/lib/utils";

/**
 * Scroll-triggered entrance for a marketing block.
 *
 * GSAP and ScrollTrigger are imported dynamically inside the effect so they are
 * code-split into the marketing route group and never reach the app bundle — the
 * signed-in product has no use for them.
 */
export function Reveal({
  children,
  className,
  delay = 0,
  y = 24,
  stagger,
}: {
  children: React.ReactNode;
  className?: string;
  delay?: number;
  y?: number;
  /** Selector for children to stagger, e.g. "[data-reveal-item]". */
  stagger?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;

    if (prefersReducedMotion()) {
      element.style.opacity = "1";
      return;
    }

    let cleanup = () => {};
    let cancelled = false;

    void (async () => {
      const [{ gsap }, { ScrollTrigger }] = await Promise.all([
        import("gsap"),
        import("gsap/ScrollTrigger"),
      ]);
      if (cancelled) return;

      gsap.registerPlugin(ScrollTrigger);
      const context = gsap.context(() => {
        const targets = stagger ? element.querySelectorAll(stagger) : [element];
        gsap.fromTo(
          targets,
          { opacity: 0, y },
          {
            opacity: 1,
            y: 0,
            duration: DURATION.reveal,
            ease: GSAP_EASE.out,
            delay,
            stagger: stagger ? 0.08 : 0,
            scrollTrigger: {
              trigger: element,
              start: "top 85%",
              once: true,
            },
          },
        );
      }, element);

      cleanup = () => context.revert();
    })();

    return () => {
      cancelled = true;
      cleanup();
    };
  }, [delay, y, stagger]);

  // opacity-0 in markup would hide content permanently if JS fails; the effect
  // sets it, so a no-JS visitor sees everything.
  return (
    <div ref={ref} className={cn(className)}>
      {children}
    </div>
  );
}
```

- [ ] **Step 7: Commit**

```bash
cd frontend && pnpm typecheck && pnpm test
git add frontend/src frontend/package.json frontend/pnpm-lock.yaml
git commit -m "feat(frontend): motion vocabulary and code-split scroll reveals"
```

---

## Task 22: Marketing layout

**Files:**
- Create: `frontend/src/app/(marketing)/layout.tsx`, `frontend/src/components/marketing/site-nav.tsx`, `site-footer.tsx`

- [ ] **Step 1: Create `frontend/src/components/marketing/site-nav.tsx`**

```tsx
"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from "@/components/ui/sheet";
import { Logo } from "@/components/brand/logo";
import { cn } from "@/lib/utils";

const LINKS = [
  { href: "#models", label: "AI models" },
  { href: "#loop", label: "How it works" },
  { href: "#analytics", label: "Analytics" },
  { href: "#pricing", label: "Pricing" },
];

export function SiteNav() {
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    // passive: this listener never calls preventDefault, and saying so lets the
    // browser scroll without waiting on it.
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <header
      className={cn(
        "fixed inset-x-0 top-0 z-50 transition-colors duration-200",
        scrolled ? "border-b bg-background/80 backdrop-blur-md" : "bg-transparent",
      )}
    >
      <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-6">
        <Link href="/" aria-label="SprintIQ home">
          <Logo />
        </Link>

        <nav aria-label="Sections" className="hidden gap-6 md:flex">
          {LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-2">
          <Button variant="ghost" size="sm" asChild className="hidden sm:inline-flex">
            <Link href="/login">Sign in</Link>
          </Button>
          <Button size="sm" asChild>
            <Link href="/register">Start free</Link>
          </Button>

          <Sheet>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open menu">
                <Menu aria-hidden className="size-5" />
              </Button>
            </SheetTrigger>
            <SheetContent side="right" className="w-64">
              <SheetTitle className="sr-only">Navigation</SheetTitle>
              <nav className="mt-8 flex flex-col gap-1">
                {LINKS.map((link) => (
                  <a key={link.href} href={link.href} className="rounded-md px-2 py-2 text-sm hover:bg-accent">
                    {link.label}
                  </a>
                ))}
                <Link href="/login" className="rounded-md px-2 py-2 text-sm hover:bg-accent">
                  Sign in
                </Link>
              </nav>
            </SheetContent>
          </Sheet>
        </div>
      </div>
    </header>
  );
}
```

- [ ] **Step 2: Create `frontend/src/components/marketing/site-footer.tsx`**

```tsx
import Link from "next/link";
import { Logo } from "@/components/brand/logo";

const GROUPS = [
  { title: "Product", links: [["AI models", "#models"], ["How it works", "#loop"], ["Analytics", "#analytics"], ["Pricing", "#pricing"]] },
  { title: "Account", links: [["Sign in", "/login"], ["Create workspace", "/register"]] },
] as const;

export function SiteFooter() {
  return (
    <footer className="border-t">
      <div className="mx-auto grid max-w-6xl gap-10 px-6 py-14 sm:grid-cols-3">
        <div className="space-y-3">
          <Logo />
          <p className="max-w-xs text-sm text-muted-foreground">
            AI-powered agile delivery. Every prediction attached to a decision.
          </p>
        </div>

        {GROUPS.map((group) => (
          <nav key={group.title} aria-label={group.title} className="space-y-3">
            <h2 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {group.title}
            </h2>
            <ul className="space-y-2">
              {group.links.map(([label, href]) => (
                <li key={label}>
                  <Link href={href} className="text-sm text-muted-foreground hover:text-foreground">
                    {label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>

      <div className="border-t">
        <p className="mx-auto max-w-6xl px-6 py-6 text-xs text-muted-foreground">
          &copy; {new Date().getFullYear()} SprintIQ
        </p>
      </div>
    </footer>
  );
}
```

- [ ] **Step 3: Create `frontend/src/app/(marketing)/layout.tsx`**

```tsx
import { SiteNav } from "@/components/marketing/site-nav";
import { SiteFooter } from "@/components/marketing/site-footer";

export default function MarketingLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <SiteNav />
      {/* pt-16 clears the fixed nav; scroll-smooth makes the anchor links glide. */}
      <main className="scroll-smooth pt-16">{children}</main>
      <SiteFooter />
    </>
  );
}
```

- [ ] **Step 4: Commit**

```bash
cd frontend && pnpm typecheck
git add frontend/src
git commit -m "feat(frontend): marketing layout with scroll-aware nav"
```

---

## Task 23: Hero and AI model sections

**Files:**
- Create: `frontend/src/components/marketing/hero.tsx`, `models-section.tsx`
- Create: `frontend/src/components/marketing/board-preview.tsx`

- [ ] **Step 1: Create `frontend/src/components/marketing/board-preview.tsx`**

```tsx
import { Sparkles } from "lucide-react";

const COLUMNS = [
  { name: "To Do", tone: "bg-status-todo", cards: [["PG-142", "Accept Visa payments", "5"], ["PG-147", "Refund flow", "3"]] },
  { name: "In Progress", tone: "bg-status-progress", cards: [["PG-139", "Tokenise card storage", "8"]] },
  { name: "In Review", tone: "bg-status-review", cards: [["PG-131", "3-D Secure challenge", "5"]] },
  { name: "Done", tone: "bg-status-done", cards: [["PG-128", "Payment webhooks", "3"]] },
] as const;

/**
 * A static representation of the real product, not a screenshot — it stays sharp at
 * any density, themes with the site, and cannot go stale when the UI changes.
 */
export function BoardPreview() {
  return (
    <div className="overflow-hidden rounded-xl border bg-card shadow-2xl shadow-primary/5">
      <div className="flex items-center gap-1.5 border-b px-4 py-2.5">
        <span className="size-2.5 rounded-full bg-priority-critical/60" />
        <span className="size-2.5 rounded-full bg-priority-medium/60" />
        <span className="size-2.5 rounded-full bg-status-done/60" />
        <span className="ml-3 text-2xs text-muted-foreground">Payments Gateway — Sprint 7</span>
      </div>

      <div className="grid gap-3 p-3 sm:grid-cols-4">
        {COLUMNS.map((column) => (
          <div key={column.name} className="space-y-2">
            <div className="flex items-center gap-1.5 px-1">
              <span className={`size-1.5 rounded-full ${column.tone}`} />
              <span className="text-2xs font-medium text-muted-foreground">{column.name}</span>
            </div>
            {column.cards.map(([key, title, points]) => (
              <div key={key} className="rounded-md border bg-background p-2.5">
                <p className="text-2xs text-muted-foreground">{key}</p>
                <p className="mt-1 text-xs leading-snug">{title}</p>
                <span className="mt-2 inline-block rounded bg-muted px-1.5 py-0.5 text-2xs font-medium">
                  {points}
                </span>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div className="border-t bg-ai-accent/5 p-3">
        <div className="flex items-start gap-2.5">
          <Sparkles aria-hidden className="mt-0.5 size-3.5 shrink-0 text-ai-accent" />
          <div className="min-w-0">
            <p className="text-xs font-medium">
              Estimate for PG-142: <span className="text-ai-accent">5 points</span>
              <span className="ml-2 font-normal text-muted-foreground">3–8 · 78% confident</span>
            </p>
            <p className="mt-1 text-2xs text-muted-foreground">
              Similar payment-integration stories in this project averaged 5.4 points.
            </p>
            <div className="mt-2 flex gap-1.5">
              {["Accept", "Override", "Compare"].map((label, index) => (
                <span
                  key={label}
                  className={`rounded px-2 py-1 text-2xs font-medium ${
                    index === 0 ? "bg-ai-accent text-white" : "border text-muted-foreground"
                  }`}
                >
                  {label}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create `frontend/src/components/marketing/hero.tsx`**

```tsx
"use client";

import { useEffect, useRef } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { BoardPreview } from "./board-preview";
import { DURATION, GSAP_EASE, prefersReducedMotion } from "@/lib/motion";

export function Hero() {
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = root.current;
    if (!element || prefersReducedMotion()) return;

    let cleanup = () => {};
    let cancelled = false;

    void import("gsap").then(({ gsap }) => {
      if (cancelled) return;
      const context = gsap.context(() => {
        gsap
          .timeline({ defaults: { ease: GSAP_EASE.out, duration: DURATION.reveal } })
          .from("[data-hero-line]", { y: 20, opacity: 0, stagger: 0.08 })
          .from("[data-hero-cta]", { y: 12, opacity: 0 }, "-=0.35")
          // The product shot arrives last and from further away: it is the payoff,
          // and leading with it would upstage the sentence that explains it.
          .from("[data-hero-art]", { y: 40, opacity: 0, duration: DURATION.sequence }, "-=0.45");
      }, element);
      cleanup = () => context.revert();
    });

    return () => {
      cancelled = true;
      cleanup();
    };
  }, []);

  return (
    <section ref={root} className="relative overflow-hidden">
      <div
        aria-hidden
        className="absolute inset-0 -z-10 bg-[radial-gradient(70%_55%_at_50%_0%,var(--primary)_0%,transparent_65%)] opacity-[0.10]"
      />
      <div
        aria-hidden
        className="absolute inset-0 -z-10 [background-image:linear-gradient(to_right,var(--border)_1px,transparent_1px),linear-gradient(to_bottom,var(--border)_1px,transparent_1px)] [background-size:56px_56px] [mask-image:radial-gradient(60%_50%_at_50%_0%,black,transparent)] opacity-50"
      />

      <div className="mx-auto max-w-6xl px-6 pb-20 pt-20 sm:pt-28">
        <div className="mx-auto max-w-3xl text-center">
          <span
            data-hero-line
            className="inline-flex items-center gap-2 rounded-full border bg-card px-3 py-1 text-xs text-muted-foreground"
          >
            <span className="size-1.5 rounded-full bg-ai-accent" />
            Four AI models, built into the workflow
          </span>

          <h1
            data-hero-line
            className="mt-6 text-4xl font-semibold tracking-tight text-balance sm:text-5xl"
          >
            Agile delivery that tells you what is about to go wrong
          </h1>

          <p data-hero-line className="mx-auto mt-5 max-w-xl text-lg text-muted-foreground text-balance">
            SprintIQ estimates story points, predicts sprint risk, ranks who should take
            a task, and forecasts cost — each one attached to the decision you are
            making, never a floating number on a dashboard.
          </p>

          <div data-hero-cta className="mt-8 flex flex-wrap justify-center gap-3">
            <Button size="lg" asChild>
              <Link href="/register">
                Start free <ArrowRight aria-hidden className="ml-2 size-4" />
              </Link>
            </Button>
            <Button size="lg" variant="outline" asChild>
              <a href="#models">See it work</a>
            </Button>
          </div>

          <p data-hero-cta className="mt-4 text-xs text-muted-foreground">
            No card required · Free for small teams
          </p>
        </div>

        <div data-hero-art className="mx-auto mt-16 max-w-4xl">
          <BoardPreview />
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 3: Create `frontend/src/components/marketing/models-section.tsx`**

```tsx
import { Brain, ShieldAlert, Users, Wallet } from "lucide-react";
import { Reveal } from "./reveal";

const MODELS = [
  {
    id: "M1",
    icon: Brain,
    title: "Story points and velocity",
    body: "Estimates a story from its text and the project's history, with uncertainty bounds and the drivers behind the number.",
    anchor: "Appears in the story editor and the sprint planning capacity meter.",
  },
  {
    id: "M2",
    icon: ShieldAlert,
    title: "Sprint risk",
    body: "Predicts over-budget and requirement-change risk from scope churn, blockers and the volatility of the requirements behind the work.",
    anchor: "Appears on the sprint dashboard and the planning risk card.",
  },
  {
    id: "M3",
    icon: Users,
    title: "Who should take this",
    body: "Ranks developers for a task on fit, consistency and current load — and scores how much each would learn from it.",
    anchor: "Appears in the task assignment assistant and the workload board.",
  },
  {
    id: "M4",
    icon: Wallet,
    title: "Cost forecast",
    body: "Forecasts sprint cost before it starts and re-forecasts mid-flight from actual effort, absence and overtime. Planning only — SprintIQ never bills.",
    anchor: "Appears in the planning cost widget and the cost trend chart.",
  },
] as const;

export function ModelsSection() {
  return (
    <section id="models" className="scroll-mt-20 border-t bg-secondary/20 py-24">
      <div className="mx-auto max-w-6xl px-6">
        <Reveal className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Predictions where the decision happens
          </h2>
          <p className="mt-4 text-muted-foreground text-balance">
            A number on a dashboard changes nothing. Every model here surfaces at the
            moment you are choosing — and every one shows its working.
          </p>
        </Reveal>

        <Reveal stagger="[data-reveal-item]" className="mt-14 grid gap-5 md:grid-cols-2">
          {MODELS.map(({ id, icon: Icon, title, body, anchor }) => (
            <article
              key={id}
              data-reveal-item
              className="rounded-xl border bg-card p-6 transition-colors hover:border-ai-accent/40"
            >
              <div className="flex items-center gap-3">
                <span className="grid size-9 place-items-center rounded-lg bg-ai-accent/10">
                  <Icon aria-hidden className="size-4 text-ai-accent" />
                </span>
                <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-2xs text-muted-foreground">
                  {id}
                </span>
              </div>
              <h3 className="mt-4 font-medium">{title}</h3>
              <p className="mt-2 text-sm text-muted-foreground">{body}</p>
              <p className="mt-4 border-t pt-3 text-xs text-muted-foreground">{anchor}</p>
            </article>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Commit**

```bash
cd frontend && pnpm typecheck
git add frontend/src
git commit -m "feat(frontend): marketing hero and AI model sections"
```

---

## Task 24: Remaining marketing sections and the landing page

**Files:**
- Create: `frontend/src/components/marketing/loop-section.tsx`, `analytics-section.tsx`, `pricing-section.tsx`, `faq-section.tsx`, `cta-section.tsx`
- Create: `frontend/src/app/(marketing)/page.tsx`
- Modify: `frontend/src/app/page.tsx` (delete — the route group owns `/`)

- [ ] **Step 1: Create `frontend/src/components/marketing/loop-section.tsx`**

```tsx
import { ClipboardList, PlayCircle, LineChart, RefreshCw } from "lucide-react";
import { Reveal } from "./reveal";

const STEPS = [
  { icon: ClipboardList, title: "Plan", body: "Rank the backlog, size stories with AI assistance, and commit against real capacity." },
  { icon: PlayCircle, title: "Execute", body: "Boards with WIP limits, dependency-aware blocking, timers that survive a refresh." },
  { icon: LineChart, title: "Track", body: "Burndown from recorded snapshots — so scope changes stay visible instead of being smoothed away." },
  { icon: RefreshCw, title: "Retrospect", body: "Capture what happened and carry the actions into the next sprint." },
] as const;

export function LoopSection() {
  return (
    <section id="loop" className="scroll-mt-20 py-24">
      <div className="mx-auto max-w-6xl px-6">
        <Reveal className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            The whole loop, not a slice of it
          </h2>
          <p className="mt-4 text-muted-foreground text-balance">
            Planning, delivery, tracking and reflection in one system — because the
            interesting signals only appear when they share a database.
          </p>
        </Reveal>

        <Reveal stagger="[data-reveal-item]" className="mt-14 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {STEPS.map(({ icon: Icon, title, body }, index) => (
            <div key={title} data-reveal-item className="relative">
              <span className="grid size-10 place-items-center rounded-lg border bg-card">
                <Icon aria-hidden className="size-4 text-primary" />
              </span>
              <h3 className="mt-4 font-medium">
                <span className="mr-2 font-mono text-xs text-muted-foreground">
                  {String(index + 1).padStart(2, "0")}
                </span>
                {title}
              </h3>
              <p className="mt-2 text-sm text-muted-foreground">{body}</p>
            </div>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
```

- [ ] **Step 2: Create `frontend/src/components/marketing/analytics-section.tsx`**

```tsx
import { Reveal } from "./reveal";

const VELOCITY = [
  { sprint: "S1", committed: 28, completed: 24 },
  { sprint: "S2", committed: 32, completed: 30 },
  { sprint: "S3", committed: 34, completed: 26 },
  { sprint: "S4", committed: 30, completed: 31 },
  { sprint: "S5", committed: 33, completed: 32 },
  { sprint: "S6", committed: 35, completed: 34 },
];

const MAX = 40;

export function AnalyticsSection() {
  return (
    <section id="analytics" className="scroll-mt-20 border-t bg-secondary/20 py-24">
      <div className="mx-auto grid max-w-6xl items-center gap-12 px-6 lg:grid-cols-2">
        <Reveal>
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Numbers you can defend in a review
          </h2>
          <p className="mt-4 text-muted-foreground">
            Velocity, burndown, cumulative flow, cycle time, spillover, scope volatility
            and workload balance — computed from what actually happened, not from what
            the board looks like today.
          </p>
          <ul className="mt-6 space-y-3 text-sm">
            {[
              "Commitment freezes when a sprint starts, so completion rate stays honest.",
              "Burndown reads recorded daily snapshots, so scope changes remain visible.",
              "Spikes are excluded from velocity — investigation is not delivery.",
            ].map((point) => (
              <li key={point} className="flex gap-2.5 text-muted-foreground">
                <span aria-hidden className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" />
                {point}
              </li>
            ))}
          </ul>
        </Reveal>

        <Reveal delay={0.1}>
          <figure className="rounded-xl border bg-card p-6">
            <figcaption className="mb-6 flex items-baseline justify-between">
              <span className="text-sm font-medium">Velocity</span>
              <span className="text-xs text-muted-foreground">committed vs completed</span>
            </figcaption>

            <div className="flex h-44 items-end gap-3" role="img"
                 aria-label="Bar chart of committed versus completed points across six sprints, trending toward parity">
              {VELOCITY.map((point) => (
                <div key={point.sprint} className="flex flex-1 flex-col items-center gap-1.5">
                  <div className="flex w-full items-end justify-center gap-0.5" style={{ height: "100%" }}>
                    <div
                      className="w-1/2 rounded-t bg-muted"
                      style={{ height: `${(point.committed / MAX) * 100}%` }}
                    />
                    <div
                      className="w-1/2 rounded-t bg-primary"
                      style={{ height: `${(point.completed / MAX) * 100}%` }}
                    />
                  </div>
                  <span className="text-2xs text-muted-foreground">{point.sprint}</span>
                </div>
              ))}
            </div>

            <div className="mt-5 flex gap-4 border-t pt-4 text-2xs text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <span className="size-2 rounded-sm bg-muted" /> Committed
              </span>
              <span className="flex items-center gap-1.5">
                <span className="size-2 rounded-sm bg-primary" /> Completed
              </span>
            </div>
          </figure>
        </Reveal>
      </div>
    </section>
  );
}
```

- [ ] **Step 3: Create `frontend/src/components/marketing/pricing-section.tsx`**

```tsx
import Link from "next/link";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Reveal } from "./reveal";
import { cn } from "@/lib/utils";

/** Tiers correspond to the OrganizationPlan enum: FREE, STARTER, PRO, ENTERPRISE. */
const TIERS = [
  {
    plan: "Free",
    price: "$0",
    cadence: "forever",
    blurb: "For a small team trying the loop.",
    features: ["Up to 5 members", "1 workspace", "Backlog, boards and sprints", "Core reports"],
    cta: "Start free",
    featured: false,
  },
  {
    plan: "Starter",
    price: "$8",
    cadence: "per member / month",
    blurb: "For a team that has committed to the process.",
    features: ["Unlimited members", "3 workspaces", "AI story point estimation", "Velocity and burndown"],
    cta: "Start free trial",
    featured: false,
  },
  {
    plan: "Pro",
    price: "$16",
    cadence: "per member / month",
    blurb: "For teams that want the predictions.",
    features: ["Everything in Starter", "All four AI models", "Risk and cost forecasting", "Full analytics suite", "Audit trail"],
    cta: "Start free trial",
    featured: true,
  },
  {
    plan: "Enterprise",
    price: "Custom",
    cadence: "annual",
    blurb: "For organizations with their own rules.",
    features: ["Everything in Pro", "SSO and SCIM", "Custom roles and retention", "Priority support"],
    cta: "Talk to us",
    featured: false,
  },
] as const;

export function PricingSection() {
  return (
    <section id="pricing" className="scroll-mt-20 py-24">
      <div className="mx-auto max-w-6xl px-6">
        <Reveal className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Simple pricing, no surprises
          </h2>
          <p className="mt-4 text-muted-foreground">Start free. Move up when the team does.</p>
        </Reveal>

        <Reveal stagger="[data-reveal-item]" className="mt-14 grid gap-5 lg:grid-cols-4">
          {TIERS.map((tier) => (
            <div
              key={tier.plan}
              data-reveal-item
              className={cn(
                "relative flex flex-col rounded-xl border bg-card p-6",
                tier.featured && "border-primary ring-1 ring-primary",
              )}
            >
              {tier.featured && (
                <span className="absolute -top-2.5 left-6 rounded bg-primary px-2 py-0.5 text-2xs font-medium text-primary-foreground">
                  Most popular
                </span>
              )}
              <h3 className="font-medium">{tier.plan}</h3>
              <p className="mt-1 text-xs text-muted-foreground">{tier.blurb}</p>

              <p className="mt-5">
                <span className="text-3xl font-semibold tracking-tight">{tier.price}</span>
                <span className="ml-1.5 text-xs text-muted-foreground">{tier.cadence}</span>
              </p>

              <ul className="mt-6 flex-1 space-y-2.5">
                {tier.features.map((feature) => (
                  <li key={feature} className="flex gap-2 text-sm text-muted-foreground">
                    <Check aria-hidden className="mt-0.5 size-3.5 shrink-0 text-status-done" />
                    {feature}
                  </li>
                ))}
              </ul>

              <Button asChild variant={tier.featured ? "default" : "outline"} className="mt-6 w-full">
                <Link href="/register">{tier.cta}</Link>
              </Button>
            </div>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Create `frontend/src/components/marketing/faq-section.tsx`**

```tsx
import { Reveal } from "./reveal";

const FAQS = [
  {
    q: "Do I need to run anything else to get the AI features?",
    a: "No. The models sit behind a port with a built-in provider, so every AI panel works out of the box. Swapping in your own model services is a configuration change, not a migration.",
  },
  {
    q: "Does SprintIQ handle billing or invoicing?",
    a: "No. Cost forecasting is for planning only — it helps you see a sprint heading over budget before it does. There is no payment code anywhere in the product.",
  },
  {
    q: "Can I override the AI?",
    a: "Always. Every prediction offers Accept, Override and Compare, and the model keeps its suggestion alongside your decision. Overrides are the most useful training signal we get.",
  },
  {
    q: "How is my data separated from other customers?",
    a: "Every tenant-scoped row carries an organization id, enforced by a database-level filter that fails closed — a query with no organization context throws rather than returning everything.",
  },
  {
    q: "Can one person belong to more than one organization?",
    a: "Yes. One email is one login, and access is granted per organization, so contractors and consultants switch between workspaces without a second account.",
  },
] as const;

export function FaqSection() {
  return (
    <section className="border-t bg-secondary/20 py-24">
      <div className="mx-auto max-w-3xl px-6">
        <Reveal className="text-center">
          <h2 className="text-3xl font-semibold tracking-tight">Questions</h2>
        </Reveal>

        <Reveal stagger="[data-reveal-item]" className="mt-12 space-y-8">
          {FAQS.map((faq) => (
            <div key={faq.q} data-reveal-item>
              <h3 className="font-medium">{faq.q}</h3>
              <p className="mt-2 text-sm text-muted-foreground">{faq.a}</p>
            </div>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Create `frontend/src/components/marketing/cta-section.tsx`**

```tsx
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Reveal } from "./reveal";

export function CtaSection() {
  return (
    <section className="relative overflow-hidden py-28">
      <div
        aria-hidden
        className="absolute inset-0 -z-10 bg-[radial-gradient(60%_60%_at_50%_50%,var(--primary)_0%,transparent_65%)] opacity-[0.10]"
      />
      <Reveal className="mx-auto max-w-2xl px-6 text-center">
        <h2 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">
          Find out what your next sprint is going to do
        </h2>
        <p className="mt-4 text-muted-foreground text-balance">
          Set up a workspace in under a minute. Free for small teams, no card required.
        </p>
        <Button size="lg" asChild className="mt-8">
          <Link href="/register">
            Start free <ArrowRight aria-hidden className="ml-2 size-4" />
          </Link>
        </Button>
      </Reveal>
    </section>
  );
}
```

- [ ] **Step 6: Create `frontend/src/app/(marketing)/page.tsx` and remove the scaffold page**

```tsx
import type { Metadata } from "next";
import { Hero } from "@/components/marketing/hero";
import { ModelsSection } from "@/components/marketing/models-section";
import { LoopSection } from "@/components/marketing/loop-section";
import { AnalyticsSection } from "@/components/marketing/analytics-section";
import { PricingSection } from "@/components/marketing/pricing-section";
import { FaqSection } from "@/components/marketing/faq-section";
import { CtaSection } from "@/components/marketing/cta-section";

export const metadata: Metadata = {
  title: "SprintIQ — agile delivery that tells you what is about to go wrong",
  description:
    "AI story point estimation, sprint risk prediction, resource allocation and cost forecasting, each attached to the decision you are making.",
};

export default function LandingPage() {
  return (
    <>
      <Hero />
      <ModelsSection />
      <LoopSection />
      <AnalyticsSection />
      <PricingSection />
      <FaqSection />
      <CtaSection />
    </>
  );
}
```

```bash
rm frontend/src/app/page.tsx
```

Both `app/page.tsx` and `app/(marketing)/page.tsx` resolve to `/`, which is a build error. The route group owns it.

- [ ] **Step 7: Verify**

```bash
cd frontend && pnpm build && pnpm dev
```

Visit `http://localhost:3000`. Expected: hero animates in, sections reveal on scroll, nav gains a background once scrolled, both themes look right, and the layout holds at 375px wide.

- [ ] **Step 8: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): marketing landing page"
```

---

## Task 25: Error boundaries and tenant recovery

**Files:**
- Create: `frontend/src/components/feedback/error-state.tsx`
- Create: `frontend/src/app/(app)/[orgSlug]/error.tsx`, `frontend/src/app/(app)/[orgSlug]/not-found.tsx`
- Create: `frontend/src/app/error.tsx`, `frontend/src/app/not-found.tsx`
- Modify: `frontend/src/components/providers.tsx`
- Test: `frontend/src/components/feedback/error-state.test.tsx`

Spec §8 requires three things not yet built: an inline treatment for 403/404, a
recovery path for `TENANT_NOT_RESOLVED`, and route-level boundaries so one failed
panel cannot blank the shell.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/feedback/error-state.test.tsx`:

```tsx
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorState } from "./error-state";
import { ApiError } from "@/lib/api/errors";

const apiError = (status: number, code: string, correlationId: string | null = "cid-9") =>
  new ApiError({ status, code, message: "Backend said no", correlationId });

describe("ErrorState", () => {
  it("shows a permission message for 403 without exposing the raw code", async () => {
    render(<ErrorState error={apiError(403, "ACCESS_DENIED")} />);
    expect(screen.getByText(/do not have access/i)).toBeInTheDocument();
  });

  it("shows a not-found message for 404", () => {
    render(<ErrorState error={apiError(404, "NOT_FOUND")} />);
    expect(screen.getByText(/could not be found/i)).toBeInTheDocument();
  });

  it("surfaces the correlation id so a bug report matches the backend logs", () => {
    render(<ErrorState error={apiError(500, "INTERNAL_ERROR", "cid-42")} />);
    expect(screen.getByText(/cid-42/)).toBeInTheDocument();
  });

  it("omits the reference line when there is no correlation id", () => {
    render(<ErrorState error={apiError(500, "INTERNAL_ERROR", null)} />);
    expect(screen.queryByText(/reference/i)).not.toBeInTheDocument();
  });

  it("calls the retry handler", async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(<ErrorState error={apiError(500, "INTERNAL_ERROR")} onRetry={onRetry} />);
    await user.click(screen.getByRole("button", { name: /try again/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("handles a plain Error, not just ApiError", () => {
    render(<ErrorState error={new Error("boom")} />);
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && pnpm vitest run src/components/feedback/error-state.test.tsx`
Expected: FAIL — cannot resolve `./error-state`.

- [ ] **Step 3: Create `frontend/src/components/feedback/error-state.tsx`**

```tsx
"use client";

import { Lock, SearchX, ServerCrash, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import { cn } from "@/lib/utils";

/**
 * The in-place treatment for a failure that belongs to a region of the page.
 *
 * A 403 is a state, not an event: a toast that disappears after four seconds
 * leaves the user staring at an empty panel with no explanation. So refusals and
 * missing resources render here, and only unexpected failures get a toast.
 */
export function ErrorState({
  error,
  onRetry,
  className,
}: {
  error: unknown;
  onRetry?: () => void;
  className?: string;
}) {
  const api = error instanceof ApiError ? error : null;

  const { icon: Icon, title, description } = describe(api);

  return (
    <div className={cn("flex flex-col items-center justify-center px-6 py-16 text-center", className)}>
      <Icon aria-hidden className="mb-4 size-8 text-muted-foreground" />
      <h2 className="text-base font-medium">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground text-balance">{description}</p>

      {api?.correlationId && (
        <p className="mt-4 font-mono text-2xs text-muted-foreground">
          Reference: {api.correlationId}
        </p>
      )}

      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} className="mt-6">
          <RefreshCw aria-hidden className="mr-2 size-3.5" />
          Try again
        </Button>
      )}
    </div>
  );
}

function describe(api: ApiError | null) {
  if (api?.status === 403) {
    return {
      icon: Lock,
      title: "You do not have access to this",
      description: "Ask an administrator in your organization if you need it.",
    };
  }
  if (api?.status === 404) {
    return {
      icon: SearchX,
      title: "This could not be found",
      description: "It may have been deleted, or the link may be out of date.",
    };
  }
  return {
    icon: ServerCrash,
    title: "Something went wrong",
    // Show the backend's message when we have one — it is written for users.
    // Never show a raw Error's message, which is written for developers.
    description: api?.message ?? "We could not complete that. Please try again.",
  };
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/components/feedback/error-state.test.tsx`
Expected: 6 passed.

- [ ] **Step 5: Recover from a lost tenant context in the session provider**

`TENANT_NOT_RESOLVED` means the backend could not tell which organization the request
belonged to — usually because the active organization changed underneath us. Re-resolving
is almost always the right move, and it is invisible to the user.

In `frontend/src/features/auth/session-context.tsx`, add this effect after the
`tokenStore.configure` effect:

```tsx
  // A lost tenant context is recoverable: re-run the session exchange, which mints a
  // token carrying the organization claim again. Showing a generic error here would
  // make the user sign out and back in to fix something we can fix ourselves.
  const reresolveTenant = useCallback(async () => {
    try {
      applySession(clientSessionSchema.parse(await postJson("/api/auth/session")));
      return true;
    } catch {
      setStatus("unauthenticated");
      return false;
    }
  }, [applySession]);

  useEffect(() => {
    setTenantRecoveryHandler(reresolveTenant);
    return () => setTenantRecoveryHandler(null);
  }, [reresolveTenant]);
```

and add to its imports:

```tsx
import { setTenantRecoveryHandler } from "@/lib/api/client";
```

- [ ] **Step 6: Call the handler from the API client**

In `frontend/src/lib/api/client.ts`, add above `export const api`:

```ts
type TenantRecoveryHandler = (() => Promise<boolean>) | null;

let tenantRecoveryHandler: TenantRecoveryHandler = null;

/** Registered by the session provider; used to recover a lost organization claim. */
export const setTenantRecoveryHandler = (handler: TenantRecoveryHandler) => {
  tenantRecoveryHandler = handler;
};

export const getTenantRecoveryHandler = () => tenantRecoveryHandler;
```

and in `ApiClient.send`, immediately after the `error.isRefreshable` block:

```ts
    // Same one-shot guard as the refresh path: recover once, then surface the error.
    if (error.code === "TENANT_NOT_RESOLVED" && !options?.isRetry) {
      const recovered = await getTenantRecoveryHandler()?.();
      if (recovered) {
        return this.send(method, path, body, { ...options, isRetry: true });
      }
    }
```

- [ ] **Step 7: Create the route-level boundaries**

`frontend/src/app/(app)/[orgSlug]/error.tsx`:

```tsx
"use client";

import { ErrorState } from "@/components/feedback/error-state";

/**
 * Scoped to the org segment, so a failure inside the app renders inside the shell
 * — sidebar and topbar survive, and the user can navigate away instead of being
 * dropped onto a blank page.
 */
export default function OrgError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return <ErrorState error={error} onRetry={reset} className="h-full" />;
}
```

`frontend/src/app/(app)/[orgSlug]/not-found.tsx`:

```tsx
import { ErrorState } from "@/components/feedback/error-state";
import { ApiError } from "@/lib/api/errors";

export default function OrgNotFound() {
  return (
    <ErrorState
      error={new ApiError({ status: 404, code: "NOT_FOUND", message: "Not found" })}
      className="h-full"
    />
  );
}
```

`frontend/src/app/error.tsx`:

```tsx
"use client";

import { ErrorState } from "@/components/feedback/error-state";

export default function RootError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="grid min-h-dvh place-items-center">
      <ErrorState error={error} onRetry={reset} />
    </div>
  );
}
```

`frontend/src/app/not-found.tsx`:

```tsx
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/feedback/empty-state";

export default function NotFound() {
  return (
    <div className="grid min-h-dvh place-items-center">
      <EmptyState
        title="Page not found"
        description="The page you were looking for does not exist."
        action={
          <Button asChild>
            <Link href="/">Back to home</Link>
          </Button>
        }
      />
    </div>
  );
}
```

- [ ] **Step 8: Verify by hand**

With the app running and signed in, visit `/<your-slug>/nonexistent`.
Expected: the not-found state renders **inside** the shell, with the sidebar still usable.

- [ ] **Step 9: Commit**

```bash
cd frontend && pnpm typecheck && pnpm test && pnpm build
git add frontend/src
git commit -m "feat(frontend): error boundaries, inline refusal states and tenant recovery"
```

---

## Task 26: Accessibility and reduced-motion pass

**Files:**
- Create: `frontend/src/tests/a11y.test.tsx`

- [ ] **Step 1: Install the axe matcher**

```bash
cd frontend && pnpm add -D jest-axe @types/jest-axe
```

- [ ] **Step 2: Write the test**

Create `frontend/src/tests/a11y.test.tsx`:

```tsx
import { describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { axe } from "jest-axe";
import { LoginForm } from "@/features/auth/components/login-form";
import { EmptyState } from "@/components/feedback/empty-state";
import { BoardPreview } from "@/components/marketing/board-preview";

vi.mock("@/features/auth/hooks", async () => {
  const actual = await vi.importActual<typeof import("@/features/auth/hooks")>(
    "@/features/auth/hooks",
  );
  return { ...actual, useLogin: () => ({ mutate: vi.fn(), isPending: false, error: null }) };
});

describe("accessibility", () => {
  it("login form has no violations", async () => {
    const { container } = render(<LoginForm />);
    expect(await axe(container)).toHaveNoViolations();
  });

  it("empty state has no violations", async () => {
    const { container } = render(<EmptyState title="Nothing here" description="Yet." />);
    expect(await axe(container)).toHaveNoViolations();
  });

  it("board preview has no violations", async () => {
    const { container } = render(<BoardPreview />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
```

- [ ] **Step 3: Register the matcher**

Append to `frontend/src/tests/setup.ts`:

```ts
import { expect } from "vitest";
import * as matchers from "jest-axe/extend-expect";

expect.extend(matchers as never);
```

- [ ] **Step 4: Run and fix**

Run: `pnpm vitest run src/tests/a11y.test.tsx`
Expected: 3 passed. If a violation appears, fix the component — not the test.

- [ ] **Step 5: Verify reduced motion by hand**

macOS: System Settings → Accessibility → Display → Reduce motion. Reload `http://localhost:3000`.
Expected: content is fully visible with no sliding or staggering, and nothing is stuck invisible. That last part is the failure mode worth checking — a reveal that never fires leaves a blank page.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "test(frontend): axe coverage and reduced-motion verification"
```

---

## Task 27: End-to-end test

**Files:**
- Create: `frontend/playwright.config.ts`, `frontend/src/tests/e2e/auth-flow.spec.ts`
- Modify: `frontend/package.json`

- [ ] **Step 1: Install Playwright**

```bash
cd frontend && pnpm add -D @playwright/test && pnpm exec playwright install chromium
```

- [ ] **Step 2: Create `frontend/playwright.config.ts`**

```ts
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./src/tests/e2e",
  fullyParallel: false,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "pnpm dev",
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
```

Note: no `webServer` entry for the backend — it is a separate stack and the test asserts a clear failure if it is not running.

- [ ] **Step 3: Create `frontend/src/tests/e2e/auth-flow.spec.ts`**

```ts
import { test, expect } from "@playwright/test";

/** Unique per run, so the suite is re-runnable without cleaning the database. */
const unique = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

const PASSWORD = "Str0ng!Passw0rd#2026";

test.beforeAll(async ({ request }) => {
  const response = await request.get("http://localhost:8080/actuator/health").catch(() => null);
  test.skip(
    !response?.ok(),
    "Backend is not running on :8080 — start it with `make infra && make backend`",
  );
});

test("register, sign in, use the shell, and sign out", async ({ page }) => {
  const id = unique();
  const email = `e2e-${id}@example.test`;

  // --- register -----------------------------------------------------------
  await page.goto("/register");
  await page.getByLabel("Full name").fill("E2E Tester");
  await page.getByLabel("Organization").fill(`E2E Org ${id}`);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(page).toHaveURL(/\/login/);

  // --- sign in ------------------------------------------------------------
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/[^/]+\/dashboard$/);
  await expect(page.getByText("Welcome back, E2E")).toBeVisible();

  // The refresh token must be httpOnly — if this ever flips, the BFF is pointless.
  const cookie = (await page.context().cookies()).find((c) => c.name === "sq_rt");
  expect(cookie?.httpOnly).toBe(true);

  // ...and it must not have leaked into storage.
  const leaked = await page.evaluate(() => JSON.stringify(window.localStorage));
  expect(leaked).not.toContain(cookie?.value ?? "__no_cookie__");

  // --- session survives a reload -------------------------------------------
  await page.reload();
  await expect(page.getByText("Welcome back, E2E")).toBeVisible();

  // --- command palette ------------------------------------------------------
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.getByPlaceholder("Search pages and commands…")).toBeVisible();
  await page.keyboard.press("Escape");

  // --- sidebar collapse persists -------------------------------------------
  await page.getByRole("button", { name: "Collapse" }).click();
  await page.reload();
  await expect(page.getByRole("button", { name: "Expand sidebar" })).toBeVisible();

  // --- sign out -------------------------------------------------------------
  await page.getByRole("button", { name: "Account menu" }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);

  const afterSignOut = (await page.context().cookies()).find((c) => c.name === "sq_rt");
  expect(afterSignOut?.value ?? "").toBe("");
});

test("an unauthenticated visitor is redirected away from an app route", async ({ page }) => {
  await page.goto("/some-org/dashboard");
  await expect(page).toHaveURL(/\/login\?next=/);
});

test("the landing page renders its key sections", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: /Predictions where the decision happens/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: /Simple pricing/ })).toBeVisible();
});
```

- [ ] **Step 4: Add the script to `frontend/package.json`**

```json
{
  "scripts": {
    "test:e2e": "playwright test"
  }
}
```

- [ ] **Step 5: Run it**

```bash
make infra && make backend        # separate terminal
cd frontend && pnpm test:e2e
```

Expected: 3 passed. This is the Phase 1b exit criterion.

- [ ] **Step 6: Ignore Playwright output and commit**

Append to the root `.gitignore`:

```
frontend/test-results/
frontend/playwright-report/
frontend/.playwright/
```

```bash
git add -A frontend .gitignore
git commit -m "test(frontend): playwright e2e covering the full auth loop"
```

---

## Task 28: Docker and documentation

**Files:**
- Create: `frontend/Dockerfile`, `frontend/.dockerignore`
- Modify: `frontend/next.config.ts`, `docs/02-architecture.md`, `docs/04-folder-structure.md`, `README.md`

- [ ] **Step 1: Enable standalone output in `frontend/next.config.ts`**

```ts
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emits a self-contained server bundle, so the runtime image does not need
  // node_modules — a much smaller and faster container.
  output: "standalone",
};

export default nextConfig;
```

- [ ] **Step 2: Create `frontend/.dockerignore`**

```
node_modules
.next
out
test-results
playwright-report
.env*.local
```

- [ ] **Step 3: Create `frontend/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1
FROM node:22-alpine AS base
RUN corepack enable
WORKDIR /app

FROM base AS deps
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM base AS build
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# NEXT_PUBLIC_* values are inlined at build time, so they must be present here.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
ARG NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_WS_URL=$NEXT_PUBLIC_WS_URL
RUN pnpm build

FROM base AS runtime
ENV NODE_ENV=production
RUN addgroup -g 1001 nodejs && adduser -S -u 1001 -G nodejs nextjs
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
```

- [ ] **Step 4: Wire the build args in `docker-compose.yml`**

Replace the `frontend` service:

```yaml
  frontend:
    build:
      context: ./frontend
      args:
        NEXT_PUBLIC_API_URL: http://localhost:8080/api/v1
        NEXT_PUBLIC_WS_URL: ws://localhost:8080/ws
    container_name: sprintiq-frontend
    depends_on: [backend]
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080/api/v1
      NEXT_PUBLIC_WS_URL: ws://localhost:8080/ws
      # The browser and the container disagree about "localhost"; route handlers
      # must reach the backend by its service name.
      BACKEND_INTERNAL_URL: http://backend:8080/api/v1
    ports: ["3000:3000"]
```

- [ ] **Step 5: Verify the image**

```bash
docker compose build frontend && docker compose up -d
```

Visit `http://localhost:3000`. Expected: the landing page renders and login works against the containerised backend.

- [ ] **Step 6: Update the documentation**

In `docs/02-architecture.md`, change the diagram line `Next.js 14 (App Router)` to `Next.js 16 (App Router)`.

In `docs/04-folder-structure.md`, add the `(app)`/`api`/`middleware.ts` entries under `frontend/src/app/` so the tree matches what was built, and note Tailwind 4 has no `tailwind.config.ts`.

In `README.md`, add a Frontend row to the service table and record Phase 1b as delivered.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore(frontend): production Dockerfile, compose wiring and doc updates"
```

---

## Definition of done

- [ ] `cd frontend && pnpm typecheck && pnpm lint && pnpm test && pnpm build` all pass
- [ ] `pnpm test:e2e` passes with the backend running
- [ ] `docker compose up` serves the landing page and a working login at `:3000`
- [ ] The refresh token is httpOnly and appears in no client-readable storage
- [ ] A 403 or 404 renders inside the shell, not as a toast or a blank page
- [ ] Reduced motion leaves every section visible and still
- [ ] Both themes are correct on the landing page, auth pages and shell
- [ ] `docs/02` and `docs/04` match what was actually built
