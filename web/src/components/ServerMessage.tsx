import type { CSSProperties } from "react";

/**
 * The admin's one-line server message (T-315), on the login page and above the overview's lists.
 *
 * Plain text in a React element, never markup and never a linkifier: the server lets an admin
 * write a URL, and the rule is that no client makes it clickable. Not dismissable. Renders nothing
 * for no message, so a caller can pass the value straight through.
 */
export default function ServerMessage({ message, style }: { message: string | null | undefined; style?: CSSProperties }) {
  if (!message) return null;
  return (
    <p className="info-banner" dir="auto" data-testid="server-message" style={style}>
      {message}
    </p>
  );
}
