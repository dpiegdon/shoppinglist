/// <reference types="node" />
// Node's file APIs, not a ?raw import: Vite refuses raw imports from outside web/ (its dev-server
// fs.allow rule), and loosening that rule for a test is the wrong trade. @types/node is already a
// dev dependency, for vite.config.ts.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { ar } from "./messages/ar";
import { de } from "./messages/de";
import { en } from "./messages/en";
import { es } from "./messages/es";
import { fr } from "./messages/fr";
import { ja } from "./messages/ja";
import { ptBR } from "./messages/pt-BR";
import { uk } from "./messages/uk";
import { zhHans } from "./messages/zh-Hans";

/**
 * The two clients read alike, in every language (T-148).
 *
 * Reads the Android resources straight from the repository — no snapshot, so nothing to drift — and
 * pairs a web message with an Android string when their English is the same text. Every such pair
 * must then say the same thing in all nine languages. A user switching between phone and browser
 * mid-shop should not meet two vocabularies.
 *
 * Pairing by English rather than by a hand-kept key map is the point: a map is one more thing to
 * forget, and a string one client rewords in English simply stops being paired, which the review
 * list below then shows.
 */

const RES = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../android/app/src/main/res");
const xmlOf = (dir: string) => readFileSync(path.join(RES, dir, "strings.xml"), "utf8");

const LOCALES: Array<[string, string, Record<string, string>]> = [
  ["en", xmlOf("values"), en as Record<string, string>],
  ["de", xmlOf("values-de"), de as Record<string, string>],
  ["es", xmlOf("values-es"), es as Record<string, string>],
  ["fr", xmlOf("values-fr"), fr as Record<string, string>],
  ["pt-BR", xmlOf("values-pt-rBR"), ptBR as Record<string, string>],
  ["zh-Hans", xmlOf("values-b+zh+Hans"), zhHans as Record<string, string>],
  ["ja", xmlOf("values-ja"), ja as Record<string, string>],
  ["uk", xmlOf("values-uk"), uk as Record<string, string>],
  ["ar", xmlOf("values-ar"), ar as Record<string, string>],
];

