import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import * as api from "../api/client";

interface Account {
  id: string;
  email: string;
  // Configured-admin flag from the login response (T-107); gates the admin tab. Persisted with the
  // account so it survives a reload; refreshed on the next login (config changes need a re-login).
  isAdmin: boolean;
}

interface AuthContextValue {
  account: Account | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

const ACCOUNT_STORAGE_KEY = "shoppinglist_account";
const DEVICE_LABEL = "web";

// localStorage so a restored tab / browser restart keeps the session — must
// match the token's storage in client.ts, or one half would survive a restart
// without the other (T-104).
function loadStoredAccount(): Account | null {
  const raw = localStorage.getItem(ACCOUNT_STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Account;
  } catch {
    return null;
  }
}

function storeAccount(account: Account | null) {
  if (account) {
    localStorage.setItem(ACCOUNT_STORAGE_KEY, JSON.stringify(account));
  } else {
    localStorage.removeItem(ACCOUNT_STORAGE_KEY);
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(() =>
    api.getToken() ? loadStoredAccount() : null,
  );
  const [loading, setLoading] = useState(false);

  const login = useCallback(async (email: string, password: string) => {
    setLoading(true);
    try {
      const result = await api.login({
        email,
        password,
        device_label: DEVICE_LABEL,
        platform: "web",
      });
      api.setToken(result.token);
      const nextAccount = { id: result.account_id, email: result.email, isAdmin: result.is_admin };
      storeAccount(nextAccount);
      setAccount(nextAccount);
    } finally {
      setLoading(false);
    }
  }, []);

  const register = useCallback(async (email: string, password: string) => {
    setLoading(true);
    try {
      await api.register({ email, password });
      await login(email, password);
    } finally {
      setLoading(false);
    }
  }, [login]);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch {
      // Best-effort: still clear the local session even if the server call
      // fails (e.g. offline, token already invalid).
    } finally {
      api.setToken(null);
      storeAccount(null);
      setAccount(null);
    }
  }, []);

  // Registered once for the app's lifetime: client.ts has no React context of
  // its own, so it reports a forced logout (401 on a request that sent a
  // bearer token — revoked/expired session, T-89) through this seam. Clearing
  // `account` here is all that's needed; ProtectedRoute already redirects to
  // /login (preserving location state) whenever account is null.
  useEffect(() => {
    api.onForcedLogout(() => {
      storeAccount(null);
      setAccount(null);
    });
    return () => api.onForcedLogout(null);
  }, []);

  const value = useMemo(
    () => ({ account, loading, login, register, logout }),
    [account, loading, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return ctx;
}
