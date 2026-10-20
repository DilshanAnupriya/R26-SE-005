#!/usr/bin/env node
// Copies partials/{head,header,footer}.html into every page between marker comments:
//   <!-- head:start --> … <!-- head:end -->
// {{root}} becomes the relative path back to the site root ("" or "../").
// Links whose data-nav matches a token in <body data-page="…"> get aria-current="page".
// Safe to run any number of times. Usage: node tools/sync-partials.mjs
import { readFile, writeFile, readdir } from "node:fs/promises";
import { join, relative, dirname, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const names = ["head", "header", "footer"];
const partials = Object.fromEntries(
  await Promise.all(names.map(async (n) => [n, await readFile(join(root, "partials", `${n}.html`), "utf8")]))
);

async function pages(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".")) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (["partials", "tools", "assets", "node_modules"].includes(entry.name)) continue;
      out.push(...(await pages(full)));
    } else if (entry.name.endsWith(".html")) {
      out.push(full);
    }
  }
  return out;
}

let changed = 0;
const files = await pages(root);
for (const file of files) {
  const depth = relative(root, file).split(sep).length - 1;
  const prefix = "../".repeat(depth);
  const html = await readFile(file, "utf8");
  const tokens = (html.match(/<body[^>]*\sdata-page="([^"]+)"/)?.[1] ?? "").split(/\s+/).filter(Boolean);

  let out = html;
  for (const name of names) {
    const marker = new RegExp(`(<!-- ${name}:start -->)[\\s\\S]*?(<!-- ${name}:end -->)`);
    if (!marker.test(out)) continue;
    let body = partials[name].replaceAll("{{root}}", prefix).trim();
    for (const token of tokens) {
      body = body.replaceAll(`data-nav="${token}"`, `data-nav="${token}" aria-current="page"`);
    }
    out = out.replace(marker, (_, start, end) => `${start}\n${body}\n${end}`);
  }
  if (out !== html) {
    await writeFile(file, out);
    changed++;
    console.log(`updated ${relative(root, file)}`);
  }
}
console.log(`${files.length} pages checked, ${changed} updated`);
