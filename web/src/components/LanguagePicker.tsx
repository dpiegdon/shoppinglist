import { LOCALES, useI18n } from "../i18n";
import type { Locale } from "../i18n";

/**
 * The language chooser (T-127), used on both the login screen and in Settings.
 *
 * It appears on login because the choice must be available BEFORE an account exists — which is
 * also why the preference is device-local rather than synced to the account (see
 * i18n/index.tsx): syncing would add a second source of truth and a conflict rule for exactly the
 * case where no account is signed in yet.
 *
 * Each language is listed in its OWN language, never translated into the current UI language. That
 * is the standard convention and the only way a user who has landed in a script they cannot read
 * can find their way back out — so the option text deliberately does not go through `t`.
 *
 * The label does go through `t`, since it describes the control rather than naming a language.
 */
export default function LanguagePicker({ id = "language" }: { id?: string }) {
  const { locale, setLocale, t } = useI18n();

  return (
    <div className="form-field">
      <label htmlFor={id}>{t("settings.language")}</label>
      <select
        id={id}
        value={locale}
        onChange={(e) => setLocale(e.target.value as Locale)}
      >
        {LOCALES.map((entry) => (
          // lang/dir per option so a browser renders each name in its own script correctly even
          // while the surrounding page is in another — otherwise the Arabic entry can render with
          // its punctuation mirrored inside an English list.
          <option key={entry.tag} value={entry.tag} lang={entry.tag} dir={entry.dir}>
            {entry.name}
          </option>
        ))}
      </select>
    </div>
  );
}
