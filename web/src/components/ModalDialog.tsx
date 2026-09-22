import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode, type RefObject } from "react";
import { useOverlayClose } from "../hooks/useOverlayClose";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), ' +
  'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface ModalDialogProps {
  /** The same close the dialog's Cancel button calls: Escape and a click outside go through it. */
  onClose: () => void;
  /** The id of the dialog's own heading, which names it for screen readers. */
  labelledBy: string;
  /** A form panel submits like the plain `<form className="dialog">` it replaces. */
  as?: "div" | "form";
  onSubmit?: (e: FormEvent<HTMLFormElement>) => void;
  children: ReactNode;
}

/**
 * The `.dialog-overlay` + `.dialog` pair every pop-up form uses, behaving as a modal dialog (T-283):
 * announced as one and named by its heading, Escape closes it, Tab cycles inside it, focus moves
 * into it on open (an `autoFocus` field inside keeps it) and goes back to whatever opened it on
 * close.
 *
 * Done by hand rather than with a native `<dialog>` + `showModal()`: that moves the box into the
 * top layer with its own backdrop and default styling, so the overlay CSS and the overlay's
 * mousedown/mouseup close rule (useOverlayClose, T-272) would both have to be redone, and jsdom has
 * no `showModal()` to test any of it against.
 */
export function ModalDialog({ onClose, labelledBy, as = "div", onSubmit, children }: ModalDialogProps) {
  const overlay = useOverlayClose(onClose);
  const panelRef = useRef<HTMLElement>(null);
  // Read while rendering, before the commit runs any autoFocus inside the box and moves focus off it.
  const [opener] = useState(() => (document.activeElement instanceof HTMLElement ? document.activeElement : null));

  useEffect(() => {
    const panel = panelRef.current;
    if (panel && !panel.contains(document.activeElement)) panel.focus();
    return () => {
      // Only on a real close: the box is already out of the document then. React's development
      // double-run of effects cleans up with the box still in place, and must not steal focus.
      if (panel && !panel.isConnected && opener?.isConnected) opener.focus();
    };
  }, [opener]);

  function handleKeyDown(e: KeyboardEvent<HTMLElement>) {
    // An IME's Escape cancels the composition, not the dialog.
    if (e.key === "Escape" && !e.defaultPrevented && !e.nativeEvent.isComposing) {
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key !== "Tab" || !panelRef.current) return;
    const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) {
      e.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const index = focusable.indexOf(document.activeElement as HTMLElement);
    // Only the ends wrap; everything in between is the browser's own Tab order.
    if (e.shiftKey && index <= 0) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (index === -1 || document.activeElement === last)) {
      e.preventDefault();
      first.focus();
    }
  }

  const panelProps = {
    className: "dialog",
    role: "dialog",
    "aria-modal": true,
    "aria-labelledby": labelledBy,
    tabIndex: -1,
    onKeyDown: handleKeyDown,
    onClick: (e: { stopPropagation: () => void }) => e.stopPropagation(),
  };

  return (
    <div className="dialog-overlay" onMouseDown={overlay.onMouseDown} onMouseUp={overlay.onMouseUp}>
      {as === "form" ? (
        <form {...panelProps} ref={panelRef as RefObject<HTMLFormElement>} onSubmit={onSubmit}>
          {children}
        </form>
      ) : (
        <div {...panelProps} ref={panelRef as RefObject<HTMLDivElement>}>
          {children}
        </div>
      )}
    </div>
  );
}
