import { Link } from "react-router-dom";
import { useT } from "../i18n";

interface ExpenseListHeaderProps {
  listId: string;
  listName: string;
  /** Which of the two views is showing. */
  view: "expenses" | "balances";
}

/**
 * The top of both views of a ledger (T-172): the header every list has — the all-lists
 * link and the list's name, which leads back to the overview like ListPage's — and under it, where
 * a shopping list keeps its controls row, the Entries | Balances selector with settings beside it.
 *
 * It replaces a balances page titled "Balances" that did not lead anywhere, reached only through
 * a summary card nothing marked as a link. The selector's two entries are links, so each view
 * keeps its own address; they replace rather than push history, as switching tabs does.
 */
export default function ExpenseListHeader({ listId, listName, view }: ExpenseListHeaderProps) {
  const t = useT();
  return (
    <>
      <Link to="/" className="muted" style={{ fontSize: "0.85rem" }}>
        {t("list.allListsLink")}
      </Link>
      <h1
        style={{
          fontSize: "1.3rem",
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          margin: "0.25rem 0",
        }}
      >
        <Link to="/" title={t("list.backToAllLists")} style={{ color: "inherit", textDecoration: "none" }}>
          {listName}
        </Link>
      </h1>

      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          margin: "0.75rem 0",
          flexWrap: "wrap",
          gap: "0.5rem",
        }}
      >
        <nav className="segmented">
          {/* The active view carries a check (T-174), as Show checked does when it is on. Hidden from
              screen readers, which announce aria-current instead. */}
          <Link to={`/list/${listId}`} replace aria-current={view === "expenses" ? "page" : undefined}>
            {view === "expenses" && <span aria-hidden="true">✓ </span>}
            {t("expense.entries")}
          </Link>
          <Link to={`/list/${listId}/balances`} replace aria-current={view === "balances" ? "page" : undefined}>
            {view === "balances" && <span aria-hidden="true">✓ </span>}
            {t("expense.balances")}
          </Link>
        </nav>
        <Link
          to={`/list/${listId}/properties`}
          className="btn-icon"
          aria-label={t("listProps.title")}
          title={t("listProps.title")}
        >
          ⚙
        </Link>
      </div>
    </>
  );
}
