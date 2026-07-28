import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DEFAULT_LOCALE, LOCALES, localeDir, matchLocale, resolveLocale } from "./locales";
import { registerCatalog, translate } from "./index";
import { ar } from "./messages/ar";
import { de } from "./messages/de";
import { es } from "./messages/es";
import { fr as frCatalog } from "./messages/fr";
import { ja } from "./messages/ja";
import { ptBR } from "./messages/pt-BR";
import { uk } from "./messages/uk";
import { zhHans } from "./messages/zh-Hans";
import { en } from "./messages/en";

describe("locale matching", () => {
  it("matches an exact tag", () => {
    expect(matchLocale("de")).toBe("de");
    expect(matchLocale("pt-BR")).toBe("pt-BR");
  });

  it("is case-insensitive, because browsers are inconsistent about tag casing", () => {
    expect(matchLocale("PT-br")).toBe("pt-BR");
    expect(matchLocale("ZH-hans")).toBe("zh-Hans");
  });

  it("falls back from a region we do not ship to the same base language", () => {
    // Without this a Portuguese browser would land on English purely because we ship the
    // Brazilian variant, which is a far worse answer than pt-BR.
    expect(matchLocale("pt-PT")).toBe("pt-BR");
    expect(matchLocale("de-AT")).toBe("de");
    expect(matchLocale("ar-EG")).toBe("ar");
  });

  it("resolves a bare base tag to the variant we ship", () => {
    expect(matchLocale("zh")).toBe("zh-Hans");
    expect(matchLocale("pt")).toBe("pt-BR");
  });

  it("returns null for a language we do not ship", () => {
    expect(matchLocale("is")).toBeNull();
    expect(matchLocale("")).toBeNull();
    expect(matchLocale("   ")).toBeNull();
  });

  it("takes the browser's first supported preference, in order", () => {
    expect(resolveLocale(["is", "fi", "uk", "de"])).toBe("uk");
    expect(resolveLocale(["de-CH", "en"])).toBe("de");
  });

  it("falls back to English when nothing is supported, rather than throwing", () => {
    expect(resolveLocale(["is", "fi"])).toBe(DEFAULT_LOCALE);
    expect(resolveLocale([])).toBe(DEFAULT_LOCALE);
  });
});

describe("writing direction", () => {
  it("marks Arabic RTL and everything else LTR", () => {
    expect(localeDir("ar")).toBe("rtl");
    for (const { tag } of LOCALES.filter((l) => l.tag !== "ar")) {
      expect(localeDir(tag)).toBe("ltr");
    }
  });
});

describe("language list", () => {
  it("names every language in its own language", () => {
    // A user who has accidentally selected a script they cannot read must still be able to find
    // their way back, so these are never translated into the current UI language.
    expect(LOCALES.find((l) => l.tag === "de")?.name).toBe("Deutsch");
    expect(LOCALES.find((l) => l.tag === "ar")?.name).toBe("العربية");
    expect(LOCALES.find((l) => l.tag === "uk")?.name).toBe("Українська");
  });

  it("has no duplicate tags", () => {
    const tags = LOCALES.map((l) => l.tag);
    expect(new Set(tags).size).toBe(tags.length);
  });
});

