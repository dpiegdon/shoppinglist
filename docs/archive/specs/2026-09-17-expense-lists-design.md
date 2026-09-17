# Expense lists — design

A third list kind, `expenses`, for a group sharing costs: who paid, for whom,
and who owes what. Tricount-like, on top of the accounts, invite-bound
membership and offline-first sync this app already has. Also works for a
single person tracking their own spending.

Decided in conversation on 2026-09-17. Implementation is broken into gittoc
tickets under the `expenses` label; this document records the decisions and
the reasons, which the tickets deliberately do not repeat.

## Decisions, in the order they were made

| Question | Decision |
|---|---|
| Is it a list? | Yes. Everything in the app is a list, and this reuses membership, invites and sync unchanged. |
| Name | Kind value `expenses`, label "Expenses". Parallel to `shopping` and `checklist`, translates without idiom. |
| What "closed" means | A read-only archive. Visible to members indefinitely, nothing editable, leaving allowed. Closing is final. |
| Currency | One per list, chosen at creation, free text with the usual codes as suggestions. Not the ISO check the price field uses. |
| The expense's text | One field, the title, reusing item `name`. Not unique — several "Dinner at Luigi's" are fine. |
| Who may edit | Any member, the flat rule every list has. |
| Storage of shares | Currency amounts, not percentages. See "Why amounts". |
| Where the money lives | One item field holding both share maps and the date. See "Why one field". |
| Closing precondition | None. Purely a vote. |
| Account deletion | Always allowed (GDPR). A departed member's shares and balance remain as a "former member". |
| Kind changes | `expenses` is fixed for life; a list of another kind cannot become one. |
| Solo use | Fully supported. One participant, trivial splits, closing is one vote. |

## The data model

### Item field `expense`

```json
"expense": {
  "paid_by":   {"<alice>": "40.00", "<bob>": "24.00"},
  "equal_by":  false,
  "paid_for":  {"<alice>": "21.34", "<bob>": "21.33", "<carol>": "21.33"},
  "equal_for": true,
  "date":      "2026-09-17"
}
```

One LWW field on the item, beside `name`, `note` and `deleted`. Null and
required-absent on every other list kind. Rules, enforced by the server on
expense lists:

- both maps non-empty; every value a positive amount in the price field's
  format (`[0-9]+(\.[0-9]{1,2})?`), never zero — a participant with no share
  is absent from the map
- the two maps sum to exactly the same value, compared as decimals in cents
- every key is a current member, or already present in the row's stored
  value — so an expense involving someone who has since left stays editable.
  Phase 2 adds the freeze rule on top: a frozen participant's amount may not
  change
- `date` is a plain calendar date, `YYYY-MM-DD`, no time, no zone; the
  form defaults it to the device's local today
- `equal_by` / `equal_for` are booleans the clients use to remember that the
  map was an equal split, so reopening the form redistributes on a changed
  total instead of erroring. The server stores them and does not cross-check
  them against the amounts.

There is no stored total. It is the sum of either map, exact by construction.

The title is `name`. The server's same-name merge (which folds two offline
creations of the same item into one) is skipped for this kind, and the
clients' duplicate-name check is off. `note` stays usable. `status`,
`category`, `stores`, `quantity` and `price` are ignored for expenses.

### List additions

Client-written, inside `fields`:

- `currency` — free text, at most 32 characters, required non-empty when
  `kind` is `expenses`, permitted but unrendered otherwise

Server-maintained, outside `fields`, following the `last_touched_by` pattern:

- `members` — `[{account_id, email, initials}]`, the current roster
- `close_votes` — account ids that have voted to close
- `closed_at` — timestamp, or null while open

Joining, leaving, account deletion and voting all bump the list's
`change_seq`, so the roster and the vote state reach every device through
the ordinary incremental sync. This is what makes the roster available
offline on both clients without a cache, and it is needed for votes in any
case. The members endpoint is unchanged and remains the source for pending
invites.

`kind` may not change to or from `expenses`, ever. Shopping and checklist
stay freely interchangeable as today.

### Why one field

Sync resolves conflicts per field, last write wins. Any invariant that spans
fields — "both maps sum to the same amount" — can be broken by two people
editing different halves of one expense offline, and the server has no way
to reject a row whose fields are individually valid but jointly wrong. One
field means the money tuple is written, resolved and validated as a unit.

### Why amounts, not percentages

- Percentages do not survive rounding. A third of 64.00 is 21.333…; every
  client would have to apply the same remainder rule at read time or two
  phones would show balances differing by cents. Amounts are validated once
  on write and are exactly true forever after; balances are a plain sum.
- Percentages cannot express a fixed share. Alice's 30 for the lobster out
  of 60 is 50%; correct the bill to 64 and she becomes 32, which is what
  "fixed" exists to prevent.
- The freeze rule is a statement about money. Under percentages, editing a
  total would change a voter's amount while their percentage stayed the
  same, and the server could not check the rule without recomputing
  rounding on both versions.
- Amounts keep history stable: a member who joins later does not silently
  re-divide old expenses.

## Closing

No proposal step. Any member can cast a close vote at any time and withdraw
it while the list is open. The list closes at the instant the last current
member's vote lands, in the same transaction. Current means current: joining
while votes are pending raises the bar; a former member does not count and
cannot block. Voting is an online action, like leave.

