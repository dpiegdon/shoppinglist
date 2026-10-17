import { act, render, renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { I18nProvider, useI18n } from "../i18n";
import type { Locale } from "../i18n";
import { safeLocalStorage } from "../lib/safeStorage";
import { useFormat } from "../lib/format";
import LanguagePicker from "./LanguagePicker";

/** The picker as a user in [locale] sees it, once the provider has switched to it. */
function pickerIn(locale: Locale) {
  let setLocale: (next: Locale) => void = () => {};
  function Capture() {
    setLocale = useI18n().setLocale;
    return null;
  }
  render(
    <I18nProvider>
      <Capture />
      <LanguagePicker />
    </I18nProvider>,
  );
  act(() => setLocale(locale));
  return Array.from((screen.getByRole("combobox") as HTMLSelectElement).options);
}

describe("the Emoji language in the picker (T-359)", () => {
  afterEach(() => safeLocalStorage.removeItem("shoppinglist_locale"));

  it("is the last entry and says what it is in the current language", () => {
    const options = pickerIn("ja");
    const last = options[options.length - 1];
    expect(last.value).toBe("qaa");
    expect(last.textContent).toBe("😀🔤 (絵文字)");
    // The real languages keep their own names alone.
    expect(options.find((o) => o.value === "de")?.textContent).toBe("Deutsch");
  });

  it("reads Emoji in English", () => {
    const options = pickerIn("en");
    expect(options[options.length - 1].textContent).toBe("😀🔤 (Emoji)");
  });

  it("is its own name alone while the app shows it", () => {
    const options = pickerIn("qaa");
    expect(options[options.length - 1].textContent).toBe("😀🔤");
    expect(document.documentElement.lang).toBe("qaa");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("writes amounts and dates as English", () => {
    // Asked for by name: this test environment's own default is English, which Intl would also
    // fall back to for "qaa", but a German browser's would not be.
    const asked: unknown[] = [];
    const { NumberFormat, DateTimeFormat } = Intl;
    Intl.NumberFormat = class extends NumberFormat {
      constructor(...args: ConstructorParameters<typeof NumberFormat>) {
        asked.push(args[0]);
        super(...args);
      }
    } as typeof NumberFormat;
    Intl.DateTimeFormat = class extends DateTimeFormat {
      constructor(...args: ConstructorParameters<typeof DateTimeFormat>) {
        asked.push(args[0]);
        super(...args);
      }
    } as typeof DateTimeFormat;
    try {
      const wrapper = ({ children }: { children: ReactNode }) => <I18nProvider>{children}</I18nProvider>;
      const { result } = renderHook(() => ({ fmt: useFormat(), i18n: useI18n() }), { wrapper });
      act(() => result.current.i18n.setLocale("qaa"));
      expect(result.current.fmt.money(6400, "EUR")).toBe("€64.00");
      expect(result.current.fmt.number(150000)).toBe("1,500.00");
      expect(result.current.fmt.date("2026-09-17")).toBe("Sep 17, 2026");
      expect(result.current.fmt.shortDate("2027-01-05", "2026-09-28")).toBe("Jan 5, 2027");
      expect(asked.length).toBeGreaterThan(0);
      expect(asked.every((tag) => tag === "en"), `asked for ${asked.join(", ")}`).toBe(true);
    } finally {
      Intl.NumberFormat = NumberFormat;
      Intl.DateTimeFormat = DateTimeFormat;
    }
  });
});
