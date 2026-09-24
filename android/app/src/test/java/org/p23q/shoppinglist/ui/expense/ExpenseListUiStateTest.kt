package org.p23q.shoppinglist.ui.expense

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ListMember

/** When Reimburse is offered (T-165), including the voter rule (T-192). */
class ExpenseListUiStateTest {

    private val me = "acct-me"
    private val other = "acct-other"
    private val third = "acct-third"
    private val members = listOf(me, other, third).map { ListMember(it, "$it@example.com", "XX") }

    // A transfer that involves neither me nor anyone who voted: only the voter rule can refuse it.
    private val betweenOthers = ExpenseMath.Transfer(from = other, to = third, cents = 2000)

    @Test
    fun `a transfer between two current members is offered`() {
        assertTrue(ExpenseListUiState(members = members, myAccountId = me).canRecord(betweenOthers))
    }

    @Test
    fun `not to someone who has agreed to close, even between two others`() {
        val state = ExpenseListUiState(members = members, myAccountId = me, closeVotes = listOf(me))
        assertFalse(state.canRecord(betweenOthers))
    }
}