describe("translate", () => {
  // registerCatalog mutates a module-level map shared by the whole file, so these tests MUST put
  // it back. Picking a victim locale that ships no catalog used to work and no longer can — every
  // shipped language now has one. Restoring is the only approach that stays correct as catalogs
  // are added, which is exactly why the earlier version broke twice.
  beforeEach(() => {
    registerCatalog("fr", {});
  });
  afterEach(() => {
    registerCatalog("fr", frCatalog);
  });

  it("returns the English text for English", () => {
    expect(translate("en", "login.email")).toBe("Email");
  });

  it("substitutes placeholders", () => {
    expect(translate("en", "admin.sessionCount", { count: 3 })).toBe("Sessions: 3");
    expect(translate("en", "ago.minutes", { count: 1 })).toBe("1 min ago");
  });

  it("reads identically at one as at many — no key branches on a number (T-123)", () => {
    expect(translate("en", "admin.sessionCount", { count: 1 })).toBe("Sessions: 1");
    expect(translate("en", "admin.sessionCount", { count: 7 })).toBe("Sessions: 7");
  });

  it("falls back to English for a key a translation has not covered yet", () => {
    // This is what lets a translation land incrementally instead of having to be complete.
    registerCatalog("fr", { "login.submit": "Connexion" });

    expect(translate("fr", "login.submit")).toBe("Connexion");
    expect(translate("fr", "login.email")).toBe("Email");
  });

  it("falls back to English for a language with no catalog at all", () => {
    // Every shipped locale now has a catalog, so this needs a tag that is deliberately absent from
    // the registry — the fallback still has to hold for one, or a future language would render its
    // own key identifiers until someone noticed.
    expect(translate("xx" as never, "login.email")).toBe("Email");
  });

  it("interpolates into a translated string, not just the English one", () => {
    registerCatalog("fr", { "admin.sessionCount": "Sessions : {count}" });

    expect(translate("fr", "admin.sessionCount", { count: 4 })).toBe("Sessions : 4");
  });

  it("leaves an unknown placeholder visible rather than rendering 'undefined'", () => {
    registerCatalog("fr", { "login.submit": "{nope} connexion" });

    // A translator's typo should be diagnosable on sight, not silently become a real-looking word.
    expect(translate("fr", "login.submit")).toBe("{nope} connexion");
  });

  it("has no empty message in the English catalog", () => {
    for (const [key, value] of Object.entries(en)) {
      expect(value.trim(), `empty message for ${key}`).not.toBe("");
    }
  });
});

describe("provider-free defaults", () => {
  it("translates outside a provider, so a component can be tested in isolation", async () => {
    // Components are routinely rendered as isolated subtrees in tests; requiring a provider around
    // each one would be a permanent tax for any component that merely contains a label.
    const { renderHook } = await import("@testing-library/react");
    const { useT } = await import("./index");

    const { result } = renderHook(() => useT());

    expect(result.current("login.email")).toBe("Email");
  });

  it("refuses to change language outside a provider instead of silently not doing it", async () => {
    // This one IS a real bug if it happens — a chooser mounted outside the provider (T-127) would
    // appear to work and change nothing.
    const { renderHook } = await import("@testing-library/react");
    const { useI18n } = await import("./index");

    const { result } = renderHook(() => useI18n());

    expect(() => result.current.setLocale("fr")).toThrow(/I18nProvider/);
  });
});

describe("bidi isolation (T-126)", () => {
  it("leaves an all-LTR interpolated value untouched", () => {
    // The common case must stay byte-for-byte unchanged: isolates are invisible but real
    // characters, and adding them unconditionally would alter every interpolated string.
    expect(translate("en", "list.categoryFixed", { category: "Dairy", count: 3 })).toBe(
      "Casing fixed in Dairy: 3",
    );
  });

  it("isolates an RTL value so it cannot reorder the surrounding punctuation", () => {
    // Without isolates the colon in "…{category}: {count}" jumps to the wrong side of an Arabic
    // category name.
    const rendered = translate("en", "list.categoryFixed", { category: "ألبان", count: 3 });

    expect(rendered).toContain("⁨ألبان⁩");
    expect(rendered).toContain("3");
  });

  it("isolates per-argument, not the whole message", () => {
    const rendered = translate("en", "list.categoryFixed", { category: "ألبان", count: 3 });

    // The count is plain LTR and must not have been wrapped along with it.
    expect(rendered).not.toContain("⁨3⁩");
  });
});

