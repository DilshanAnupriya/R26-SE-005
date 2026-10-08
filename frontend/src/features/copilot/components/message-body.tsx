/**
 * An answer as plain text, with runs of "- " lines shown as a list.
 *
 * <p>The model is told markdown is not rendered, and it is not: an answer is the
 * model's text, and rendering it as HTML would hand anything echoed from a task title
 * a way onto the page. Lists are the one structure worth recovering.</p>
 */
export function MessageBody({ content }: { content: string }) {
  const blocks: ({ kind: "text"; lines: string[] } | { kind: "list"; items: string[] })[] = [];

  for (const line of content.split("\n")) {
    const item = /^\s*[-•]\s+(.*)$/.exec(line);
    const last = blocks[blocks.length - 1];
    if (item) {
      if (last?.kind === "list") last.items.push(item[1]);
      else blocks.push({ kind: "list", items: [item[1]] });
    } else if (last?.kind === "text") {
      last.lines.push(line);
    } else {
      blocks.push({ kind: "text", lines: [line] });
    }
  }

  return (
    <div className="space-y-1.5">
      {blocks.map((block, index) =>
        block.kind === "list" ? (
          <ul key={index} className="list-disc space-y-0.5 pl-4">
            {block.items.map((text, i) => (
              <li key={i}>{text}</li>
            ))}
          </ul>
        ) : block.lines.join("\n").trim() ? (
          <p key={index} className="whitespace-pre-wrap">
            {block.lines.join("\n").trim()}
          </p>
        ) : null,
      )}
    </div>
  );
}
