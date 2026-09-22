import type { MessageKey } from "../i18n/messages/en";

/**
 * Result of parsing a user-typed price amount/currency field. Mirrors Android's
 * `PriceParse` sealed interface (ItemFormViewModel.kt): `value` is `null` when the field was blank
 * (no price/currency at all), a non-null string when a value was recognized, or `Invalid` with a
 * message to show inline when the value can't be interpreted at all.
 *
 * `message` is a catalog KEY, not English text (T-270) — this module has no access to the current
 * locale, so it names which message applies and leaves translating it to the call site (`t(...)`).
 */
export type PriceParseResult = { valid: true; value: string | null } | { valid: false; message: MessageKey };

const WHITESPACE_RE = /\s/g;
const LEADING_CURRENCY_RE = /^[€$£¥]/;
const TRAILING_CURRENCY_RE = /[€$£¥]$/;
// [0-9] rather than \d, to stay explicitly in step with the server (T-125). JS's \d is already
// ASCII-only so this is a no-op here — but the server's Python \d is NOT, which is exactly how the
// three copies of this "identical" pattern came to mean different things. Spelling it out removes
// the trap rather than relying on each language's default.
// Siblings: server sync.py PRICE_AMOUNT_RE, android ItemFormViewModel.kt PRICE_AMOUNT_RE.
// The whole part is capped at 13 digits (T-262): kept in step with expenses.ts's `toCents` regex,
// whose comment explains the bound.
const PRICE_AMOUNT_RE = /^[0-9]{1,13}(\.[0-9]{1,2})?$/;
const CURRENCY_RE = /^[A-Z]{3}$/;

/**
 * Normalizes a typed amount to the server's decimal-string format: accepts a comma decimal
 * separator, strips all whitespace, and strips a single leading or trailing currency symbol
 * ("1,99", "2€", "€1.50", " 1.50 " -> "1.99"/"2"/"1.50"/"1.50"), then requires `\d+(\.\d{1,2})?` —
 * the same shape the server validates on push. A symbol is only stripped from the ends (T-275): an
 * embedded symbol ("1€5") is left in place and correctly rejected, rather than silently deleted to
 * make "15". Android's `parsePriceAmount` used to strip a symbol from anywhere in the string —
 * fixed to match this, the safer of the two grammars, rather than the other way round. Blank ->
 * valid with a null value (no price). Anything else (letters, embedded symbols, >2 decimals, too
 * many digits) -> invalid with a message to show inline, so a bad value is caught before it's
 * pushed and 422'd (which would wedge the queue, T-32). Pinned by
 * shared-test-cases/price-parse.json, driven by both suites.
 */
export function parsePriceAmount(raw: string): PriceParseResult {
  const cleaned = raw
    .replace(/,/g, ".")
    .replace(WHITESPACE_RE, "")
    .replace(LEADING_CURRENCY_RE, "")
    .replace(TRAILING_CURRENCY_RE, "");
  if (!cleaned) return { valid: true, value: null };
  return PRICE_AMOUNT_RE.test(cleaned)
    ? { valid: true, value: cleaned }
    : { valid: false, message: "item.priceInvalid" };
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
    : { valid: false, message: "item.currencyInvalid" };
}
