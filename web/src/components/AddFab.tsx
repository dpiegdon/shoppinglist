interface AddFabProps {
  /** What pressing it adds — its accessible name and tooltip, since it shows only a plus. */
  label: string;
  onClick: () => void;
  /** Lifted clear of a toast at the bottom of the screen, as Android lifts a FAB over a snackbar. */
  raised?: boolean;
}

/**
 * The round Add button on every list and on the overview (T-168, T-174), bottom right.
 *
 * Fixed to the viewport's bottom, but aligned to the page's content column rather than to the
 * window's edge: on a wide screen it sits against the right border of the list entries, not out
 * in the empty margin. The dock spans the viewport and centres a column exactly as wide as the
 * pages' <main>; it ignores pointer events so it never blocks what is under its empty part.
 */
export default function AddFab({ label, onClick, raised = false }: AddFabProps) {
  return (
    <div className={`fab-dock${raised ? " fab-dock-raised" : ""}`}>
      <div className="fab-dock-column">
        <button type="button" className="btn fab" aria-label={label} title={label} onClick={onClick}>
          +
        </button>
      </div>
    </div>
  );
}
