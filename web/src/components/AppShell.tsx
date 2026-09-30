import { useEffect, useRef, useState, type FocusEvent, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { SyncProvider } from "../hooks/SyncContext";
import SyncIndicator from "./SyncIndicator";
import { useT } from "../i18n";

export default function AppShell({ children }: { children: ReactNode }) {
  const t = useT();
  const [menuOpen, setMenuOpen] = useState(false);
  const { account, logout } = useAuth();
  const toggleRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLElement>(null);

  // The menu behaves as a disclosure should: it takes focus on its first entry when it opens, and
  // closes on Escape (focus back to ☰), on a press outside it, and when focus moves out of it.
  useEffect(() => {
    if (!menuOpen) return;
    menuRef.current?.querySelector<HTMLElement>("a, button")?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setMenuOpen(false);
      toggleRef.current?.focus();
    };
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      // A press on ☰ is its own toggle; closing here as well would reopen the menu at once.
      if (menuRef.current?.contains(target) || toggleRef.current?.contains(target)) return;
      setMenuOpen(false);
    };
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [menuOpen]);

  const onMenuBlur = (event: FocusEvent<HTMLElement>) => {
    const next = event.relatedTarget as Node | null;
    // No next target is a press on something that takes no focus, which the pointer handler
    // above settles; focus moving to ☰ is its own toggle.
    if (!next || menuRef.current?.contains(next) || toggleRef.current?.contains(next)) return;
    setMenuOpen(false);
  };

  return (
    <SyncProvider>
      <div style={{ minHeight: "100dvh", display: "flex", flexDirection: "column" }}>
        <header
          style={{
            position: "relative",
            display: "flex",
            alignItems: "center",
            gap: "0.75rem",
            padding: "0.5rem 1rem",
            borderBottom: "1px solid var(--color-border)",
          }}
        >
          <button
            ref={toggleRef}
            type="button"
            className="btn-icon"
            aria-label={t("nav.menu")}
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((v) => !v)}
          >
            ☰
          </button>

          {/* Right after ☰ in the page, so Tab goes from the button into the menu. */}
          {menuOpen && (
            <nav
              ref={menuRef}
              className="card"
              style={{
                position: "absolute",
                top: "100%",
                insetInlineStart: "0.5rem",
                zIndex: 50,
                padding: "0.5rem",
                display: "flex",
                flexDirection: "column",
                minWidth: "12rem",
                boxShadow: "var(--shadow)",
              }}
              onClick={() => setMenuOpen(false)}
              onBlur={onMenuBlur}
            >
              <span className="muted" style={{ padding: "0.4rem 0.6rem", fontSize: "0.85rem" }}>
                {account?.email}
              </span>
              <Link to="/" className="menu-item">
                {t("nav.overview")}
              </Link>
              {/* Groups as on Android (T-307): your lists; joining one; the app itself. */}
              <hr style={navDividerStyle} />
              <Link to="/redeem" className="menu-item">
                {t("nav.joinList")}
              </Link>
              <hr style={navDividerStyle} />
              <Link to="/settings" className="menu-item">
                {t("settings.title")}
              </Link>
              {/* Administering the server is not a personal preference, so it sits beside Settings
                  rather than inside it (T-220). Hiding it from a non-admin is an affordance only —
                  the server enforces admin on every /admin route regardless of what the menu shows. */}
              {account?.isAdmin && (
                <Link to="/admin" className="menu-item">
                  {t("nav.serverAdmin")}
                </Link>
              )}
              {/* Last before Log out (T-224): what the app is, not something you do with it. */}
              <Link to="/about" className="menu-item">
                {t("nav.about")}
              </Link>
              <button type="button" onClick={() => logout()} className="menu-item">
                {t("nav.logOut")}
              </button>
            </nav>
          )}

          <Link to="/" style={{ fontWeight: 700, textDecoration: "none", color: "var(--color-text)" }}>
            {t("app.title")}
          </Link>
          <SyncIndicator />
        </header>

        <div style={{ flex: 1, display: "flex", flexDirection: "column" }}>{children}</div>
      </div>
    </SyncProvider>
  );
}

const navDividerStyle: React.CSSProperties = {
  border: "none",
  borderTop: "1px solid var(--color-border)",
  margin: "0.25rem 0.6rem",
  alignSelf: "stretch",
};
