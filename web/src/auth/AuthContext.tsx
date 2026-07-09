import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import * as api from "../api/client";

interface Account {
  id: string;
  email: string;
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

function loadStoredAccount(): Account | null {
  const raw = sessionStorage.getItem(ACCOUNT_STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Account;
  } catch {
    return null;
  }
}

function storeAccount(account: Account | null) {
  if (account) {
    sessionStorage.setItem(ACCOUNT_STORAGE_KEY, JSON.stringify(account));
  } else {
    sessionStorage.removeItem(ACCOUNT_STORAGE_KEY);
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
      const result = await api.login({ email, password, device_label: DEVICE_LABEL });
      api.setToken(result.token);
      const nextAccount = { id: result.account_id, email: result.email };
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
