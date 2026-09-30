import { useId, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useT } from "../i18n";
import { ModalDialog } from "./ModalDialog";

export interface ConfirmOptions {
  title: string;
  body?: ReactNode;
  /** What the confirm button says: the action itself ("Delete", "Leave"), not "OK". */
  confirmLabel: string;
  /** A confirm that removes something draws its label in the error colour. */
  destructive?: boolean;
}

interface ConfirmDialogProps extends ConfirmOptions {
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Every confirmation in the web client (T-343), as Android's AlertDialog draws it: a title, a
 * body, and two text buttons at the end, Cancel then the action, the action in red when it
 * removes something. Replaces `window.confirm`, whose OK/Cancel follow the browser's language.
 *
 * Cancel takes focus on open, so Enter or Space on a stray press dismisses rather than deletes.
 * Escape and a click outside cancel; focus goes back to the button that opened it (ModalDialog).
 *
 * Portalled to the body so a confirmation over the item or expense dialog is not nested inside
 * that dialog's form and scroll box.
 */
export function ConfirmDialog({ title, body, confirmLabel, destructive, onConfirm, onCancel }: ConfirmDialogProps) {
  const t = useT();
  const id = useId();
  const titleId = `${id}-title`;
  const bodyId = `${id}-body`;
  return createPortal(
    <ModalDialog role="alertdialog" onClose={onCancel} labelledBy={titleId} describedBy={body ? bodyId : undefined}>
      <h2 id={titleId} style={{ marginTop: 0, fontSize: "1.1rem" }}>
        {title}
      </h2>
      {body && (
        <p id={bodyId} style={{ overflowWrap: "anywhere" }}>
          {body}
        </p>
      )}
      <div style={{ display: "flex", flexWrap: "wrap", gap: "0.5rem", justifyContent: "flex-end", marginTop: "0.5rem" }}>
        <button type="button" className="btn btn-text" autoFocus onClick={onCancel}>
          {t("action.cancel")}
        </button>
        <button type="button" className={destructive ? "btn btn-text btn-danger" : "btn btn-text"} onClick={onConfirm}>
          {confirmLabel}
        </button>
      </div>
    </ModalDialog>,
    document.body,
  );
}
