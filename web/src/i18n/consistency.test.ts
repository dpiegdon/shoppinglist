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
 * Consistency of the WEB catalogs against each other and against English (T-124): no language
 * translates the same English source two different ways.
 *
 * The cross-CLIENT check — web against the Android resources, in all nine languages — is
 * crossClient.test.ts (T-148). It replaced a hand-kept snapshot of Android's German here, which had
 * drifted, and a comment claiming the Android suite did this, which it never did.
 */
const CATALOGS: Array<[string, Record<string, string>]> = [
  ["de", de as Record<string, string>],
  ["es", es as Record<string, string>],
  ["fr", fr as Record<string, string>],
  ["pt-BR", ptBR as Record<string, string>],
  ["zh-Hans", zhHans as Record<string, string>],
  ["ja", ja as Record<string, string>],
  ["uk", uk as Record<string, string>],
  ["ar", ar as Record<string, string>],
];

const source = en as Record<string, string>;
// Both placeholder dialects collapse to the same token: the web catalogs use {name} and the
// Android resources use %1$s / %1$d, so a naive comparison would report every parameterised
// string as a mismatch.
const normalise = (v: string) =>
  v.replace(/\{(\w+)\}/g, "@").replace(/%\d+\$[sd]/g, "@").trim();

describe("catalog self-consistency (T-124)", () => {
  for (const [tag, catalog] of CATALOGS) {
    it(`${tag}: never translates the same English source two different ways`, () => {
      // A user meeting "Speichern" on one screen and "Sichern" on the next reads it as two
      // different actions. Nobody in this repo can spot that in Japanese or Ukrainian by eye,
      // so it has to be mechanical.
      const bySource = new Map<string, Set<string>>();
      for (const [key, value] of Object.entries(catalog)) {
        const src = source[key];
        if (!src) continue;
        const bucket = bySource.get(normalise(src)) ?? new Set();
        bucket.add(normalise(value));
        bySource.set(normalise(src), bucket);
      }
      const split = [...bySource.entries()]
        .filter(([, vs]) => vs.size > 1)
        .map(([src, vs]) => `${src} -> ${[...vs].join(" | ")}`);
      expect(split, `${tag} renders one English string several ways`).toEqual([]);
    });
  }
});
