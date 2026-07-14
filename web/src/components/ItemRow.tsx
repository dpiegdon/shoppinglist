import type { ItemObject, Member } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";

interface ItemRowProps {
  item: ItemObject;
  /** Who last touched this item, only when the list has 2+ members (T-64) — undefined hides the
   *  indicator entirely (the common solo-list case, and the pre-T-64 default for every caller). */
  authorMember?: Member;
  onToggle: () => void;
  onEdit: () => void;
}

export default function ItemRow({ item, authorMember, onToggle, onEdit }: ItemRowProps) {
  const checked = itemFieldValue(item, "status") === "checked";
  const category = itemFieldValue(item, "category");
  const quantity = itemFieldValue(item, "quantity");
  const price = itemFieldValue(item, "price");

  const details = [quantity, price ? `${price.amount}${price.currency ? " " + price.currency : ""}` : null]
    .filter(Boolean)
    .join(" · ");

  return (
    <div
      className="card"
      role="button"
      tabIndex={0}
      onClick={onToggle}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onToggle();
        }
      }}
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "0.65rem 0.75rem",
        gap: "0.5rem",
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div
          style={{
            fontWeight: 600,
            textDecoration: checked ? "line-through" : "none",
            color: checked ? "var(--color-checked)" : "var(--color-text)",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {itemFieldValue(item, "name")}
        </div>
        {details && <div className="muted" style={{ fontSize: "0.85rem" }}>{details}</div>}
        {!category && null}
      </div>
      {authorMember && (
        <span
          title={`Last touched by ${authorMember.email}`}
          aria-label={`Last touched by ${authorMember.email}`}
          style={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            width: "1.5rem",
            height: "1.5rem",
            borderRadius: "50%",
            fontSize: "0.7rem",
            fontWeight: 600,
            flexShrink: 0,
            background: "var(--color-border)",
            color: "var(--color-text)",
          }}
        >
          {authorMember.initials}
        </span>
      )}
      <button
        type="button"
        className="btn-icon"
        aria-label={`Edit ${itemFieldValue(item, "name")}`}
        onClick={(e) => {
          e.stopPropagation();
          onEdit();
        }}
      >
        ✎
      </button>
    </div>
  );
}
