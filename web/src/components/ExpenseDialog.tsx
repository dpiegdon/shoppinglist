import { useMemo, useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import type { Expense, ExpenseType, ItemObject, ListMember } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";
import {
  distribute,
  entryType,
  expenseTotalCents,
  fromCents,
  sharesToWire,
  toCents,
  type DistributeError,
  type ShareEntry,
} from "../lib/expenses";
import { useT } from "../i18n";
import { errorMessage } from "../i18n/apiErrors";

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
  /**
   * How to number participants who are no longer members (T-152), numbered across the whole list
   * by whoever renders it. The form is given it rather than numbering its own participants
   * (T-197), which called the same person one number here and another on the list and balances.
   */
  formerNumbers: ReadonlyMap<string, number>;
  /** Present for edit mode, absent for add mode. */
  editingItem?: ItemObject;
  /**
   * Values to open an add-mode form with (T-164): what Reimburse on the balances screen hands over.
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
 *
 * A frozen participant is seeded from what is stored whichever it was (T-196): their share may not
 * move, so it can never be one of the auto shares — and seeding it is what keeps it at its own
 * amount rather than at nothing, which every save would then be refused for.
 */
function shareStateFrom(
  shares: Record<string, string>,
  equal: boolean,
  frozen: ReadonlySet<string>,
): ShareState {
  const selected: Record<string, boolean> = {};
  const text: Record<string, string> = {};
  for (const [id, amount] of Object.entries(shares)) {
    selected[id] = true;
    if (!equal || frozen.has(id)) text[id] = amount;
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

/** The three types in the order the segmented control offers them. */
const TYPES: ExpenseType[] = ["expense", "income", "transfer"];

/** The i18n key of a type's label — also the title a blank income or transfer falls back to. */
function typeLabelKey(type: ExpenseType): "expense.type.expense" | "expense.type.income" | "expense.type.transfer" {
  if (type === "income") return "expense.type.income";
  if (type === "transfer") return "expense.type.transfer";
  return "expense.type.expense";
}

/** The single account named in one side of a transfer, or "" for anything else. */
function soleParticipant(shares: Record<string, string>): string {
  const ids = Object.keys(shares);
  return ids.length === 1 ? ids[0] : "";
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
  formerNumbers,
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

  /**
   * Whose amounts may not move, and why (T-203): whoever has agreed to close, and anyone no longer
   * on the roster. Their rows render locked rather than hidden — the amount is part of the record,
   * and a share that vanished from the form would be silently dropped on the next save. The reason
   * is kept because the row says it: "agreed to close" is a lie about someone who simply left.
   */
  const frozenReason = useMemo(() => {
    const current = new Set(members.map((member) => member.account_id));
    const reasons = new Map<string, "voter" | "former">();
    for (const id of participantIds) {
      if (closeVotes.includes(id)) reasons.set(id, "voter");
      else if (!current.has(id)) reasons.set(id, "former");
    }
    return reasons;
  }, [participantIds, members, closeVotes]);
  const frozen = useMemo(() => new Set(frozenReason.keys()), [frozenReason]);

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
  // Only an expense already stored seeds frozen shares: a prefilled new one is a change from zero
  // for anyone frozen whatever it says, so there is nothing of theirs to preserve.
  const seedFrozen: ReadonlySet<string> = stored ? frozen : new Set<string>();
  const [paidBy, setPaidBy] = useState<ShareState>(() =>
    seed
      ? shareStateFrom(seed.paid_by, seed.equal_by, seedFrozen)
      : { selected: frozenFreeSelection([myAccountId], closeVotes, members), text: {} },
  );
  const [paidFor, setPaidFor] = useState<ShareState>(() =>
    seed
      ? shareStateFrom(seed.paid_for, seed.equal_for, seedFrozen)
      : {
          selected: frozenFreeSelection(
            members.map((m) => m.account_id),
            closeVotes,
            members,
          ),
          text: {},
        },
  );
  // Which of the three this entry is (T-245). An entry stored before types existed opens as the
  // Expense it has always been, and can be corrected here — that is how a "Settlement" someone
  // recorded as an expense becomes a Transfer.
  const [type, setType] = useState<ExpenseType>(() => (seed ? entryType(seed) : "expense"));
  // A transfer names one sender and one recipient rather than distributing anything.
  const [transferFrom, setTransferFrom] = useState(() =>
    seed && entryType(seed) === "transfer" ? soleParticipant(seed.paid_by) : "",
  );
  const [transferTo, setTransferTo] = useState(() =>
    seed && entryType(seed) === "transfer" ? soleParticipant(seed.paid_for) : "",
  );
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  /** Who a transfer may name: nobody whose amounts are frozen can be moved onto or off one. */
  const transferCandidates = participantIds.filter((id) => !frozen.has(id));
  // A list of one has nobody to pay, so Transfer is not on offer at all. An entry that already is
  // one keeps its segment whatever the roster now looks like, so it can still be read and retyped.
  const canTransfer = transferCandidates.length >= 2 || type === "transfer";

  /**
   * Changing the type keeps everything the types have in common — title, date, note, total — and
   * translates what they do not (T-245).
   *
   * Expense and Income are the same form read two ways, so their maps survive untouched. A
   * transfer has no maps to keep: it becomes the one sender and one recipient the maps already
   * described when they described exactly one of each, and otherwise starts from me and the first
   * other person. Coming back the other way, the sender is the sole payer and the recipient the
   * sole beneficiary, each an equal share of one.
   */
  function changeType(next: ExpenseType) {
    if (next === type) return;
    if (next === "transfer") {
      const payers = participantIds.filter((id) => paidBy.selected[id]);
      const beneficiaries = participantIds.filter((id) => paidFor.selected[id]);
      if (payers.length === 1 && beneficiaries.length === 1 && payers[0] !== beneficiaries[0]) {
        setTransferFrom(payers[0]);
        setTransferTo(beneficiaries[0]);
      } else {
        const from = transferCandidates.includes(myAccountId)
          ? myAccountId
          : (payers.find((id) => transferCandidates.includes(id)) ?? transferCandidates[0] ?? "");
        // The beneficiaries say more about what the user meant than the roster does, so they are
        // asked first; the first other member is the fallback.
        const to =
          beneficiaries.find((id) => id !== from && transferCandidates.includes(id)) ??
          transferCandidates.find((id) => id !== from) ??
          "";
        setTransferFrom(from);
        setTransferTo(to);
      }
    } else if (type === "transfer") {
      setPaidBy({ selected: transferFrom ? { [transferFrom]: true } : {}, text: {} });
      setPaidFor({ selected: transferTo ? { [transferTo]: true } : {}, text: {} });
    }
    setType(next);
  }

  // Whether deleting is off the table: it would take a frozen participant's amounts to zero (T-193).
  const deleteBlocked =
    !!stored && [...Object.keys(stored.paid_by), ...Object.keys(stored.paid_for)].some((id) => frozen.has(id));

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
    return t("expense.formerMember", { number: formerNumbers.get(id) ?? 0 });
  }

  function errorText(error: DistributeError, sumCents: number): string {
    if (error === "fixed_exceeds_total") return t("expense.error.aboveTotal");
    if (error === "fixed_sum_mismatch") {
      return t("expense.error.doesNotAddUp", { sum: fromCents(sumCents), total: fromCents(totalCents) });
    }
    if (error === "no_participants") return t("expense.error.nobody");
    return t("expense.error.total");
  }

  /**
   * What the two sides are called. Income is the same form read the other way round: the money
   * came in to someone and was credited to the others, so the two legends say so rather than
   * leaving "Paid by" over a refund.
   */
  function sideLabel(which: "by" | "for"): string {
    if (type === "income") return which === "by" ? t("expense.receivedBy") : t("expense.creditedTo");
    return which === "by" ? t("expense.paidBy") : t("expense.paidFor");
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
          {sideLabel(which)}
        </legend>
        {participantIds.map((id) => {
          const selected = Boolean(state.selected[id]);
          const typed = state.text[id] ?? "";
          const reason = frozenReason.get(id);
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
                disabled={reason !== undefined}
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
                {reason && (
                  <span className="muted" style={{ fontSize: "0.75rem", display: "block" }}>
                    {t(reason === "voter" ? "expense.frozenVoter" : "expense.frozenFormer")}
                  </span>
                )}
              </label>
              <input
                aria-label={`${sideLabel(which)} ${labelFor(id)}`}
                inputMode="decimal"
                disabled={!selected || reason !== undefined}
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

  /** Whether a transfer names two different people, which is the only shape the server takes. */
  const transferOk = transferFrom !== "" && transferTo !== "" && transferFrom !== transferTo;
  const sharesOk = type === "transfer" ? totalCents > 0 && transferOk : byResult.ok && forResult.ok;
  // An expense still needs a title. An income or a transfer falls back to its own name, because
  // "Income" is all there is to say about most refunds and making people type it is friction.
  const titleOk = type !== "expense" || name.trim() !== "";

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const title = name.trim() || (type === "expense" ? "" : t(typeLabelKey(type)));
    if (!title || !sharesOk) return;

    setSaving(true);
    setSaveError(null);
    try {
      await onSave({
        itemId: editingItem?.id ?? crypto.randomUUID(),
        name: title,
        note: note.trim(),
        expense:
          type === "transfer"
            ? {
                type,
                // One sender, one recipient, the same amount on both sides: the total is the whole
                // of it, so a partial settlement is recorded by editing the total.
                paid_by: { [transferFrom]: fromCents(totalCents) },
                equal_by: true,
                paid_for: { [transferTo]: fromCents(totalCents) },
                equal_for: true,
                date,
              }
            : {
                type,
                paid_by: sharesToWire(byResult.ok ? byResult.shares : {}),
                // "Everyone selected is auto" is exactly what makes a later total change redistribute.
                equal_by: entriesOf(paidBy, participantIds, frozen).every((entry) => entry.fixed == null),
                paid_for: sharesToWire(forResult.ok ? forResult.shares : {}),
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
    if (err instanceof ApiError && err.code === "participant_frozen") {
      return t("expense.error.frozen", { who: labelFor(String(err.details.account_id ?? "")) });
    }
    return errorMessage(t, err, "item.saveFailed");
  }

  const canSave = titleOk && sharesOk && !saving;

  /** One side of a transfer. The other side's choice is not on offer: nobody pays themselves. */
  function renderTransferPicker(
    which: "from" | "to",
    value: string,
    other: string,
    setValue: (next: string) => void,
  ) {
    return (
      <div className="form-field" style={{ flex: 1, minWidth: 0 }}>
        <label htmlFor={`expense-${which}`}>{t(which === "from" ? "expense.from" : "expense.to")}</label>
        <select
          id={`expense-${which}`}
          value={value}
          // A frozen participant's amounts may not move, so an entry that names one cannot be
          // pointed at somebody else here; the server would refuse it anyway.
          disabled={frozen.has(value)}
          onChange={(e) => setValue(e.target.value)}
        >
          {participantIds
            .filter((id) => (id !== other || id === value) && (!frozen.has(id) || id === value))
            .map((id) => (
              <option key={id} value={id}>
                {labelFor(id)}
              </option>
            ))}
        </select>
      </div>
    );
  }

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

        {/* What kind of entry this is, before anything else: it decides what the rest of the
            form means (T-245). */}
        <fieldset style={{ border: "none", padding: 0, margin: "0 0 0.75rem" }}>
          <legend className="muted" style={{ fontSize: "0.85rem", padding: 0 }}>
            {t("expense.type")}
          </legend>
          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap" }}>
            {TYPES.map((option) => (
              <label key={option} style={{ display: "flex", alignItems: "center", gap: "0.35rem" }}>
                <input
                  type="radio"
                  name="expense-type"
                  value={option}
                  checked={type === option}
                  disabled={option === "transfer" && !canTransfer}
                  onChange={() => changeType(option)}
                />
                <span>{t(typeLabelKey(option))}</span>
              </label>
            ))}
          </div>
          {!canTransfer && (
            <p className="muted" style={{ margin: "0.25rem 0 0", fontSize: "0.8rem" }}>
              {t("expense.error.needTwoMembers")}
            </p>
          )}
        </fieldset>

        <div className="form-field">
          <label htmlFor="expense-what">{t("expense.what")}</label>
          <input
            id="expense-what"
            autoFocus
            // An income or a transfer names itself when left blank, so only an expense insists.
            required={type === "expense"}
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

        {/* A transfer has nothing to split: it is one person handing money to another. */}
        {type === "transfer" && (
          <>
            <div style={{ display: "flex", gap: "0.5rem" }}>
              {renderTransferPicker("from", transferFrom, transferTo, setTransferFrom)}
              {renderTransferPicker("to", transferTo, transferFrom, setTransferTo)}
            </div>
            {transferFrom !== "" && transferFrom === transferTo && (
              <p className="error-text" role="alert" style={{ margin: "0 0 0.75rem" }}>
                {t("expense.error.sameMember")}
              </p>
            )}
          </>
        )}

        {/* Nothing to choose on a list of one: they paid, and it was for them. */}
        {type !== "transfer" && !soloList && renderShares("by", paidBy, setPaidBy, byResult)}
        {type !== "transfer" && !soloList && renderShares("for", paidFor, setPaidFor, forResult)}
        {type !== "transfer" && soloList && (
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
                // Deleting takes everyone on it to zero, which the freeze forbids for anyone whose
                // amounts are fixed (T-157) — so say so here rather than let the server refuse it.
                disabled={deleteBlocked}
                onClick={() => onDelete(editingItem.id).then(onClose)}
              >
                {t("action.delete")}
              </button>
            )}
            {isEdit && deleteBlocked && (
              <p className="muted" style={{ fontSize: "0.8rem", margin: "0.3rem 0 0" }}>
                {t("expense.deleteBlocked")}
              </p>
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
