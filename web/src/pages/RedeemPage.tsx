import { useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { useSyncContext } from "../hooks/SyncContext";
import { LAST_LIST_STORAGE_KEY } from "./OverviewPage";

export default function RedeemPage() {
  const [searchParams] = useSearchParams();
  const [token, setToken] = useState(searchParams.get("token") ?? "");
  const [error, setError] = useState<string | null>(null);
  const [redeeming, setRedeeming] = useState(false);
  const { push } = useSyncContext();
  const navigate = useNavigate();

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setRedeeming(true);
    try {
      const { list_id } = await api.redeemInvite(token.trim());
      // Pull the newly joined list's full state immediately (Spec §6 full_lists).
      await push({}, [list_id]);
      localStorage.setItem(LAST_LIST_STORAGE_KEY, list_id);
      navigate(`/list/${list_id}`, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not redeem this invite.");
    } finally {
      setRedeeming(false);
    }
  }

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
      <form onSubmit={handleSubmit} className="card" style={{ padding: "2rem", width: "100%", maxWidth: "26rem" }}>
        <h1 style={{ fontSize: "1.3rem", marginTop: 0 }}>Join a shopping list</h1>
        <p className="muted">Paste the invite code, or open the invite link directly.</p>
        <div className="form-field">
          <label htmlFor="invite-token">Invite code</label>
          <textarea
            id="invite-token"
            required
            rows={3}
            value={token}
            onChange={(e) => setToken(e.target.value)}
            style={{ fontFamily: "monospace", fontSize: "0.8rem", wordBreak: "break-all" }}
          />
        </div>
        {error && (
          <p className="error-text" role="alert">
            {error}
          </p>
        )}
        <button type="submit" className="btn" disabled={redeeming} style={{ width: "100%" }}>
          Join list
        </button>
      </form>
    </main>
  );
}
