import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { SyncProvider } from "../hooks/SyncContext";

export default function AppShell({ children }: { children: ReactNode }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const { account, logout } = useAuth();

  return (
    <SyncProvider>
      <div style={{ minHeight: "100%", display: "flex", flexDirection: "column" }}>
        <header
          style={{
            display: "flex",
            alignItems: "center",
            gap: "0.75rem",
            padding: "0.75rem 1rem",
            borderBottom: "1px solid var(--color-border)",
          }}
        >
          <button
            type="button"
            className="btn-icon"
            aria-label="Menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((v) => !v)}
          >
            ☰
          </button>
          <Link to="/" style={{ fontWeight: 700, textDecoration: "none", color: "var(--color-text)" }}>
            Shopping List
          </Link>
        </header>

        {menuOpen && (
          <nav
            className="card"
            style={{
              position: "absolute",
              top: "3.2rem",
              left: "0.5rem",
              zIndex: 50,
              padding: "0.5rem",
              display: "flex",
              flexDirection: "column",
              minWidth: "12rem",
              boxShadow: "var(--shadow)",
            }}
            onClick={() => setMenuOpen(false)}
          >
            <span className="muted" style={{ padding: "0.4rem 0.6rem", fontSize: "0.85rem" }}>
              {account?.email}
            </span>
            <Link to="/" style={navLinkStyle}>
              Overview
            </Link>
            <Link to="/redeem" style={navLinkStyle}>
              Join a list
            </Link>
            <Link to="/settings" style={navLinkStyle}>
              Account settings
            </Link>
            <button
              type="button"
              onClick={() => logout()}
              className="btn-icon"
              style={{ textAlign: "left", padding: "0.5rem 0.6rem" }}
            >
              Log out
            </button>
          </nav>
        )}

        <div style={{ flex: 1, display: "flex", flexDirection: "column" }}>{children}</div>
      </div>
    </SyncProvider>
  );
}

const navLinkStyle: React.CSSProperties = {
  padding: "0.5rem 0.6rem",
  borderRadius: "0.375rem",
  color: "var(--color-text)",
  textDecoration: "none",
};
