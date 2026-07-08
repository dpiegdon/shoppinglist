# Example prompts / prompt-sets for use with gittoc:

## The Big Housekeeping

Three (potentially somewhat concurrent) agent-sessions do a full review of the codebase and try to fix the easy bits.

### Prompt #1 -- The Auditor

Please first read the gittoc's SKILL.md file to understand the ticketsystem we are using.

Then please review the sourcecode for <<<<<<<<<<<<<< *INSERT SHORT PRODUCT DESCRIPTION HERE*

Review the full codebase and create an issue with gittoc for each thing you consider problematic or improvable.
Make a quick first priority (i.e. criticality) assessment, but don't invest too much time here.
Do NOT make any changes to the codebase itself. You are to only create tickets.

This includes but is not limited to tickets for:
- chores (minor code cleanup, minimal refactoring) ("chore")
- internal code cleanup that preserves existing behaviour ("refactor")
- file/module reorganisation without logic changes, SOLID and subsystem boundaries ("structure")
- security issues ("security")
- usability improvement ("ux")
- build, test, deployment, or infrastructure tooling ("ops")
- outright bugs ("bug")
- performance improvements ("perf")
- documentation ("docs")
- error handling, resilience, and robustness ("reliability")
- etc.

Add subset of labels accordingly:
- chore, refactor, structure, security, ux, ops, bug, perf, docs, reliability, ...

If a ticket is safe for autonomous agent implementation without human supervision, also add 'agent'.
If a ticket requires human review or a decision before it can proceed, add 'human'.

And one label per subsystem, container and/or service affected in the codebase: <<<<<<<<<<<<<< *FIXME*

Tickets should be precisely and succinctly describe the issue. Maybe also how to reproduce, if that is complicated, and/or how to fix, if that is easy to determinte without big investigation.

If you think solutions to tickets are easy and minimally invasive, label these with 'ready'.

Label anything that needs to be reviewed and decided by a human with 'human'.

### Prompt #2 -- The Manager

Please first read the gittoc's SKILL.md file to understand the ticketsystem we are using.

Then please start grooming all tickets currently in the ticket system.
Do NOT make any changes to the codebase itself.

Labels exist for the following contexts:
- chores (minor code cleanup, minimal refactoring) ("chore")
- internal code cleanup that preserves existing behaviour ("refactor")
- file/module reorganisation without logic changes, SOLID and subsystem boundaries ("structure")
- security issues ("security")
- usability improvement ("ux")
- build, test, deployment, or infrastructure tooling ("ops")
- outright bugs ("bug")
- performance improvements ("perf")
- documentation ("docs")
- error handling, resilience, and robustness ("reliability")
- tickets that need to be checked by a human ("human") -- you may set this one, and also remove it if you are sure this ticket doesn't need a review by a human.
- tickets safe for autonomous agent implementation without human supervision ("agent") -- set when a ticket is clearly scoped and does not require human judgment.
- tickets that have a clear problem statement, concrete acceptance criteria, clear scope boundaries, identified dependencies (if any), a clearly defined or very easy solution and are estimable in effort and are thus ready to be implemented ("ready")
- one label per subsystem, container and/or service affected in the codebase: <<<<<<<<<<<<<< *FIXME*

Your task is to go through all tickets one-by-one, groom them and try to move them towards readiness:
- Check ticket validity and labels and change anything that doesn't match.
- Try to move tickets toward readyness, but only if it's possible:
  - Add dependencies where appropriate
  - Link related tickets with notes
  - Analyse context and add notes with suggestions or clear paths to fix it. Only add notes if you feel that they progresses the ticket toward readyness or improve on them otherwise.
  - etc
- You may also reject tickets where you are sure they're invalid. If you are not quite sure, add your doubts as a note.
- Feel free to reprioritize tickets by criticality.
- Tickets should only ever be labeled with 'ready' if the fix is clear and clearly outlined, and the software will work the same or better after the fix is applied.

When you have iterated through all tickets then double-check that no further tickets have been added
to the system in the meantime. If any were, groom these too, and re-check.

Only stop once you checked and there were no more new tickets in the system.

### Prompt #3 -- The Fixer

Please first read the gittoc's SKILL.md file to understand the ticketsystem we are using.

Then please start picking tickets with the 'ready' label and fix them in the codebase, unless they also have the 'human' label. 'human' tickets need someone else to revisit them first and should be considered blocked.
Only ever pick one at a time, and only those with 'ready' as label.
Claim the ticket, implement it, commit with a proper message with the ticket-number at the end of first line, and then close it.
Then continue to the next ready ticket.
Once you reach the end double-check that no further ready tickets have been added to the system.
If any were, fix these too, and re-check.