/** Android's <string> resources, decoded the way aapt decodes them. */
function androidStrings(xml: string): Map<string, string> {
  const doc = new DOMParser().parseFromString(xml, "text/xml");
  const strings = new Map<string, string>();
  for (const node of Array.from(doc.getElementsByTagName("string"))) {
    let value = node.textContent ?? "";
    // A value wrapped in double quotes keeps its whitespace; the quotes themselves are not text.
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.slice(1, -1);
    value = value.replace(/\\n/g, "\n").replace(/\\(['"@?\\])/g, "$1");
    strings.set(node.getAttribute("name") ?? "", value);
  }
  return strings;
}

/** Placeholders to one token (web {name}, Android %1$s), and every kind of space to one space. */
function normalise(value: string): string {
  return value
    .replace(/\{\w+\}/g, "@")
    .replace(/%(\d+\$)?[sd]/g, "@")
    .replace(/[\s  ]+/g, " ")
    .trim();
}

/**
 * Pairs whose translations are allowed to differ, each with the reason. Empty is the goal; an entry
 * here is a decision, not a way to make the test pass.
 */
const ALLOWED: Record<string, string> = {};

/**
 * Strings in the expense and error families that one client alone has, each with the reason (T-203).
 *
 * Pairing by English says nothing about a string the other client never wrote: expense.error.frozen
 * lived on the web alone for two releases and this file was happy. So the two families where the
 * clients must agree — the expense form and the server's error table — are also checked for a key
 * that pairs with nothing. Everything outside them (navigation labels, platform-specific screens)
 * is left alone: those differ by design and listing them all would be noise.
 */
const ONE_SIDED: Record<string, string> = {
  "expense.total": "the web labels the field 'Total' and puts the currency beside it; Android folds it into the label (expense_total_with_currency)",
  expense_total_with_currency: "the web's expense.total, with the currency in the label",
  "expense.error.total": "the web says 'Enter a total.' under the shares; Android leaves TOTAL_NOT_POSITIVE silent and keeps the Save button off",
  expense_total_spent_value: "one line on Android's balances screen, two stacked labels on the web's",
  expense_currency_value: "list properties reads the currency out on Android, where the web has a labelled field",
  expense_delete_title: "Android confirms deleting an EXPENSE ENTRY in a dialog; the web's delete button acts at once (unlike an item, T-277)",
  expense_delete_body: "the body of that same Android-only confirmation",
  // T-270 widened this check to the item.* family too. These three stay one-sided rather than
  // paired or removed:
  "item.saveFailed": "the web shows this after an optimistic push to the server fails inline; Android writes to its local mirror and syncs in the background, so there is no synchronous save failure to report here",
  item_msg_name_required: "Android blocks an empty name inline as you type; the web relies on the input's own required attribute and shows no message",
  item_msg_duplicate_name: "Android warns before saving over an existing item name; the web has no such guard",
  // T-304: where you delete your own account differs by client.
  "apiError.cannotDeleteSelf": "the web deletes your own account in Settings; Android does it on that account's page (api_error_cannot_delete_self)",
  api_error_cannot_delete_self: "the web's apiError.cannotDeleteSelf, naming the account's page instead of Settings",
};

describe("the two clients say the same thing (T-148)", () => {
  const androidEn = androidStrings(LOCALES[0][1]);
  const webEn = en as Record<string, string>;
  const byEnglish = new Map<string, string[]>();
  for (const [name, value] of androidEn) {
    const key = normalise(value);
    byEnglish.set(key, [...(byEnglish.get(key) ?? []), name]);
  }
  const pairs: Array<[string, string]> = [];
  for (const [webKey, value] of Object.entries(webEn)) {
    for (const name of byEnglish.get(normalise(value)) ?? []) pairs.push([webKey, name]);
  }

  it("no expense or error string lives on one client only (T-203)", () => {
    // T-270: widened to item.*/item_* after the price/currency messages and the delete
    // confirmation moved into the catalog on both sides — that family had the same silent-drift
    // risk (a string added to one client's item form with nothing to pair it on the other).
    const inFamily = (key: string) =>
      key.startsWith("expense.") || key.startsWith("apiError.") ||
      key.startsWith("expense_") || key.startsWith("api_error_") ||
      key.startsWith("item.") || key.startsWith("item_");
    const pairedWeb = new Set(pairs.map(([webKey]) => webKey));
    const pairedAndroid = new Set(pairs.map(([, name]) => name));
    const lonely = [
      ...Object.keys(webEn).filter((key) => !pairedWeb.has(key)),
      ...[...androidEn.keys()].filter((name) => !pairedAndroid.has(name)),
    ].filter((key) => inFamily(key) && !ONE_SIDED[key]);
    expect(lonely, `${lonely.length} strings have no twin on the other client`).toEqual([]);
  });

  it("pairs a meaningful share of the strings", () => {
    // A guard on the guard: if parsing broke, there would be nothing to compare and every check
    // below would pass on nothing.
    expect(pairs.length).toBeGreaterThan(100);
  });

  for (const [tag, , catalog] of LOCALES.slice(1)) {
    it(`${tag}: has every English key — a gap would silently show English`, () => {
      // Catalog is Partial by design so a missing key degrades to English at runtime; this is what
      // keeps that fallback from being where a translation quietly went missing.
      const missing = Object.keys(webEn).filter((key) => !catalog[key]);
      expect(missing).toEqual([]);
    });

    it(`${tag}: keeps every placeholder English has`, () => {
      const names = (v: string) => (v.match(/\{\w+\}/g) ?? []).sort().join(" ");
      const broken = Object.keys(webEn).filter((key) => catalog[key] && names(catalog[key]) !== names(webEn[key]));
      expect(broken).toEqual([]);
    });
  }

  for (const [tag, xml, catalog] of LOCALES.slice(1)) {
    it(`${tag}: every shared string reads the same on both clients`, () => {
      const android = androidStrings(xml);
      const differ = pairs
        .filter(([webKey, name]) => !ALLOWED[`${webKey}|${name}`])
        .filter(([webKey, name]) => normalise(catalog[webKey] ?? "") !== normalise(android.get(name) ?? ""))
        .map(([webKey, name]) => `${webKey} | ${name}: web "${catalog[webKey]}" / android "${android.get(name)}"`);
      expect(differ, `${tag}: ${differ.length} shared strings differ`).toEqual([]);
    });
  }
});
