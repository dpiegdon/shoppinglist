import type { ListMember } from "../api/contract";
import type { MessageKey } from "../i18n/messages/en";
import type { Participant } from "./expenses";

type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

/**
 * What to call a participant (T-364, design section 8), the one wording every ledger screen uses:
 * a member as the screen already names members (initials on a row, the email in the form and on
 * balances), an unclaimed guest by name, a claimed guest by name and then its claimant
 * ("Dave · DP"), anyone else as a numbered former member.
 */
export function participantText(
  participant: Participant,
  t: Translate,
  memberText: (member: ListMember) => string,
): string {
  switch (participant.kind) {
    case "member":
      return memberText(participant.member);
    case "guest":
      return participant.guest.name;
    case "claimed":
      return t("guest.claimedLabel", {
        guest: participant.guest.name,
        claimant: participantText(participant.claimant, t, memberText),
      });
    case "former":
      return t("expense.formerMember", { number: participant.number });
  }
}
