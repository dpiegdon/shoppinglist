/**
 * A bulleted list, the icon of "All items" (T-338): Android's Icons.AutoMirrored.Filled.List, so it
 * is not mistaken for the ☰ of the app menu. Drawn in the text colour around it and mirrored in a
 * right-to-left language, as the Android icon is. Decorative: the link carries the label.
 */
export default function ListIcon() {
  return (
    <svg className="icon-auto-mirror" viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true" focusable="false">
      <path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z" />
    </svg>
  );
}
