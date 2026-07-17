/**
 * Result of parsing a user-typed price amount/currency field. Mirrors Android's
 * `PriceParse` sealed interface (ItemFormViewModel.kt): `value` is `null` when the field was blank
 * (no price/currency at all), a non-null string when a value was recognized, or `Invalid` with a
 * message to show inline when the value can't be interpreted at all.
 */
export type PriceParseResult = { valid: true; value: string | null } | { valid: false; message: string };

const CURRENCY_SYMBOLS_RE = /[\s€$£¥]/g;
const PRICE_AMOUNT_RE = /^\d+(\.\d{1,2})?$/;
const CURRENCY_RE = /^[A-Z]{3}$/;

/**
 * Normalizes a typed amount to the server's decimal-string format: accepts a comma decimal
 * separator and strips whitespace + currency symbols ("1,99", "2€", " 1.50 " -> "1.99"/"2"/"1.50"),
 * then requires `\d+(\.\d{1,2})?` — the same shape the server validates on push. Blank -> valid
 * with a null value (no price). Anything else (letters, >2 decimals) -> invalid with a message to
 * show inline, so a bad value is caught before it's pushed and 422'd (which would wedge the queue,
 * T-32). Mirrors Android's `parsePriceAmount`.
 */
export function parsePriceAmount(raw: string): PriceParseResult {
  const cleaned = raw.trim().replace(/,/g, ".").replace(CURRENCY_SYMBOLS_RE, "");
  if (!cleaned) return { valid: true, value: null };
  return PRICE_AMOUNT_RE.test(cleaned)
    ? { valid: true, value: cleaned }
    : { valid: false, message: "Enter an amount like 1.99" };
}

/**
 * Uppercases and validates a 3-letter ISO-4217 code; blank -> valid with a null value (no
 * currency). Mirrors Android's `parseCurrency`.
 */
export function parseCurrency(raw: string): PriceParseResult {
  const code = raw.trim().toUpperCase();
  if (!code) return { valid: true, value: null };
  return CURRENCY_RE.test(code)
    ? { valid: true, value: code }
    : { valid: false, message: "Use a 3-letter code like EUR" };
}
