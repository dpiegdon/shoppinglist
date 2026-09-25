import { useState } from "react";
import * as api from "../api/client";
import type { ListMember } from "../api/contract";
import { useSyncContext } from "../hooks/SyncContext";
import { useT } from "../i18n";
import { useFormat } from "../lib/format";
import { errorMessage } from "../i18n/apiErrors";

interface CloseVoteBannerProps {
  listId: string;
  members: ListMember[];
  closeVotes: string[];
  closedAt: number | null;
  /** Decides whether this offers to agree or to withdraw. */
  myAccountId: string | null;
  /**
   * Show the controls even when nobody has voted. The list screen stays quiet until someone does;
   * list properties is where you go to start it, so there it is always offered.
   */
  alwaysShow?: boolean;
}

/**
 * Where an expenses list stands on closing (T-159): nothing while nobody has voted, a running
 * count while votes are pending, and a note once it is closed.
 *
 * Voting is an online action, like leaving — the list closes server-side the moment the last
 * current member agrees, which is not a decision a client can make on its own while offline.
 */
export default function CloseVoteBanner({
  listId,
  members,
  closeVotes,
  closedAt,
  myAccountId,
  alwaysShow = false,
}: CloseVoteBannerProps) {
  const t = useT();
  const fmt = useFormat();
  const { refresh } = useSyncContext();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const iHaveVoted = myAccountId !== null && closeVotes.includes(myAccountId);

  async function vote(cast: boolean) {
    setBusy(true);
    setError(null);
    try {
      if (cast) {
        await api.castCloseVote(listId);
      } else {
        await api.withdrawCloseVote(listId);
      }
      // The list row carries the vote state, so a pull is what makes the change visible.
      await refresh();
    } catch (err) {
      setError(errorMessage(t, err, "expense.voteFailed"));
    } finally {
      setBusy(false);
    }
  }

  if (closedAt !== null) {
    return (
      <p className="card" style={{ padding: "0.6rem 0.75rem", margin: "0 0 0.75rem" }}>
        {t("expense.closedOn", { date: fmt.day(closedAt) })}
      </p>
    );
  }

  if (closeVotes.length === 0 && !alwaysShow) return null;

  return (
    <div className="card" style={{ padding: "0.6rem 0.75rem", margin: "0 0 0.75rem" }}>
      <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", flexWrap: "wrap" }}>
        <span style={{ flex: 1, minWidth: "12rem" }}>
          {t("expense.agreeCount", { voted: closeVotes.length, total: members.length })}
        </span>
        <button
          type="button"
          className="btn"
          disabled={busy}
          onClick={() => vote(!iHaveVoted)}
        >
          {iHaveVoted ? t("expense.withdrawVote") : t("expense.agreeToClose")}
        </button>
      </div>
      {error && (
        <p className="error-text" role="alert" style={{ margin: "0.4rem 0 0" }}>
          {error}
        </p>
      )}
    </div>
  );
}
