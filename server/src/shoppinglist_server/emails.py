"""E-mail address folding (T-328).

A leaf module on purpose: auth, accounts, invites and the migrations all need the one rule, and
migrations is imported while the package itself is still initialising, so the rule cannot live
anywhere that imports the package. accounts re-exports it as `accounts.normalize_email`.
"""

import unicodedata


def normalize_email(email: str) -> str:
    """The one form every comparison of two e-mail addresses uses (T-328).

    Two addresses are the same account when they are equal after this: surrounding whitespace
    dropped, NFC, full Unicode case folding, NFC again. `accounts.email_normalized` and
    `invites.invited_email_normalized` hold its result, and every lookup compares those columns
    or this function's output, never SQLite's `lower()` (ASCII-only) or Python's `.lower()` (not
    a folding: it keeps 'ß' apart from 'SS').

    The second NFC is not decoration. Folding can leave a string that is canonically equivalent
    to, but not identical with, the folding of its own upper-case form: 'ΐ' folds to three code
    points, while 'ΐ'.upper() comes back through NFC and folding as two. Without it, an account
    registered as 'ΐ@example.com' could not log in as 'Ϊ́@EXAMPLE.COM'.

    One pair stays apart by design: dotless 'ı' upper-cases to 'I', which folds to 'i'. Unicode's
    default folding keeps 'ı' distinct; joining the two is the Turkic-locale folding, and a server
    has no locale to pick it by.

    The typed address is what is stored in `email` and shown; this form is never displayed.
    """
    return unicodedata.normalize("NFC", unicodedata.normalize("NFC", email.strip()).casefold())
