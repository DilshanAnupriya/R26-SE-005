import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Proves the harness itself works before any real test depends on it: the "@/"
 * alias resolves, JSX compiles, jsdom renders, and jest-dom matchers are loaded.
 * When something here fails, the problem is the tooling, not the code under test.
 */
describe("test harness", () => {
  it("resolves the @/ alias to project modules", () => {
    expect(cn("a", false && "b", "c")).toBe("a c");
  });

  it("renders a vendored shadcn component into jsdom", () => {
    render(<Button>Create project</Button>);

    const button = screen.getByRole("button", { name: "Create project" });
    expect(button).toBeInTheDocument();
    expect(button).toBeEnabled();
  });

  it("stubs the browser APIs Radix overlays require", () => {
    expect(window.matchMedia("(prefers-reduced-motion: reduce)").matches).toBe(false);
    expect(typeof window.ResizeObserver).toBe("function");
  });
});
