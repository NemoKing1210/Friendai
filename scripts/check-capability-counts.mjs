import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const source = readFileSync(new URL("../site/app.js", import.meta.url), "utf8");
function section(start, end) {
  const first = source.indexOf(start);
  const last = source.indexOf(end, first);
  assert.ok(first >= 0 && last > first, "Missing capability map code: " + start);
  return source.slice(first, last);
}

const stats = {
  rows: [],
  set textContent(value) { this.rows = []; },
  append(row) { this.rows.push(row.children.map(cell => cell.textContent)); },
};
const rendered = runInNewContext(
  section("const COLORS =", "const FLOW =") +
  section("const parseItem =", "function shade(") +
  section("const toolCount =", "function overviewGraph(") +
  `
    const actual = () => {
      buildStats();
      return Object.fromEntries($().rows.map(([value, label]) => [label, Number(value)]));
    };
    const all = CATS.flatMap(c => c.items);
    const names = new Set(all.filter(item => item[0] !== "~").map(item => item.split("|")[0]));
    const renderedNow = actual();
    const categoryCounts = CATS.map(c => [toolCount(c), c.items.filter(item => item[0] !== "~").length]);
    CATS.push({items: [all.find(item => item[0] !== "~"), "~built-in|trait", "__probe__|one new tool"]});
    ({renderedNow, expectedTools: names.size, expectedCategories: CATS.length - 1, categoryCounts, withProbe: actual()});
  `,
  {
    $: () => stats,
    t: text => text,
    countLabel: (_, label) => label,
    document: { createElement: () => ({ children: [], append(...elements) { this.children.push(...elements); } }) },
  },
);

assert.equal(rendered.renderedNow.tools, rendered.expectedTools);
assert.equal(rendered.renderedNow.categories, rendered.expectedCategories);
assert.ok(rendered.categoryCounts.every(([actual, expected]) => actual === expected));
assert.equal(rendered.withProbe.tools, rendered.expectedTools + 1);
assert.equal(rendered.withProbe.categories, rendered.expectedCategories + 1);
console.log("Capability map counts: " + rendered.expectedTools + " tools and " + rendered.expectedCategories + " categories.");
