#!/usr/bin/env node
// Fails (exit 1) if any page links to a local file or #fragment that doesn't exist.
// External links (http, mailto, tel) are skipped. Usage: node tools/check-links.mjs
import { readFile, readdir, stat } from "node:fs/promises";
import { join, relative, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".")) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (["partials", "tools", "node_modules"].includes(entry.name)) continue;
      out.push(...(await walk(full)));
    } else if (entry.name.endsWith(".html")) out.push(full);
  }
  return out;
}

const exists = async (p) => stat(p).then(() => true, () => false);
const idCache = new Map();
async function ids(file) {
  if (!idCache.has(file)) {
    const html = await readFile(file, "utf8");
    idCache.set(file, new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
  }
  return idCache.get(file);
}

let broken = 0;
let checked = 0;
for (const file of await walk(root)) {
  const html = (await readFile(file, "utf8")).replace(/<!--[\s\S]*?-->/g, "");
  for (const [, attr, value] of html.matchAll(/\s(href|src)="([^"]*)"/g)) {
    if (!value || /^(https?:|mailto:|tel:|data:|javascript:)/.test(value)) continue;
    checked++;
    const [pathPart, hash] = value.split("#");
    const target = pathPart ? resolve(dirname(file), pathPart.split("?")[0]) : file;
    const where = `${relative(root, file)} → ${attr}="${value}"`;
    if (!(await exists(target))) {
      console.error(`missing file  ${where}`);
      broken++;
      continue;
    }
    if (hash && target.endsWith(".html") && !(await ids(target)).has(hash)) {
      console.error(`missing #id   ${where}`);
      broken++;
    }
  }
}
console.log(`${checked} local links checked, ${broken} broken`);
process.exit(broken ? 1 : 0);
