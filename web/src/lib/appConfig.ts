/**
 * Runtime config the server injects into index.html when it serves the SPA
 * (window.__APP_CONFIG__, see server routes/webapp.py — T-60/T-61). Absent in
 * dev (Vite serves index.html untouched), so every reader falls back to the
 * domain-root, registration-enabled defaults. Read lazily (functions, not
 * module constants) so tests can stub the global per-case.
 */

interface AppConfig {
  basename?: string;
  allowRegistration?: boolean;
}

declare global {
  interface Window {
    __APP_CONFIG__?: AppConfig;
  }
}

/** The mount root the server serves us under ("" = domain root), e.g. "/shopping". */
export function appBasename(): string {
  return (typeof window !== "undefined" && window.__APP_CONFIG__?.basename) || "";
}

/** Whether this server accepts new-account registration (T-61). */
export function allowRegistration(): boolean {
  if (typeof window === "undefined") return true;
  return window.__APP_CONFIG__?.allowRegistration !== false;
}
