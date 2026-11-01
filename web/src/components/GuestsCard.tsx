import { useState, type FormEvent } from "react";
import * as api from "../api/client";
import type { ItemObject, ListGuest, ListObject } from "../api/contract";
import { useSyncContext } from "../hooks/SyncContext";
import { itemFieldValue } from "../hooks/useSync";
import { useT } from "../i18n";
import { errorMessage } from "../i18n/apiErrors";
import { formerMemberNumbers, resolveParticipant, sortedExpenses } from "../lib/expenses";
import { GuestBadge } from "./GuestBadge";
import { participantText } from "../lib/participantLabel";
import ToggleSwitch from "./ToggleSwitch";

interface GuestsCardProps {
  list: ListObject;
  /** The list's items that are not deleted: a guest named in one of them cannot be removed. */
  liveItems: ItemObject[];
  myAccountId: string | null;
  /** The viewer has agreed to close (T-193): every control is off, as everywhere else. */
  lockedByVote: boolean;
}

/**
 * A ledger's guests (T-364, design section 8), directly under Members: people the group tracks money
 * for who have no account here. Every change is an online action against the guest endpoints, and
 * the list object carries the result, so each one ends with a pull, as a close vote does.
 *
 * Read-only on a closed list, like the vote line.
 */
export default function GuestsCard({ list, liveItems, myAccountId, lockedByVote }: GuestsCardProps) {
  const t = useT();
  const { refresh } = useSyncContext();
  const [newName, setNewName] = useState("");
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const guests = list.guests ?? [];
  const members = list.members ?? [];
  const closed = (list.closed_at ?? null) !== null;
  const disabled = busy || lockedByVote;

  // Who a live entry names, by stored key: the server refuses removing any of them (guest_in_use).
  const named = new Set<string>();
  for (const item of liveItems) {
    const expense = itemFieldValue(item, "expense");
    if (!expense) continue;
    for (const id of [...Object.keys(expense.paid_by), ...Object.keys(expense.paid_for)]) named.add(id);
  }
  // Over the ledger's order (T-197), so a departed claimant keeps the number every other screen gives.
  const formerNumbers = formerMemberNumbers(sortedExpenses(liveItems), members, guests);

  async function act(action: () => Promise<unknown>, guest?: ListGuest): Promise<boolean> {
    setBusy(true);
    setError(null);
    try {
      await action();
      await refresh();
      return true;
    } catch (err) {
      if (err instanceof api.ApiError && err.code === "participant_frozen" && guest) {
        // A claim of a locked guest: its amounts would move onto the claimant's.
        setError(t("guest.errorLocked", { who: guest.name }));
      } else {
        setError(errorMessage(t, err, "item.saveFailed"));
      }
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function addGuest(e: FormEvent) {
    e.preventDefault();
    const name = newName.trim();
    if (!name) return;
    if (await act(() => api.createGuest(list.id, name))) setNewName("");
  }

  async function saveRename(e: FormEvent, guest: ListGuest) {
    e.preventDefault();
    const name = (renaming?.name ?? "").trim();
    if (!name || name === guest.name) {
      setRenaming(null);
      return;
    }
    if (await act(() => api.updateGuest(list.id, guest.id, { name }), guest)) setRenaming(null);
  }

  function claimantText(guest: ListGuest): string {
    return participantText(
      resolveParticipant(guest.claimed_by ?? "", members, [], formerNumbers),
      t,
      (member) => member.initials,
    );
  }

  return (
    <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
      <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("guest.section")}</h2>
      {guests.length > 0 && (
        <ul style={{ listStyle: "none", padding: 0, margin: "0 0 0.75rem" }}>
          {guests.map((guest) => {
            const inUse = named.has(guest.id);
            return (
              <li
                key={guest.id}
                data-testid="guest-row"
                style={{ padding: "0.4rem 0", borderBottom: "1px solid var(--color-border)" }}
              >
                {renaming?.id === guest.id ? (
                  <form onSubmit={(e) => saveRename(e, guest)} style={{ display: "flex", gap: "0.4rem" }}>
                    <input
                      autoFocus
                      aria-label={t("guest.name")}
                      value={renaming.name}
                      onChange={(e) => setRenaming({ id: guest.id, name: e.target.value })}
                      style={{ flex: 1, minWidth: 0 }}
                    />
                    <button type="submit" className="btn" disabled={disabled}>
                      {t("action.save")}
                    </button>
                    <button type="button" className="btn btn-secondary" onClick={() => setRenaming(null)}>
                      {t("action.cancel")}
                    </button>
                  </form>
                ) : (
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
                    <GuestBadge name={guest.name} initials={guest.initials} />
                    <span dir="auto" style={{ flex: 1, minWidth: 0, overflowWrap: "anywhere" }}>
                      {guest.name}
                    </span>
                    {!closed && (
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        disabled={disabled}
                        onClick={() => setRenaming({ id: guest.id, name: guest.name })}
                      >
                        {t("action.rename")}
                      </button>
                    )}
                  </div>
                )}

                {guest.claimed_by ? (
                  // A claimed guest's numbers are its claimant's, governed by their vote: no lock.
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginTop: "0.3rem" }}>
                    <span className="muted" style={{ flex: 1, fontSize: "0.85rem" }}>
                      {t("guest.claimedBy", { initials: claimantText(guest) })}
                    </span>
                    {!closed && guest.claimed_by === myAccountId && (
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        disabled={disabled}
                        onClick={() => act(() => api.unclaimGuest(list.id, guest.id), guest)}
                      >
                        {t("guest.unclaim")}
                      </button>
                    )}
                  </div>
                ) : (
                  !closed && (
                    <>
                      <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginTop: "0.3rem" }}>
                        <span style={{ flex: 1 }}>{t("guest.lockedSwitch")}</span>
                        <ToggleSwitch
                          checked={guest.locked}
                          disabled={disabled}
                          onChange={() => act(() => api.updateGuest(list.id, guest.id, { locked: !guest.locked }), guest)}
                          label={t("guest.lockedSwitch")}
                          onColor="var(--color-accent)"
                          offColor="var(--color-text-muted)"
                          onKnobColor="var(--color-accent-text)"
                          offKnobColor="var(--color-switch-knob)"
                        />
                      </div>
                      <p className="muted" style={{ margin: "0.2rem 0 0", fontSize: "0.8rem" }}>
                        {t("guest.lockedHelp")}
                      </p>
                    </>
                  )
                )}

                {!closed && (
                  <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.4rem", flexWrap: "wrap" }}>
                    {!guest.claimed_by && (
                      <button
                        type="button"
                        className="btn btn-sm"
                        disabled={disabled}
                        onClick={() => act(() => api.claimGuest(list.id, guest.id), guest)}
                      >
                        {t("guest.thisIsMe")}
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      disabled={disabled || inUse}
                      onClick={() => act(() => api.deleteGuest(list.id, guest.id), guest)}
                    >
                      {t("action.remove")}
                    </button>
                  </div>
                )}
                {!closed && inUse && (
                  <p className="muted" style={{ margin: "0.2rem 0 0", fontSize: "0.8rem" }}>
                    {t("guest.inUse")}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {!closed && (
        <form onSubmit={addGuest} style={{ display: "flex", gap: "0.5rem", marginTop: "0.6rem" }}>
          <input
            aria-label={t("guest.add")}
            placeholder={t("guest.add")}
            value={newName}
            disabled={lockedByVote}
            onChange={(e) => {
              setNewName(e.target.value);
              setError(null);
            }}
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn" disabled={disabled || newName.trim() === ""}>
            {t("action.add")}
          </button>
        </form>
      )}
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
