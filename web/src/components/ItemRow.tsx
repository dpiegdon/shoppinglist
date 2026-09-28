import type { ItemObject, Member } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";
import { dueState, todayIsoDate, useFormat } from "../lib/format";
import { toCents } from "../lib/expenses";
import { useT } from "../i18n";

interface ItemRowProps {
  item: ItemObject;
  /** Who last touched this item, only when the list has 2+ members (T-64) — undefined hides the
   *  indicator entirely (the common solo-list case, and the pre-T-64 default for every caller). */
  authorMember?: Member;
  /** False on a checklist (T-110): hides the quantity/price detail line. */
  showShoppingFields?: boolean;
  /** True on a checklist (T-323): shows the item's due date, if it has one, at the trailing edge. */
  showDue?: boolean;
  /** Today as YYYY-MM-DD, for a test to pin; the local calendar day otherwise. */
  today?: string;
  /** The account's currency, shown for a price that has none of its own — as the app does (T-187). */
  defaultCurrency?: string | null;
  /** True while this row is animating away after being checked off (T-128). The row is already
   *  logically gone — it is still mounted only so the exit can be seen — so it is inert. */
  exiting?: boolean;
  onToggle: () => void;
  onEdit: () => void;
}

export default function ItemRow({
  item,
  authorMember,
  showShoppingFields = true,
  showDue = false,
  today,
  defaultCurrency = null,
  exiting = false,
  onToggle,
  onEdit,
}: ItemRowProps) {
  const t = useT();
  const fmt = useFormat();
  const checked = itemFieldValue(item, "status") === "checked";
  const category = itemFieldValue(item, "category");
  const quantity = itemFieldValue(item, "quantity");
  const price = itemFieldValue(item, "price");
  const due = showDue ? itemFieldValue(item, "due") ?? null : null;
  const todayDate = today ?? todayIsoDate();
  // Colour is the date's only emphasis (T-323): the error colour once past, the accent on the day,
  // muted otherwise, and muted whatever it is once the item is checked off.
  const dueStanding = due && !checked ? dueState(due, todayDate) : "upcoming";
  const dueColor =
    dueStanding === "overdue"
      ? "var(--color-danger)"
      : dueStanding === "today"
        ? "var(--color-accent)"
        : "var(--color-text-muted)";
  const dueDescription =
    dueStanding === "overdue" ? t("item.overdue") : dueStanding === "today" ? t("item.dueToday") : null;

  // Suppressed on a checklist (T-110) — a converted list can still hold quantity/price, and showing
  // values the dialog won't let you edit would be confusing. The data itself is untouched.
  const details = !showShoppingFields
    ? ""
    : [quantity, price ? fmt.money(toCents(price.amount), price.currency ?? defaultCurrency) : null]
        .filter(Boolean)
        .join(" · ");

  return (
    <div
      // A flat row, as the app draws a list's entries (T-184).
      className={exiting ? "row item-exiting" : "row"}
      role="button"
      // Inert while exiting: without this a fast double-tap re-toggles a row that is on its way
      // out, and the second tap lands on something the user can no longer really see.
      tabIndex={exiting ? -1 : 0}
      aria-hidden={exiting || undefined}
      onClick={exiting ? undefined : onToggle}
      onKeyDown={(e) => {
        if (exiting) return;
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
      {/* flex:1 makes the text column fill the row so the author badge sits snug against the edit
          pencil on the right (matching Android's weight(1f) text column) instead of floating
          centered in the leftover space. */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontWeight: 600,
            textDecoration: checked ? "line-through" : "none",
            color: checked ? "var(--color-checked)" : "var(--color-text)",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
          // User-authored text inside UI chrome (T-126): without dir="auto" a Hebrew or Arabic
          // item name renders with its punctuation on the wrong side in an English UI, and a Latin
          // name does the same in an Arabic one. The browser picks per-string from first strong
          // character, which is exactly right for content the app did not author.
          dir="auto"
        >
          {itemFieldValue(item, "name")}
        </div>
        {details && <div className="muted" dir="auto" style={{ fontSize: "0.85rem" }}>{details}</div>}
        {!category && null}
      </div>
      {/* A sidenote (T-323): on the name's line, never wrapping it — the name truncates first. */}
      {due && (
        <span
          data-testid="item-due"
          title={dueDescription ?? undefined}
          style={{ fontSize: "0.8rem", whiteSpace: "nowrap", flexShrink: 0, color: dueColor }}
        >
          {dueDescription && <span className="visually-hidden">{dueDescription} </span>}
          {fmt.shortDate(due, todayDate)}
        </span>
      )}
      {authorMember && (
        <span
          title={t("list.lastTouchedBy", { email: authorMember.email })}
          aria-label={t("list.lastTouchedBy", { email: authorMember.email })}
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
        aria-label={t("list.editItem", { name: itemFieldValue(item, "name") ?? "" })}
        onClick={(e) => {
          e.stopPropagation();
          onEdit();
        }}
        style={{
          // Make the tap target the whole right corner of the row (T-77) rather than just the ✎
          // glyph: stretch to full row height and reach the card's right/top/bottom edges via
          // negative margins that cancel the card's 0.65rem/0.75rem padding.
          alignSelf: "stretch",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          padding: "0 0.9rem",
          margin: "-0.65rem -0.75rem -0.65rem 0",
          borderRadius: "0 var(--radius) var(--radius) 0",
          fontSize: "1.05rem",
        }}
      >
        ✎
      </button>
    </div>
  );
}
