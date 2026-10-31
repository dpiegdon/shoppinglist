import { useState } from "react";
import * as api from "../api/client";
import type { ListGuest } from "../api/contract";
import { useSyncContext } from "../hooks/SyncContext";
import { useT } from "../i18n";
import { errorMessage } from "../i18n/apiErrors";
import { ModalDialog } from "./ModalDialog";
import { GuestBadge } from "./GuestBadge";

interface GuestChooserProps {
  listId: string;
  /** The ledger's unclaimed guests, in the list's order. */
  guests: ListGuest[];
  onClose: () => void;
}

/**
 * "Are you one of these?" (T-364): right after joining a ledger that already tracks guests, the
 * newcomer can say which slot was them, so its history becomes theirs. One button per unclaimed
 * guest, and "No, I'm new". Dismissing is final for this visit; "This is me" in list properties
 * covers second thoughts.
 */
export default function GuestChooser({ listId, guests, onClose }: GuestChooserProps) {
  const t = useT();
  const { refresh } = useSyncContext();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function claim(guest: ListGuest) {
    setBusy(true);
    setError(null);
    try {
      await api.claimGuest(listId, guest.id);
      // The list object carries the claim, so a pull is what makes it count.
      await refresh();
      onClose();
    } catch (err) {
      setError(errorMessage(t, err, "item.saveFailed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <ModalDialog onClose={onClose} labelledBy="guest-chooser-title">
      <h2 id="guest-chooser-title" style={{ marginTop: 0, fontSize: "1.1rem" }}>
        {t("guest.chooserTitle")}
      </h2>
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
      <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
        {guests.map((guest) => (
          <button
            key={guest.id}
            type="button"
            className="btn btn-secondary"
            disabled={busy}
            onClick={() => claim(guest)}
            style={{ display: "flex", alignItems: "center", gap: "0.5rem", textAlign: "start" }}
          >
            <GuestBadge name={guest.name} initials={guest.initials} />
            <span dir="auto">{guest.name}</span>
          </button>
        ))}
        <button type="button" className="btn" disabled={busy} onClick={onClose}>
          {t("guest.chooserNew")}
        </button>
      </div>
    </ModalDialog>
  );
}
