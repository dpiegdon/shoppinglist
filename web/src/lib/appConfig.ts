/**
 * Runtime config the server injects into index.html when it serves the SPA
 * (see server routes/webapp.py — T-60/T-61). Carried as <meta> tags, NOT an
 * inline script: the security CSP's strict script-src blocks inline scripts in
 * a real browser, but meta content isn't governed by it. Absent in dev (Vite
 * serves index.html untouched), so every reader falls back to the domain-root,
 * registration-enabled defaults. Read lazily so tests can inject meta per-case.
 */

function metaContent(name: string): string | null {
  if (typeof document === "undefined") return null;
  return document.querySelector(`meta[name="${name}"]`)?.getAttribute("content") ?? null;
}

/** The mount root the server serves us under ("" = domain root), e.g. "/shopping". */
export function appBasename(): string {
  return metaContent("app-basename") ?? "";
}

/** Whether this server accepts new-account registration (T-61). */
export function allowRegistration(): boolean {
  return metaContent("app-allow-registration") !== "false";
}
