import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Standalone emits a self-contained server bundle carrying only the node_modules
  // actually imported — without it the Docker image ships the entire dependency
  // tree, hundreds of megabytes of build tooling that never runs in production.
  //
  // Gated on an env var the Dockerfile sets, because `next start` refuses to run
  // against a standalone build. Enabling it unconditionally silently breaks every
  // local production run, including the e2e suite's web server.
  output: process.env.BUILD_STANDALONE === "1" ? "standalone" : undefined,

  // Already the default; stating it means a future "just unblock the deploy" edit
  // has to be deliberate and visible in review. (Next 16 no longer takes an `eslint`
  // key here — linting is a separate `next lint` step.)
  typescript: { ignoreBuildErrors: false },

  poweredByHeader: false,

  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          // Defence in depth. The backend sets its own headers on API responses;
          // these cover the documents Next serves.
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
