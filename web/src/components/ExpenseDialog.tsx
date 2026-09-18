import { useMemo, useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import type { Expense, ItemObject, ListMember } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";
import {
  distribute,
  expenseTotalCents,
  fromCents,
  sharesToWire,
  toCents,
  type DistributeError,
  type ShareEntry,
} from "../lib/expenses";
import { useT } from "../i18n";

export interface ExpenseSaveValues {
  itemId: string;
  name: string;
  note: string;
  expense: Expense;
}

interface ExpenseDialogProps {
  members: ListMember[];
  /** Who has agreed to close the list (T-157): their amounts are frozen and cannot be edited. */
  closeVotes: string[];
  /** The list's free-text currency label, shown beside the total. */
  currency: string;
  /** The signed-in account, which is who a new expense defaults to having been paid by. */
  myAccountId: string;
  /** Present for edit mode, absent for add mode. */
  editingItem?: ItemObject;
  /**
   * Values to open an add-mode form with (T-164): what Record on the balances screen hands over.
   * Everything stays editable — changing the total is how a partial settlement is recorded.
   */
  prefill?: { name: string; expense: Expense };
  onClose: () => void;
  onSave: (values: ExpenseSaveValues) => Promise<void>;
  onDelete?: (itemId: string) => Promise<void>;
}

/** One participant's row in a distribution: selected or not, and the amount if the user typed one. */
interface ShareState {
  selected: Record<string, boolean>;
  /** What the user typed. Absent or "" means auto: share whatever is left. */
  text: Record<string, string>;
}

export function today(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Seed one distribution from a stored map. `equal` is the flag the expense carries: when it was an
 * equal split, everyone comes back as auto so that correcting the total redistributes rather than
 * erroring. Otherwise the amounts were meant, so they come back fixed.
 */
function shareStateFrom(shares: Record<string, string>, equal: boolean): ShareState {
  const selected: Record<string, boolean> = {};
  const text: Record<string, string> = {};
  for (const [id, amount] of Object.entries(shares)) {
    selected[id] = true;
    if (!equal) text[id] = amount;
  }
  return { selected, text };
}

function entriesOf(state: ShareState, ids: string[], frozen?: Set<string>): ShareEntry[] {
  return ids
    .filter((id) => state.selected[id])
    .map((id) => {
      const typed = (state.text[id] ?? "").trim();
      // A frozen participant's share is fixed by definition: it may not move, so it cannot be
      // one of the auto shares that absorb a change elsewhere.
      if (frozen?.has(id) && typed === "") return { id, fixed: 0 };
      return { id, fixed: typed === "" ? null : toCents(typed) };
    });
}

/** A new expense can never involve a frozen participant, so they start unselected. */
function frozenFreeSelection(
  ids: string[],
  closeVotes: string[],
  members: ListMember[],
): Record<string, boolean> {
  const current = new Set(members.map((member) => member.account_id));
  return Object.fromEntries(
    ids.filter((id) => !closeVotes.includes(id) && current.has(id)).map((id) => [id, true]),
  );
}

export default function ExpenseDialog({
  members,
  closeVotes,
  currency,
  myAccountId,
  editingItem,
  prefill,
  onClose,
  onSave,
  onDelete,
}: ExpenseDialogProps) {
  const t = useT();
  const isEdit = Boolean(editingItem);
  const stored = editingItem ? itemFieldValue(editingItem, "expense") ?? null : null;
  // What the distributions start from: the expense being edited, else a prefill, else nothing.
  const seed = stored ?? prefill?.expense ?? null;

  // Everyone who can be given a share: the current roster, plus anyone already on this expense
  // who has since left — their amount must stay visible and editable rather than vanish.
  const participantIds = useMemo(() => {
    const ids = members.map((member) => member.account_id);
    for (const id of [...Object.keys(seed?.paid_by ?? {}), ...Object.keys(seed?.paid_for ?? {})]) {
      if (!ids.includes(id)) ids.push(id);
    }
    return ids;
  }, [members, seed]);

  const [name, setName] = useState(() =>
    editingItem ? itemFieldValue(editingItem, "name") ?? "" : prefill?.name ?? "",
  );
  const [note, setNote] = useState(() =>
    editingItem ? itemFieldValue(editingItem, "note") ?? "" : "",
  );
  const [date, setDate] = useState(() => seed?.date ?? today());
  const [totalText, setTotalText] = useState(() =>
    seed ? fromCents(expenseTotalCents(seed)) : "",
  );
  const [paidBy, setPaidBy] = useState<ShareState>(() =>
    seed
      ? shareStateFrom(seed.paid_by, seed.equal_by)
      : { selected: frozenFreeSelection([myAccountId], closeVotes, members), text: {} },
  );
  const [paidFor, setPaidFor] = useState<ShareState>(() =>
    seed
      ? shareStateFrom(seed.paid_for, seed.equal_for)
      : {
          selected: frozenFreeSelection(
            members.map((m) => m.account_id),
            closeVotes,
            members,
          ),
          text: {},
        },
  );
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  /**
   * Whose amounts may not move: whoever has agreed to close, and anyone no longer on the roster.
   * Their rows render locked rather than hidden — the amount is part of the record, and a share
   * that vanished from the form would be silently dropped on the next save.
   */
  const frozen = useMemo(() => {
    const current = new Set(members.map((member) => member.account_id));
    return new Set(participantIds.filter((id) => closeVotes.includes(id) || !current.has(id)));
  }, [participantIds, members, closeVotes]);

  const totalCents = toCents(totalText.trim() || "0");
  const byResult = useMemo(
    () => distribute(totalCents, entriesOf(paidBy, participantIds, frozen)),
    [totalCents, paidBy, participantIds, frozen],
  );
  const forResult = useMemo(
    () => distribute(totalCents, entriesOf(paidFor, participantIds, frozen)),
    [totalCents, paidFor, participantIds, frozen],
  );

  // With one participant there is nothing to distribute: they paid, and it was for them.
  const soloList = participantIds.length <= 1;

  function labelFor(id: string): string {
    const member = members.find((m) => m.account_id === id);
    if (member) return member.email;
    return t("expense.formerMember", { number: String(participantIds.indexOf(id) + 1) });
  }

  function errorText(error: DistributeError, sumCents: number): string {
    if (error === "fixed_exceeds_total") return t("expense.error.aboveTotal");
    if (error === "fixed_sum_mismatch") {
      return t("expense.error.doesNotAddUp", { sum: fromCents(sumCents), total: fromCents(totalCents) });
    }
    if (error === "no_participants") return t("expense.error.nobody");
    return t("expense.error.total");
  }

  function sumOf(state: ShareState): number {
    return entriesOf(state, participantIds, frozen).reduce((sum, entry) => sum + (entry.fixed ?? 0), 0);
  }

  function renderShares(
    which: "by" | "for",
    state: ShareState,
    setState: (next: ShareState) => void,
    result: ReturnType<typeof distribute>,
  ) {
    const computed = result.ok ? result.shares : {};
    const sum = sumOf(state);
    return (
      <fieldset style={{ border: "none", padding: 0, margin: "0 0 0.75rem" }}>
        <legend className="muted" style={{ fontSize: "0.85rem", padding: 0 }}>
          {which === "by" ? t("expense.paidBy") : t("expense.paidFor")}
        </legend>
        {participantIds.map((id) => {
          const selected = Boolean(state.selected[id]);
          const typed = state.text[id] ?? "";
          const isFrozen = frozen.has(id);
          // The derived share is the PLACEHOLDER, never the value. As the value it would come
          // straight back the moment the field was cleared, so typing over it appended to it.
          // Empty means auto, which is also what the greyed-out number says.
          const derived = selected ? fromCents(computed[id] ?? 0) : "";
          return (
            <div key={id} style={{ display: "flex", alignItems: "center", gap: "0.5rem", padding: "0.15rem 0" }}>
              <input
                type="checkbox"
                id={`${which}-${id}`}
                checked={selected}
                disabled={isFrozen}
                onChange={(e) =>
                  setState({
                    selected: { ...state.selected, [id]: e.target.checked },
                    // Deselecting drops any amount typed for that person, so re-selecting them
                    // starts from an equal share rather than a stale number.
                    text: e.target.checked ? state.text : { ...state.text, [id]: "" },
                  })
                }
              />
              {/* Wraps rather than truncates: in a form, who a share belongs to must stay readable. */}
              <label htmlFor={`${which}-${id}`} style={{ flex: 1, minWidth: 0, overflowWrap: "anywhere" }} dir="auto">
                {labelFor(id)}
                {isFrozen && (
                  <span className="muted" style={{ fontSize: "0.75rem", display: "block" }}>
                    {t("expense.frozen")}
                  </span>
                )}
              </label>
              <input
                aria-label={`${which === "by" ? t("expense.paidBy") : t("expense.paidFor")} ${labelFor(id)}`}
                inputMode="decimal"
                disabled={!selected || isFrozen}
                value={typed}
                placeholder={derived}
                style={{ width: "6rem", textAlign: "end" }}
                onChange={(e) => setState({ ...state, text: { ...state.text, [id]: e.target.value } })}
              />
            </div>
          );
        })}
        {!result.ok && (
          <p className="error-text" role="alert" style={{ margin: "0.25rem 0 0" }}>
            {errorText(result.error, sum)}
            {result.error === "fixed_sum_mismatch" && (
              <>
                {" "}
                <button type="button" className="chip-button" onClick={() => setTotalText(fromCents(sum))}>
                  {t("expense.error.useSum", { sum: fromCents(sum) })}
                </button>
              </>
            )}
          </p>
        )}
      </fieldset>
    );
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || !byResult.ok || !forResult.ok) return;

    setSaving(true);
    setSaveError(null);
    try {
      await onSave({
        itemId: editingItem?.id ?? crypto.randomUUID(),
        name: trimmed,
        note: note.trim(),
        expense: {
          paid_by: sharesToWire(byResult.shares),
          // "Everyone selected is auto" is exactly what makes a later total change redistribute.
          equal_by: entriesOf(paidBy, participantIds, frozen).every((entry) => entry.fixed == null),
          paid_for: sharesToWire(forResult.shares),
          equal_for: entriesOf(paidFor, participantIds, frozen).every((entry) => entry.fixed == null),
          date,
        },
      });
      onClose();
    } catch (err) {
      setSaveError(saveErrorText(err));
    } finally {
      setSaving(false);
    }
  }

  /** The server's refusals, said in terms of this list's people rather than account ids. */
  function saveErrorText(err: unknown): string {
    if (!(err instanceof ApiError)) return t("item.saveFailed");
    if (err.code === "participant_frozen") {
      return t("expense.error.frozen", { who: labelFor(String(err.details.account_id ?? "")) });
    }
    if (err.code === "list_closed") return t("expense.error.closed");
    return err.message;
  }

  const canSave = name.trim() !== "" && byResult.ok && forResult.ok && !saving;

  return (
    <div className="dialog-overlay" onClick={onClose}>
      <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <h2 style={{ marginTop: 0, fontSize: "1.1rem" }}>
          {isEdit ? t("expense.edit") : t("expense.new")}
        </h2>
        {saveError && (
          <p className="error-text" role="alert">
            {saveError}
          </p>
        )}

        <div className="form-field">
          <label htmlFor="expense-what">{t("expense.what")}</label>
          <input
            id="expense-what"
            autoFocus
            required
            autoComplete="off"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div style={{ display: "flex", gap: "0.5rem" }}>
          <div className="form-field" style={{ flex: 1, minWidth: 0 }}>
            <label htmlFor="expense-total">
              {t("expense.total")} ({currency})
            </label>
            <input
              id="expense-total"
              inputMode="decimal"
              placeholder="0.00"
              required
              value={totalText}
              onChange={(e) => setTotalText(e.target.value)}
            />
          </div>
          <div className="form-field" style={{ flex: 1, minWidth: 0 }}>
            <label htmlFor="expense-date">{t("expense.date")}</label>
            <input
              id="expense-date"
              type="date"
              required
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </div>
        </div>

        {/* Nothing to choose on a list of one: they paid, and it was for them. */}
        {!soloList && renderShares("by", paidBy, setPaidBy, byResult)}
        {!soloList && renderShares("for", paidFor, setPaidFor, forResult)}
        {soloList && (
          <p className="muted" style={{ fontSize: "0.85rem" }}>
            {t("expense.soloHint")}
          </p>
        )}

        <div className="form-field">
          <label htmlFor="expense-note">{t("item.note")}</label>
          <input id="expense-note" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>

        <div style={{ display: "flex", justifyContent: "space-between", gap: "0.5rem" }}>
          <div>
            {isEdit && onDelete && editingItem && (
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => onDelete(editingItem.id).then(onClose)}
              >
                {t("action.delete")}
              </button>
            )}
          </div>
          <div style={{ display: "flex", gap: "0.5rem" }}>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              {t("action.cancel")}
            </button>
            <button type="submit" className="btn" disabled={!canSave}>
              {isEdit ? t("action.save") : t("action.add")}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
