import { useEffect } from "react";
import { useT } from "../i18n";

/**
 * The browser tab's title for a page (T-338): what the page is, then the app, "Groceries · Tuppu",
 * so tabs and history entries can be told apart. Parts that are missing (a list not loaded yet) are
 * left out, and with none the title is the app's name alone. Leaving the page puts that back.
 */
export function useDocumentTitle(...parts: (string | null | undefined)[]): void {
  const t = useT();
  const app = t("app.title");
  const title = [...parts.filter((part): part is string => !!part && part.trim() !== ""), app].join(" · ");
  useEffect(() => {
    document.title = title;
    return () => {
      document.title = app;
    };
  }, [title, app]);
}
