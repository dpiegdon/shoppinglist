import { beforeEach, describe, expect, it } from "vitest";
import { DEFAULT_LOCALE, LOCALES, localeDir, matchLocale, resolveLocale } from "./locales";
import { registerCatalog, translate } from "./index";
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
  beforeEach(() => {
    // Each test registers what it needs; start from a known-empty German catalog.
    registerCatalog("de", {});
  });

  it("returns the English text for English", () => {
    expect(translate("en", "action.cancel")).toBe("Cancel");
  });

  it("substitutes placeholders", () => {
    expect(translate("en", "admin.sessionCount", { count: 3 })).toBe("Sessions: 3");
    expect(translate("en", "lastSeen.minutes", { count: 1 })).toBe("1 min ago");
  });

  it("reads identically at one as at many — no key branches on a number (T-123)", () => {
    expect(translate("en", "admin.sessionCount", { count: 1 })).toBe("Sessions: 1");
    expect(translate("en", "admin.sessionCount", { count: 7 })).toBe("Sessions: 7");
  });

  it("falls back to English for a key a translation has not covered yet", () => {
    // This is what lets a translation land incrementally instead of having to be complete.
    registerCatalog("de", { "action.save": "Speichern" });

    expect(translate("de", "action.save")).toBe("Speichern");
    expect(translate("de", "action.cancel")).toBe("Cancel");
  });

  it("falls back to English for a language with no catalog at all", () => {
    expect(translate("ja", "action.cancel")).toBe("Cancel");
  });

  it("interpolates into a translated string, not just the English one", () => {
    registerCatalog("de", { "admin.sessionCount": "Sitzungen: {count}" });

    expect(translate("de", "admin.sessionCount", { count: 4 })).toBe("Sitzungen: 4");
  });

  it("leaves an unknown placeholder visible rather than rendering 'undefined'", () => {
    registerCatalog("de", { "action.save": "{nope} speichern" });

    // A translator's typo should be diagnosable on sight, not silently become a real-looking word.
    expect(translate("de", "action.save")).toBe("{nope} speichern");
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

    expect(result.current("action.cancel")).toBe("Cancel");
  });

  it("refuses to change language outside a provider instead of silently not doing it", async () => {
    // This one IS a real bug if it happens — a chooser mounted outside the provider (T-127) would
    // appear to work and change nothing.
    const { renderHook } = await import("@testing-library/react");
    const { useI18n } = await import("./index");

    const { result } = renderHook(() => useI18n());

    expect(() => result.current.setLocale("de")).toThrow(/I18nProvider/);
  });
});
