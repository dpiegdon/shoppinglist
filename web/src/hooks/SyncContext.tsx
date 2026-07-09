import { createContext, useContext, type ReactNode } from "react";
import { useSync, type SyncState } from "./useSync";

const SyncContext = createContext<SyncState | undefined>(undefined);

export function SyncProvider({ children }: { children: ReactNode }) {
  const sync = useSync();
  return <SyncContext.Provider value={sync}>{children}</SyncContext.Provider>;
}

export function useSyncContext(): SyncState {
  const ctx = useContext(SyncContext);
  if (!ctx) {
    throw new Error("useSyncContext must be used within a SyncProvider");
  }
  return ctx;
}
