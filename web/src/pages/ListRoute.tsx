import { useParams } from "react-router-dom";
import { useSyncContext } from "../hooks/SyncContext";
import { listKind } from "../lib/listKind";
import ExpenseListPage from "./ExpenseListPage";
import ListPage from "./ListPage";
import { useT } from "../i18n";

/**
 * Picks the screen for a list by its kind (T-155). An expenses list shares almost nothing with a
 * shopping list on screen, so it gets its own page rather than a branch inside ListPage.
 *
 * While the first sync is still in flight nothing is rendered but a loading line: the kind is not
 * known yet, and guessing would flash the wrong screen on every visit to an expenses list.
 */
export default function ListRoute() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, loading } = useSyncContext();
  const list = listId ? lists.get(listId) : undefined;

  if (!list && loading) {
    return (
      <main style={{ padding: "1rem" }}>
        <p className="muted">{t("common.loading")}</p>
      </main>
    );
  }
  return listKind(list) === "expenses" ? <ExpenseListPage /> : <ListPage />;
}
