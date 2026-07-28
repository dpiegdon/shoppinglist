import type { Catalog } from "./en";

/**
 * German (T-124).
 *
 * Reviewed against the running UI rather than translated key-by-key in isolation — German is the
 * worst case for text expansion (20-35% over English, worse for compound nouns), so this is the
 * catalog most likely to surface a layout that cannot take longer strings.
 *
 * Conventions used here:
 * - Du-form throughout ("Melde dich an"), not Sie. A shared household shopping list is informal,
 *   and mixing the two registers is the usual way an app's German reads wrong.
 * - Unit abbreviations follow German convention: Min / Std / T. They never inflect, which is what
 *   keeps plural handling out of the codebase entirely (T-123).
 * - "Backlog" is kept as a loanword. It is established in German product usage, and the
 *   alternatives ("Merkliste", "Vorrat") each mean something subtly different from "wanted but not
 *   on this trip's list". The hint string carries the actual explanation.
 */
export const de: Catalog = {
  "app.title": "Einkaufsliste",

  "ago.justNow": "gerade eben",
  "ago.minutes": "vor {count} Min",
  "ago.hours": "vor {count} Std",
  "ago.days": "vor {count} T",

  "login.email": "E-Mail",
  "login.password": "Passwort",
  "login.submit": "Anmelden",
  "login.register": "Konto erstellen",
  "login.toggleToRegister": "Noch kein Konto? Registrieren",
  "login.toggleToLogin": "Schon ein Konto? Anmelden",
  "login.registrationDisabled": "Registrierung ist auf diesem Server deaktiviert.",
  "login.getAndroidApp": "Android-App herunterladen",
  "login.error.generic": "Etwas ist schiefgelaufen. Bitte versuche es erneut.",

  "redeem.title": "Einkaufsliste beitreten",
  "redeem.hint": "Füge den Einladungscode ein oder öffne den Einladungslink direkt.",
  "redeem.code": "Einladungscode",
  "redeem.join": "Liste beitreten",
  "redeem.error": "Diese Einladung konnte nicht eingelöst werden.",

  "common.loading": "Wird geladen…",
  "overview.empty": "Noch keine Listen. Erstelle eine, um loszulegen.",
  "list.registry.empty": "Keine Artikel gefunden.",
  "list.categoryFixed": "Schreibweise in {category} korrigiert: {count}",

  "sync.syncing": "Wird synchronisiert…",
  "sync.synced": "Synchronisiert {ago}",
  "sync.failed": "Synchronisierung fehlgeschlagen",
  "sync.failedSince": "Synchronisierung fehlgeschlagen · zuletzt erfolgreich {ago}",
  "sync.never": "Noch nicht synchronisiert",
  "sync.now": "Jetzt synchronisieren",

  "lastSeen.activeNow": "Gerade aktiv",

  "item.add": "Artikel hinzufügen",
  "item.edit": "Artikel bearbeiten",
  "item.name": "Name",
  "item.category": "Kategorie",
  "item.stores": "Geschäfte",
  "item.addStore": "Geschäft hinzufügen",
  "item.quantity": "Menge",
  "item.price": "Preis",
  "item.currency": "Währung",
  "item.note": "Notiz",
  "item.statusLabel": "Status",
  "item.status.todo": "Zu kaufen",
  "item.status.checked": "Erledigt",
  "item.status.backlog": "Backlog",
  "item.status.backlogHint": "Noch nicht auf der Liste",
  "item.addAnother": "Weiteren hinzufügen",
  "item.saveFailed": "Speichern fehlgeschlagen. Bitte versuche es erneut.",

  "listProps.title": "Listeneigenschaften",
  "listProps.name": "Name",
  "listProps.type": "Typ",
  "listProps.kind.checklist": "Artikel haben Name, Kategorie und Notiz.",
  "listProps.kind.shopping": "Artikel haben zusätzlich Geschäfte, Menge und Preis.",
  "listProps.makeShopping": "In Einkaufsliste umwandeln",
  "listProps.makeChecklist": "In Checkliste umwandeln",
  "listProps.categories": "Kategorien",
  "listProps.noCategories": "Noch keine Kategorien.",
  "listProps.moveUp": "Nach oben",
  "listProps.moveDown": "Nach unten",
  "listProps.removeFromOrder": "Aus der Reihenfolge entfernen",
  "listProps.addToOrder": "Zur Reihenfolge hinzufügen",
  "listProps.addCategory": "Kategorie hinzufügen…",
  "listProps.clearChecked": "Erledigte zurücksetzen",
  "listProps.notes": "Notizen",
  "listProps.notesPlaceholder": "Torcode, Öffnungszeiten, alles Erwähnenswerte…",
  "listProps.members": "Mitglieder",
  "listProps.inviteByEmail": "Per E-Mail einladen…",
  "listProps.inviteLink": "Einladungslink",
  "listProps.clearCheckedHelp": "Setzt alle erledigten Artikel zurück ins Backlog.",
  "listProps.saveNotes": "Notizen speichern",
  "listProps.pendingInvites": "Offene Einladungen",
  "listProps.leaveList": "Liste verlassen",
  "listProps.membersFailed": "Mitglieder konnten nicht geladen werden.",
  "listProps.inviteFailed": "Einladung konnte nicht gesendet werden.",
  "listProps.leaveConfirm": "Diese Liste verlassen? Du verlierst den Zugriff darauf.",

  "action.cancel": "Abbrechen",
  "action.save": "Speichern",
  "action.delete": "Löschen",
  "action.revoke": "Widerrufen",
  "action.undo": "Rückgängig",
  "action.invite": "Einladen",
  "action.create": "Erstellen",
  "action.duplicate": "Duplizieren",
  "action.copy": "Kopieren",
  "action.copied": "Kopiert!",
  "common.saved": "Gespeichert.",

  "nav.overview": "Übersicht",
  "nav.joinList": "Liste beitreten",
  "nav.logOut": "Abmelden",
  "nav.menu": "Menü",

  "error.generic": "Etwas ist schiefgelaufen.",

  "overview.title": "Deine Listen",
  "overview.newList": "Neue Liste",
  "overview.name": "Name",
  "overview.type": "Typ",
  "overview.kind.checklist": "Nur Namen, Kategorien und Notizen.",
  "overview.kind.shopping": "Ergänzt jeden Artikel um Geschäfte, Menge und Preis.",

  "list.notFound": "Liste nicht gefunden (oder du hast keinen Zugriff mehr).",
  "list.backToOverview": "Zurück zur Übersicht",
  "list.backToAllLists": "Zurück zu allen Listen",
  "list.allItems": "Alle Artikel",
  "list.search": "Artikel suchen…",
  "list.empty": "Noch nichts auf dieser Liste. Füge einen Artikel hinzu.",
  "list.checkedOff": "Artikel abgehakt.",

  "settings.language": "Sprache",
  "settings.title": "Kontoeinstellungen",
  "settings.serverAdmin": "Serververwaltung",
  "settings.defaultCurrency": "Standardwährung",
  "settings.initials": "Initialen",
  "settings.changePassword": "Passwort ändern",
  "settings.currentPassword": "Aktuelles Passwort",
  "settings.newPassword": "Neues Passwort",
  "settings.passwordChanged": "Passwort geändert.",
  "settings.changeEmail": "E-Mail ändern",
  "settings.newEmail": "Neue E-Mail",
  "settings.emailChanged": "E-Mail geändert.",
  "settings.sessions": "Sitzungen",
  "settings.deleteAccount": "Konto löschen",
  "settings.openServerAdmin": "Serververwaltung öffnen",
  "settings.deleteMyAccount": "Mein Konto löschen",
  "settings.password": "Passwort",
  "settings.deleteConfirm": "Dadurch wird dein Konto dauerhaft gelöscht. Bist du sicher?",

  "admin.title": "Serververwaltung",
  "admin.registration": "Registrierung",
  "admin.allowNewAccounts": "Neue Konten zulassen",
  "admin.users": "Benutzer",
  "admin.yourPassword": "Dein Passwort (für Zurücksetzen/Löschen erforderlich)",
  "admin.deleteUserTitle": "Benutzer löschen?",
  "admin.resetPassword": "Passwort zurücksetzen",
  "admin.passwordRequired": "Gib dein Passwort ein, um einen Benutzer zurückzusetzen oder zu löschen.",
  "admin.loadFailed": "Admin-Daten konnten nicht geladen werden.",
  "admin.updateFailed": "Aktualisierung fehlgeschlagen.",
  "admin.resetFailed": "Passwort konnte nicht zurückgesetzt werden.",
  "admin.deleteFailed": "Benutzer konnte nicht gelöscht werden.",
  "admin.sessionCount": "Sitzungen: {count}",
  "admin.isAdmin": "(Admin)",
};
