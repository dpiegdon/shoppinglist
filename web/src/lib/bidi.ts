/**
 * Wraps a name in Unicode first-strong isolates (FSI … PDI) before it goes into a sentence, so a
 * name written right to left cannot reorder the words and arrows around it: "{from} → {to}" with
 * two Arabic names otherwise reads back to front. The sentence itself then takes the page's
 * direction, which is why the Arabic catalogue's arrow points left. Android's bidiIsolate does the
 * same.
 */
export function bidiIsolate(text: string): string {
  return `⁨${text}⁩`;
}
