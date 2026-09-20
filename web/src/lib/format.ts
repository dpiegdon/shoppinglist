import { useMemo } from "react";
import { useI18n } from "../i18n";

/**
 * Amounts and dates in the app's chosen language (T-180, T-187), rather than one fixed format:
 * 64,00 € in German, €64.00 in English, and dates as each language writes them.
 *
 * Display only. Input fields keep the plain 64.00 / 2026-09-17 forms they parse, so what a user
 * types is never reinterpreted by their language setting.
 */

/** Only an ISO code gets currency formatting; a free-text label (a list's own unit) does not. */
const ISO_CURRENCY = /^[A-Z]{3}$/;

/** 64,00 € / €64.00 — or "12,00 pizza slices" when the label is not a currency code. */
export function formatMoney(cents: number, currency: string | null | undefined, locale: string): string {
  return money(cents, currency, locale, "auto");
}

/**
 * [formatMoney] with a "+" on a credit: +64,00 €, and −32,00 € unchanged (T-241). A balance's
 * colour is never its only signal, so the sign carries the same meaning for a reader who cannot
 * tell the green from the red. The language places the sign, so Arabic gets its plus the way it
 * gets its minus, with the bidi marks that keep an RTL line in order.
 */
export function formatSignedMoney(cents: number, currency: string | null | undefined, locale: string): string {
  return money(cents, currency, locale, "exceptZero");
}

function money(
  cents: number,
  currency: string | null | undefined,
  locale: string,
  signDisplay: "auto" | "exceptZero",
): string {
  const label = (currency ?? "").trim();
  if (ISO_CURRENCY.test(label)) {
    try {
      // A currency's own precision (none for yen), unless the amount really has cents to show.
      const exact = cents % 100 === 0 ? {} : { minimumFractionDigits: 2, maximumFractionDigits: 2 };
      return new Intl.NumberFormat(locale, {
        style: "currency",
        currency: label,
        signDisplay,
        ...exact,
      }).format(cents / 100);
    } catch {
      // A three-letter label Intl does not know as a currency: shown as a label below.
    }
  }
  const number = formatNumber(cents, locale, signDisplay);
  return label ? `${number} ${label}` : number;
}

/** 64,00 / 64.00: the number alone, always with two decimals. */
export function formatNumber(cents: number, locale: string, signDisplay: "auto" | "exceptZero" = "auto"): string {
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
    signDisplay,
  }).format(cents / 100);
}

/**
 * A calendar date (YYYY-MM-DD, no time, no zone) as the language writes it. Formatted as a UTC
 * midnight in UTC, so no time zone can move it to the day before.
 */
export function formatCalendarDate(isoDate: string, locale: string): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  if (!year || !month || !day) return isoDate;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(
    Date.UTC(year, month - 1, day),
  );
}

/** The day a moment falls on, locally, in the same style as [formatCalendarDate]. */
export function formatDay(epochMillis: number, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(epochMillis);
}

/** The formatters bound to the current language. */
export function useFormat() {
  const { locale } = useI18n();
  return useMemo(
    () => ({
      money: (cents: number, currency: string | null | undefined) => formatMoney(cents, currency, locale),
      signedMoney: (cents: number, currency: string | null | undefined) => formatSignedMoney(cents, currency, locale),
      number: (cents: number) => formatNumber(cents, locale),
      date: (isoDate: string) => formatCalendarDate(isoDate, locale),
      day: (epochMillis: number) => formatDay(epochMillis, locale),
    }),
    [locale],
  );
}

/**
 * Owed is red, owing-to-you is green, square is grey — the same everywhere (T-182, T-241). Green
 * rather than the theme's accent: the accent belongs to headings and buttons, and a number that
 * means something should read by the colour everyone already reads money in.
 */
export function balanceColor(cents: number): string {
  if (cents < 0) return "var(--color-danger)";
  if (cents > 0) return "var(--color-positive)";
  return "var(--color-text-muted)";
}
