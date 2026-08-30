"use client";

import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/**
 * Acceptance criteria as a list of rows, rather than as a block of text.
 *
 * <p>Criteria are a countable thing in this product, not prose. The story-point model
 * sums them, the requirement-change model reads how many there are and how many stories
 * have none, and the register decides which way a change pushed by comparing the count
 * before against the count after. A textarea asked people to maintain that count by
 * pressing Enter in the right places, and the one field where an accidental blank line
 * or a wrapped sentence changes a risk score is the field that should not be free
 * text.</p>
 *
 * <p>The rows are owned here and the joined string is pushed up, rather than the string
 * being parsed back on every render. A round-trip through <code>split/filter</code> would
 * delete a row the moment somebody added it, because a blank row does not survive being
 * serialised — and the blank row is exactly what they are about to type into.</p>
 */

/** One criterion, with an identity that survives its neighbours being deleted. */
type Row = { id: string; text: string };

/**
 * The rows plus the counter that names the next one.
 *
 * <p>Both in one piece of state rather than the counter in a ref, because a ref read
 * while the initial rows are being built is a read during render — which React's own
 * lint rule objects to, and which would give two rows the same key under Strict Mode's
 * double invocation.</p>
 */
type State = { rows: Row[]; nextId: number };

function seed(value: string, idPrefix: string): State {
  const initial = parseCriteria(value);
  return {
    rows: initial.map((text, index) => ({ id: `${idPrefix}-${index}`, text })),
    nextId: initial.length,
  };
}

/** Splits stored criteria into rows, tolerating the bullets people paste in. */
export function parseCriteria(text: string | null | undefined): string[] {
  if (!text) return [];
  return text
    .split(/\r?\n/)
    .map((line) => line.replace(/^[-*•]\s*/, "").trim())
    .filter(Boolean);
}

/** Back to the newline-separated form the API stores and both models read. */
export function joinCriteria(rows: string[]): string {
  return rows.map((row) => row.trim()).filter(Boolean).join("\n");
}

export function AcceptanceCriteriaList({
  value,
  onChange,
  idPrefix = "criterion",
  disabled = false,
  addLabel = "Add criterion",
  emptyHint = "No criteria yet. Add the things that must be true before this is done.",
}: {
  /** The stored form: one criterion per line. Read once, on mount. */
  value: string;
  onChange: (next: string) => void;
  idPrefix?: string;
  disabled?: boolean;
  addLabel?: string;
  emptyHint?: string;
}) {
  // Seeded once. After this the rows are the truth and `value` is derived from them —
  // see the note above about blank rows not surviving a round trip.
  const [{ rows, nextId }, setState] = useState<State>(() => seed(value, idPrefix));
  const [justAdded, setJustAdded] = useState<string | null>(null);

  function commit(next: Row[], counter = nextId) {
    setState({ rows: next, nextId: counter });
    onChange(joinCriteria(next.map((row) => row.text)));
  }

  function add(afterIndex?: number) {
    const row: Row = { id: `${idPrefix}-${nextId}`, text: "" };
    const next = [...rows];
    next.splice(afterIndex == null ? next.length : afterIndex + 1, 0, row);
    setJustAdded(row.id);
    commit(next, nextId + 1);
  }

  function edit(id: string, text: string) {
    commit(rows.map((row) => (row.id === id ? { ...row, text } : row)));
  }

  function remove(id: string) {
    commit(rows.filter((row) => row.id !== id));
  }

  return (
    <div className="space-y-1.5">
      {rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-line px-3 py-3 text-xs leading-relaxed text-ink-3">
          {emptyHint}
        </p>
      ) : (
        /*
          Capped, with its own scroll. A story with fifteen criteria should make this
          list scroll rather than push the dialog buttons off the screen.
        */
        <ul className="max-h-44 space-y-1.5 overflow-y-auto pr-0.5">
          {rows.map((row, index) => (
            <li key={row.id} className="flex items-center gap-1.5">
              <span
                aria-hidden
                className="w-5 shrink-0 text-right font-mono text-2xs text-ink-3 tabular-nums"
              >
                {index + 1}
              </span>
              <Input
                id={`${idPrefix}-${index}`}
                value={row.text}
                disabled={disabled}
                // Newly mounted, so the browser honours this and the next criterion can
                // be typed without reaching for the mouse.
                autoFocus={row.id === justAdded}
                onFocus={() => setJustAdded(null)}
                onChange={(event) => edit(row.id, event.target.value)}
                onKeyDown={(event) => {
                  // Enter adds the next row rather than submitting the form. A checklist
                  // people fill in one line at a time should not need the Add button on
                  // every line — and submitting on Enter here would create the story
                  // halfway through writing its criteria.
                  if (event.key === "Enter") {
                    event.preventDefault();
                    add(index);
                  }
                  // Backspace on an empty row deletes it and steps back, the way every
                  // other list editor behaves.
                  if (event.key === "Backspace" && row.text === "" && rows.length > 1) {
                    event.preventDefault();
                    remove(row.id);
                  }
                }}
                placeholder={
                  index === 0 ? "Given … when … then …" : "Another thing that must be true"
                }
                className="h-8 text-sm"
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                disabled={disabled}
                onClick={() => remove(row.id)}
                className={cn(
                  "size-8 shrink-0 text-ink-3",
                  "hover:bg-red-soft hover:text-red",
                )}
                // Named by its content, so a screen reader is not offered five
                // identical "delete" buttons.
                aria-label={
                  row.text.trim()
                    ? `Delete criterion ${index + 1}: ${row.text.trim()}`
                    : `Delete empty criterion ${index + 1}`
                }
              >
                <Trash2 className="size-3.5" aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
      )}

      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={disabled}
        onClick={() => add()}
        className="w-full"
      >
        <Plus className="size-3.5" aria-hidden />
        {addLabel}
      </Button>
    </div>
  );
}
