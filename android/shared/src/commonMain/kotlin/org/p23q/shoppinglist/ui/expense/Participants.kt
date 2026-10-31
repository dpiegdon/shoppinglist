package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ListGuest
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.expense_former_member
import org.p23q.shoppinglist.resources.guest_a11y
import org.p23q.shoppinglist.resources.guest_claimed_label
import org.p23q.shoppinglist.ui.stringResource

/**
 * Who is who on a ledger (T-364): its members, its guests, who has agreed to close, and the
 * numbering of everyone who is neither any more. Every screen labels a participant, decides who
 * is frozen and folds claimed guests through this one class, so they all say the same.
 *
 * Ids come in two kinds. A STORED key is what an entry's maps hold: a member, a guest (claimed or
 * not), or someone gone. An EFFECTIVE id is what the arithmetic works on: a claimed guest is its
 * claimant ([effective]), everyone else is themselves.
 */
data class Roster(
    val members: List<ListMember> = emptyList(),
    val guests: List<ListGuest> = emptyList(),
    val closeVotes: List<String> = emptyList(),
    /** Numbering of former participants over effective ids (T-197): see [formerNumbersFor]. */
    val formerNumbers: Map<String, Int> = emptyMap(),
) {
    /** Each claimed guest to its claimant: what [ExpenseMath.fold] replaces. */
    val aliases: Map<String, String> = ExpenseMath.aliasesOf(guests)

    val unclaimedGuests: List<ListGuest> get() = guests.filter { !it.isClaimed }

    /** Members, then unclaimed guests, in that order: who balancesFor and the form start from. */
    val participantIds: List<String> get() = members.map { it.accountId } + unclaimedGuests.map { it.id }

    fun member(id: String): ListMember? = members.firstOrNull { it.accountId == id }

    fun guest(id: String): ListGuest? = guests.firstOrNull { it.id == id }

    fun effective(id: String): String = aliases[id] ?: id

    /** An unclaimed guest of this list, shown as a guest rather than as someone's alias. */
    fun isUnclaimedGuest(id: String): Boolean = guest(id)?.isClaimed == false

    /**
     * Why a stored key's amounts may not move (T-157, T-203, T-364), mirroring the server's freeze:
     * an account has voted or is no longer a member; an unclaimed guest is locked or no longer a
     * guest here; a claimed guest is frozen for whatever reason its claimant is.
     */
    fun frozenReason(id: String): FrozenReason {
        val guest = guest(id)
        return when {
            guest != null && guest.claimedBy != null -> frozenReason(guest.claimedBy!!)
            guest != null -> if (guest.locked) FrozenReason.LOCKED else FrozenReason.NONE
            id in closeVotes -> FrozenReason.VOTER
            member(id) == null -> FrozenReason.FORMER
            else -> FrozenReason.NONE
        }
    }

    fun isFrozen(id: String): Boolean = frozenReason(id) != FrozenReason.NONE

    /**
     * What a stored key is called (section 8 of T-364): a member by [byEmail]'s choice of email
     * or initials, as each screen always named members; an unclaimed guest by name; a claimed
     * guest by name and then its claimant; anyone else as the former participant they are.
     */
    fun nameOf(id: String, byEmail: Boolean = false): ParticipantName {
        member(id)?.let { return ParticipantName.Member(if (byEmail) it.email else it.initials) }
        val guest = guest(id)
        if (guest != null) {
            val claimant = guest.claimedBy ?: return ParticipantName.Guest(guest.name, guest.initials)
            return ParticipantName.ClaimedGuest(guest.name, nameOf(claimant, byEmail))
        }
        return ParticipantName.Former(formerNumbers[effective(id)] ?: 0)
    }

    /** The same roster with former participants numbered over [expenses] (stored, unfolded). */
    fun withFormerNumbersFrom(expenses: List<org.p23q.shoppinglist.core.Expense>): Roster =
        copy(formerNumbers = formerNumbersFor(expenses, this))

    companion object {
        /**
         * Former-participant numbering over effective ids (T-197, T-364): the entries are folded
         * first, and everyone who is a member or a guest of the list (claimed or not) is current.
         * A removed guest and a departed claimant thus get one number each; a live claimed guest,
         * folded away, never does.
         */
        fun formerNumbersFor(expenses: List<org.p23q.shoppinglist.core.Expense>, roster: Roster): Map<String, Int> =
            ExpenseMath.formerMemberNumbers(
                ExpenseMath.foldAll(expenses, roster.aliases),
                (roster.members.map { it.accountId } + roster.guests.map { it.id }).toSet(),
            )
    }
}

/** A participant's name, resolved to what it is; [text] says it in the app's language. */
sealed interface ParticipantName {
    data class Member(val label: String) : ParticipantName
    data class Guest(val name: String, val initials: String) : ParticipantName
    /** A claimed guest: its own name, which the old entries show, then whose it is now. */
    data class ClaimedGuest(val name: String, val claimant: ParticipantName) : ParticipantName
    data class Former(val number: Int) : ParticipantName
}

/** "DP", "Dave", "Dave · DP" or "Former member 2". */
@Composable
fun ParticipantName.text(): String = when (this) {
    is ParticipantName.Member -> label
    is ParticipantName.Guest -> name
    is ParticipantName.ClaimedGuest -> stringResource(Res.string.guest_claimed_label, name, claimant.text())
    is ParticipantName.Former -> stringResource(Res.string.expense_former_member, number)
}

/**
 * A guest's badge (T-364): the initials in an outline, where a member's are plain, and "guest" in
 * what a screen reader says.
 */
@Composable
fun GuestBadge(name: String, initials: String, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.guest_a11y, name)
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = description },
    ) {
        Text(initials.ifBlank { name.take(2) }, style = MaterialTheme.typography.labelMedium)
    }
}