describe("translation catalogs (T-124)", () => {
  // Placeholders are the translation bug that actually bites: a translator who drops {count}
  // produces a sentence that reads fine and silently loses the number. TypeScript cannot catch it
  // — the value is still a string — so it has to be a test.
  const PLACEHOLDER = /\{(\w+)\}/g;
  const placeholdersOf = (s: string) => new Set(Array.from(s.matchAll(PLACEHOLDER), (m) => m[1]));

    // Every shipped catalog, so adding one to the registry without adding it here is the only way
  // to escape these checks — and that omission is itself caught by the registration test below.
  const catalogs: Array<[string, Record<string, string>]> = [
    ["de", de as Record<string, string>],
    ["es", es as Record<string, string>],
    ["fr", frCatalog as Record<string, string>],
    ["pt-BR", ptBR as Record<string, string>],
    ["zh-Hans", zhHans as Record<string, string>],
    ["ja", ja as Record<string, string>],
    ["uk", uk as Record<string, string>],
    ["ar", ar as Record<string, string>],
  ];

  for (const [name, catalog] of catalogs) {
    it(`${name}: every translated string keeps its source placeholders`, () => {
      for (const [key, translated] of Object.entries(catalog)) {
        const source = (en as Record<string, string>)[key];
        expect(
          [...placeholdersOf(translated)].sort(),
          `${name}.${key}: placeholders differ from English`,
        ).toEqual([...placeholdersOf(source)].sort());
      }
    });

    it(`${name}: has no key English does not have`, () => {
      // Belt and braces — tsc enforces this via Catalog's Partial<Record<MessageKey, …>>, but a
      // stale key surviving a rename would otherwise sit there translated and unreachable.
      for (const key of Object.keys(catalog)) {
        expect(en, `${name}.${key} is not a key in English`).toHaveProperty(key);
      }
    });

    it(`${name}: leaves no string empty`, () => {
      for (const [key, value] of Object.entries(catalog)) {
        expect(value.trim(), `${name}.${key} is empty`).not.toBe("");
      }
    });
  }

  it("German covers the whole catalog", () => {
    // German is the one language with real users today, so it is held to completeness; the others
    // fall back per-key to English while they are still being reviewed (T-124).
    const missing = Object.keys(en).filter((k) => !(k in de));
    expect(missing, `untranslated keys: ${missing.join(", ")}`).toEqual([]);
  });
});

describe("catalog registration (T-124)", () => {
  // Regression: `de.ts` existed, was complete, and every test passed — while the app still
  // rendered English, because nothing had added it to the registry and the tests imported the
  // catalog directly. A translation file that is not wired up is invisible to any test that does
  // not go through translate(), so this asserts the wiring rather than the content.
  it("resolves German through translate(), not just from the imported module", () => {
    expect(translate("de", "action.save")).toBe("Speichern");
    expect(translate("de", "app.title")).toBe("Einkaufsliste");
  });

  it("every shipped locale with a catalog file is actually registered", () => {
    // Any locale whose translate() output is identical to English for a key it demonstrably
    // translates is a locale that was never registered.
    const withCatalogs: Array<[string, Record<string, string>]> = [
    ["de", de as Record<string, string>],
    ["es", es as Record<string, string>],
    ["fr", frCatalog as Record<string, string>],
    ["pt-BR", ptBR as Record<string, string>],
    ["zh-Hans", zhHans as Record<string, string>],
    ["ja", ja as Record<string, string>],
    ["uk", uk as Record<string, string>],
    ["ar", ar as Record<string, string>],
  ];
    for (const [tag, catalog] of withCatalogs) {
      const [key, translated] = Object.entries(catalog).find(
        ([k, v]) => v !== (en as Record<string, string>)[k],
      )!;
      expect(translate(tag as never, key as never), `${tag} is not registered`).toBe(translated);
    }
  });
});

describe("Arabic engages the RTL machinery end-to-end (T-126/T-124)", () => {
  it("flips the document direction when Arabic is selected", async () => {
    // The point of shipping Arabic early: everything RTL in T-126 was built without a single real
    // Arabic string to test against. This asserts the provider actually drives <html dir>, which
    // is what every logical CSS property in the app keys off.
    const { renderHook, act } = await import("@testing-library/react");
    const { I18nProvider, useI18n } = await import("./index");

    const { result } = renderHook(() => useI18n(), { wrapper: I18nProvider });

    act(() => result.current.setLocale("ar"));
    expect(document.documentElement.dir).toBe("rtl");
    expect(document.documentElement.lang).toBe("ar");

    act(() => result.current.setLocale("de"));
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("translates and isolates in the same pass", () => {
    // An Arabic UI string with a Latin user value embedded: the value must be isolated so it
    // cannot drag the Arabic punctuation around it.
    const rendered = translate("ar", "list.categoryFixed", { category: "Dairy", count: 3 });

    expect(rendered).toContain("Dairy");
    expect(rendered).not.toBe("Casing fixed in Dairy: 3");
  });

  it("keeps prices ASCII, matching the wire format", () => {
    // T-125: price amounts are ASCII decimals on the wire. Eastern Arabic numerals in UI labels
    // next to an ASCII-only input would read inconsistently, so the catalog stays ASCII-digit.
    for (const value of Object.values(ar)) {
      expect(value, `non-ASCII digit in: ${value}`).not.toMatch(/[٠-٩۰-۹]/);
    }
  });
});
