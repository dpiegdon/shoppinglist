import { useT } from "../i18n";

/**
 * The full-page notice shown when this bundle is too old for the server and reloading has already
 * been tried (T-240).
 *
 * It replaces the whole app rather than sitting over it: every request now comes back 426, so a
 * screen behind it could only show stale data and a sync that keeps failing. Rendering this
 * instead of the routes unmounts the sync hook with them, which is what stops the syncing — there
 * is no separate "paused" flag to get out of step with what is on screen.
 */
export default function UpdateRequiredNotice() {
  const t = useT();
  return (
    <main
      style={{
        minHeight: "100%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "1rem",
      }}
    >
      <div className="card" style={{ padding: "2rem", width: "100%", maxWidth: "26rem" }} role="alert">
        <h1 style={{ fontSize: "1.3rem", marginTop: 0 }}>{t("outdated.title")}</h1>
        <p className="muted">{t("outdated.body")}</p>
        <button
          type="button"
          className="btn"
          style={{ width: "100%" }}
          onClick={() => window.location.reload()}
        >
          {t("outdated.reload")}
        </button>
      </div>
    </main>
  );
}
