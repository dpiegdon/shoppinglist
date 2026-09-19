import { appBasename, appVersion } from "../lib/appConfig";
import { useT } from "../i18n";
import CuneiformName from "../components/CuneiformName";

/**
 * What the app is and where its name comes from (T-224).
 *
 * The Android screen carries an "App updates" block here — the automatic-check switch and the
 * answer to the check it makes on opening. There is deliberately none on the web: the browser
 * fetches the current bundle from the server on every load, so there is nothing to offer to
 * update. The two screens are otherwise the same page.
 */
export default function AboutPage() {
  const t = useT();
  // The server injects its package version into index.html (routes/webapp.py); one version number
  // ships the server, this bundle and the APK. Absent in dev — the line then simply reads empty.
  const version = appVersion();

  return (
    <main
      style={{
        padding: "1rem",
        maxWidth: "40rem",
        margin: "0 auto",
        width: "100%",
        flex: 1,
        display: "flex",
        flexDirection: "column",
      }}
    >
      {/* The brand mark over a centred title, exactly as the login page opens (T-213): the
          favicon and Android's ic_brand_logo draw the same two paths (T-216), at the app's 72dp. */}
      <img
        src={`${appBasename()}/favicon.svg`}
        alt=""
        width={72}
        height={72}
        style={{ display: "block", margin: "1.5rem auto 0.75rem" }}
      />
      <h1 style={{ fontSize: "1.4rem", marginTop: 0, textAlign: "center" }}>{t("app.title")}</h1>

      {/* The name in cuneiform under the heading, with its transliteration beneath (T-225). The
          login screens carry the same sign smaller and without the caption. */}
      <CuneiformName height={64} style={{ margin: "0 auto 0.25rem" }} />
      <p
        className="muted"
        style={{ textAlign: "center", fontStyle: "italic", fontSize: "0.85rem", marginTop: 0 }}
      >
        {t("about.transliteration")}
      </p>

      <p style={{ textAlign: "center" }}>{t("about.tagline")}</p>

      <p className="muted" style={{ textAlign: "center" }}>{t("about.version", { version })}</p>

      <p
        className="muted"
        style={{ textAlign: "center", fontSize: "0.8rem", marginTop: "auto", marginBottom: 0 }}
      >
        {t("about.license")}
      </p>
    </main>
  );
}