Once closed: sync refuses item writes and list-field writes with
`list_closed`; invite minting and redemption are refused; leave is allowed.
Balances remain viewable. Closed is final in this version. Reopening would be
the same unanimous mechanism in reverse and is left out until it is wanted.

Leaving an open expense list is refused with `list_open`. There is no delete
for expense lists at all — the `deleted` tombstone is refused with
`cannot_delete_expense_list` — since any member deleting a shared list is a
larger hole than leaving it. Closed lists are left one member at a time, and
the last leaver orphans the list, which is then tombstoned as today.

### The freeze rule

Frozen participants are the members who have voted to close, and former
members. While a list is open:

> Any write that would change a frozen participant's paid-by amount or
> paid-for amount is refused with `participant_frozen`, naming the account.
> A new row counts as a change from zero, a deletion as a change to zero.

This is what guarantees that once you have voted, your totals cannot move.
"New entries must not include voters" is the common case of it; the rule
also has to cover edits and deletions or anyone could still move your
numbers.

The check runs against the version of the field that would actually win the
per-field conflict resolution. A stale offline write that would have been
discarded anyway must not produce a spurious rejection.

Former members are included deliberately: their totals are debts and
credits belonging to someone who can no longer object.

### Former members

Shares key on account id. After an account is deleted or a member leaves
(possible only on a closed list, or on any list during phase 1), the id
stays in the maps, the balance stays in the overview, and clients render it
as "Former member 1", "Former member 2" — numbered by first appearance so two
stay distinguishable. Nothing personal remains attached to the id, which is
the same argument the audit log already relies on. Former members do not
count towards closing.

## The form

The total is the single driver. It is typed by the user, or derived as the
sum when an existing expense is opened. Both distributions are constrained
to it; neither has a total of its own, so editing one never touches the
other.

Within a distribution each selected member is auto or fixed. Fixed means the
user typed the amount. Auto members share what is left after the fixed ones,
equally.

- Change the total: fixed amounts stay, auto members absorb the difference.
- Type an amount for a member: they become fixed; the remaining auto members
  rebalance.
- Clear a member's amount: back to auto.
- Select or deselect a member: they join or leave the auto pool.

Defaults are all auto, which is what makes them follow the total. Paid-by
starts as the current user alone; paid-for as every eligible member. Frozen
participants are never in the defaults and cannot be selected.

Two invalid states, shown inline, both blocking save: fixed amounts
exceeding the total; and a fully-fixed distribution whose sum differs from
the total, which offers a one-tap "set total to X" for the case where that
sum was the intended total (two people paid by card). A fully-fixed map never
silently moves the total: a total that changes underneath you while editing
shares produces wrong numbers.

Rounding: cents. Auto members get the floor; the leftover cents go one each
to the first auto members in join order. Deterministic and identical on both
clients. The server never recomputes it, only checks the sums.

On reopen, a map whose `equal_*` flag is set loads as all-auto, otherwise as
all-fixed. Frozen participants load locked: amount shown, not editable, not
deselectable, excluded from rebalancing.

With one participant, both distribution sections are hidden — there is
nothing to choose.

## Screens

**Creating.** The kind picker gains Expenses. Choosing it reveals the
currency field, prefilled from the account default. Kind and currency are
then fixed. List properties show the currency read-only; hide kind toggle,
duplicate and delete; show leave disabled while open, with the reason; and
carry the close-vote section (who has agreed, agree / withdraw).

**Overview card.** Total spent, and your balance when there is more than
one participant. A closed marker.

**List screen.** A summary bar (total, your balance) tapping through to
balances. Expenses newest first, sectioned by date: title, amount, and a
second line such as "paid by AL · for everyone". Banners above the list for
pending votes ("2 of 4 agree to close", agree / withdraw) and for closed
("Closed on 17 Sep 2026", no add button). None of the shopping apparatus.

**Balances.** Total spent. One row per participant, current and former:
initials and email, paid, share, balance signed and coloured, largest
credit first. The balances always sum to zero.

**Non-copyable** is a client rule only — the server cannot tell a copy from
a fresh list — so the app simply does not offer duplicate for this kind.

## Sync and offline

The server is the authority for every rule; the clients pre-check only so
people rarely see an error. Two new rejection codes need different client
reactions from the existing quarantine:

- `list_closed` — the local row is **reverted** to the server's copy. An edit
  made offline before the list closed must not sit as a phantom forever.
- `participant_frozen` — the row is **quarantined** with a message naming who
  is frozen, so the existing edit-to-clear path applies: remove them from the
  split, or wait for the vote to be withdrawn.

Voting, leaving and inviting stay online-only with the control disabled
offline.

## Error codes

`list_closed`, `list_open`, `participant_frozen` (with `account_id`),
`cannot_delete_expense_list`. All in the existing envelope, with `row_id`
where a sync row is involved.

## Phases

1. **Expense lists exist and balance.** Schema, validation, roster on the
   list object, both clients' data layers, arithmetic and screens. Leaving
   still works as today; former members already render.
2. **The rules.** Close votes, the closed state and its refusals, the leave
   rule, the freeze rule, no delete. Both clients' banners, vote controls and
   rejection handling.
3. **Settlement suggestions** — "who pays whom". Not designed yet.

Each phase is a release.
