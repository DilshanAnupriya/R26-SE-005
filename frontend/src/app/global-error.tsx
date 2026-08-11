"use client";

/**
 * Last resort: a failure in the root layout itself, which the route-level boundary
 * cannot catch because it lives inside that layout.
 *
 * <p>Must render its own `<html>` and `<body>`: at this point the root layout did not
 * produce any. For the same reason it cannot use the design system — the providers,
 * fonts and theme all live in the layout that just failed — so the styling here is
 * inline and self-contained on purpose.</p>
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="en">
      <body
        style={{
          minHeight: "100vh",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontFamily: "ui-sans-serif, system-ui, sans-serif",
          background: "#0a0a0b",
          color: "#f5f5f6",
          margin: 0,
          padding: "1rem",
        }}
      >
        <div style={{ maxWidth: "28rem", textAlign: "center" }}>
          <h1 style={{ fontSize: "1.125rem", fontWeight: 600, margin: 0 }}>
            SprintIQ could not start
          </h1>
          <p style={{ marginTop: "0.5rem", fontSize: "0.875rem", opacity: 0.7 }}>
            Something failed before the application could load. Reloading usually fixes it.
          </p>
          {error.digest ? (
            <p
              style={{
                marginTop: "0.75rem",
                fontFamily: "ui-monospace, monospace",
                fontSize: "0.6875rem",
                opacity: 0.5,
              }}
            >
              Reference: {error.digest}
            </p>
          ) : null}
          <button
            onClick={reset}
            style={{
              marginTop: "1.25rem",
              padding: "0.5rem 1rem",
              fontSize: "0.875rem",
              borderRadius: "0.375rem",
              border: "1px solid rgba(255,255,255,0.2)",
              background: "transparent",
              color: "inherit",
              cursor: "pointer",
            }}
          >
            Reload
          </button>
        </div>
      </body>
    </html>
  );
}
