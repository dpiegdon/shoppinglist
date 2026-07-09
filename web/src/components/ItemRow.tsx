import type { ItemObject } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";

interface ItemRowProps {
  item: ItemObject;
  onToggle: () => void;
  onEdit: () => void;
}

export default function ItemRow({ item, onToggle, onEdit }: ItemRowProps) {
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
