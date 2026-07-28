import type { Catalog } from "./en";

/**
 * Ukrainian (T-124). Needs a native review before being relied on.
 *
 * Ти-form throughout, matching the informal register of the other European catalogs.
 *
 * This is the catalog that would have been most expensive without T-123. Ukrainian has three
 * plural forms (1 / 2-4 / 5+, with the teens as a further exception), so every count-bearing
 * string would have needed a plural rule. Because every count now sits after a label or beside an
 * abbreviated unit, not one string here has to agree with a number.
 *
 * `backlog` becomes "Пізніше" ("later"), consistent with the other catalogs.
 *
 * Deliberately Ukrainian, not Russian-influenced: "будь ласка" not "пожалуйста", "електронна
 * пошта" not "почта", and the vocative is avoided since the app never addresses a user by name.
 */
export const uk: Catalog = {
  "app.title": "Список покупок",

  "ago.justNow": "щойно",
  "ago.minutes": "{count} хв тому",
  "ago.hours": "{count} год тому",
  "ago.days": "{count} дн тому",

  "login.email": "Електронна пошта",
  "login.password": "Пароль",
  "login.submit": "Увійти",
  "login.register": "Створити обліковий запис",
  "login.toggleToRegister": "Немає облікового запису? Зареєструйся",
  "login.toggleToLogin": "Уже маєш обліковий запис? Увійди",
  "login.registrationDisabled": "Реєстрація вимкнена на цьому сервері.",
  "login.getAndroidApp": "Завантажити застосунок для Android",
  "login.error.generic": "Щось пішло не так. Спробуй ще раз.",

  "redeem.title": "Приєднатися до списку покупок",
  "redeem.hint": "Встав код запрошення або відкрий посилання напряму.",
  "redeem.code": "Код запрошення",
  "redeem.join": "Приєднатися до списку",
  "redeem.error": "Не вдалося скористатися цим запрошенням.",

  "common.loading": "Завантаження…",
  "overview.empty": "Списків ще немає. Створи один, щоб почати.",
  "list.registry.empty": "Товарів не знайдено.",
  "list.categoryFixed": "Регістр виправлено в {category}: {count}",

  "sync.syncing": "Синхронізація…",
  "sync.synced": "Синхронізовано {ago}",
  "sync.failed": "Помилка синхронізації",
  "sync.failedSince": "Помилка синхронізації · востаннє успішно {ago}",
  "sync.never": "Ще не синхронізовано",
  "sync.now": "Синхронізувати зараз",

  "lastSeen.activeNow": "Зараз активний",

  "item.add": "Додати товар",
  "item.edit": "Редагувати товар",
  "item.name": "Назва",
  "item.category": "Категорія",
  "item.stores": "Магазини",
  "item.addStore": "Додати магазин",
  "item.quantity": "Кількість",
  "item.price": "Ціна",
  "item.currency": "Валюта",
  "item.note": "Нотатка",
  "item.statusLabel": "Статус",
  "item.status.todo": "Купити",
  "item.status.checked": "Готово",
  "item.status.backlog": "Пізніше",
  "item.status.backlogHint": "Ще не в списку",
  "item.addAnother": "Додати ще",
  "item.saveFailed": "Не вдалося зберегти. Спробуй ще раз.",

  "listProps.title": "Властивості списку",
  "listProps.name": "Назва",
  "listProps.type": "Тип",
  "listProps.kind.checklist": "Товари мають назву, категорію та нотатку.",
  "listProps.kind.shopping": "Товари також мають магазини, кількість і ціну.",
  "listProps.makeShopping": "Зробити списком покупок",
  "listProps.makeChecklist": "Зробити контрольним списком",
  "listProps.categories": "Категорії",
  "listProps.noCategories": "Категорій ще немає.",
  "listProps.moveUp": "Вгору",
  "listProps.moveDown": "Вниз",
  "listProps.removeFromOrder": "Прибрати з порядку",
  "listProps.addToOrder": "Додати до порядку",
  "listProps.addCategory": "Додати категорію…",
  "listProps.clearChecked": "Скинути виконані",
  "listProps.notes": "Нотатки",
  "listProps.notesPlaceholder": "Код воріт, години роботи, усе, що варто пам'ятати…",
  "listProps.members": "Учасники",
  "listProps.inviteByEmail": "Запросити електронною поштою…",
  "listProps.inviteLink": "Посилання-запрошення",
  "listProps.clearCheckedHelp": "Повертає всі виконані товари до «Пізніше».",
  "listProps.saveNotes": "Зберегти нотатки",
  "listProps.pendingInvites": "Очікувані запрошення",
  "listProps.leaveList": "Покинути список",
  "listProps.membersFailed": "Не вдалося завантажити учасників.",
  "listProps.inviteFailed": "Не вдалося надіслати запрошення.",
  "listProps.leaveConfirm": "Покинути цей список? Ти втратиш доступ до нього.",

  "action.cancel": "Скасувати",
  "action.save": "Зберегти",
  "action.delete": "Видалити",
  "action.revoke": "Відкликати",
  "action.undo": "Скасувати дію",
  "action.invite": "Запросити",
  "action.create": "Створити",
  "action.duplicate": "Дублювати",
  "action.copy": "Копіювати",
  "action.copied": "Скопійовано!",
  "common.saved": "Збережено.",

  "nav.overview": "Огляд",
  "nav.joinList": "Приєднатися до списку",
  "nav.logOut": "Вийти",
  "nav.menu": "Меню",

  "error.generic": "Щось пішло не так.",

  "overview.title": "Твої списки",
  "overview.newList": "Новий список",
  "overview.name": "Назва",
  "overview.type": "Тип",
  "overview.kind.checklist": "Лише назви, категорії та нотатки.",
  "overview.kind.shopping": "Додає магазини, кількість і ціну до кожного товару.",

  "list.notFound": "Список не знайдено (або ти більше не маєш доступу).",
  "list.backToOverview": "Назад до огляду",
  "list.backToAllLists": "Назад до всіх списків",
  "list.allItems": "Усі товари",
  "list.search": "Пошук товарів…",
  "list.empty": "У цьому списку ще нічого немає. Додай товар, щоб почати.",
  "list.checkedOff": "Товар позначено.",

  "settings.language": "Мова",
  "settings.title": "Налаштування облікового запису",
  "settings.serverAdmin": "Адміністрування сервера",
  "settings.defaultCurrency": "Валюта за замовчуванням",
  "settings.initials": "Ініціали",
  "settings.changePassword": "Змінити пароль",
  "settings.currentPassword": "Поточний пароль",
  "settings.newPassword": "Новий пароль",
  "settings.passwordChanged": "Пароль змінено.",
  "settings.changeEmail": "Змінити електронну пошту",
  "settings.newEmail": "Нова електронна пошта",
  "settings.emailChanged": "Електронну пошту змінено.",
  "settings.sessions": "Сеанси",
  "settings.deleteAccount": "Видалити обліковий запис",
  "settings.openServerAdmin": "Відкрити адміністрування сервера",
  "settings.deleteMyAccount": "Видалити мій обліковий запис",
  "settings.password": "Пароль",
  "settings.deleteConfirm": "Це назавжди видалить твій обліковий запис. Ти впевнений?",

  "admin.title": "Адміністрування сервера",
  "admin.registration": "Реєстрація",
  "admin.allowNewAccounts": "Дозволити нові облікові записи",
  "admin.users": "Користувачі",
  "admin.yourPassword": "Твій пароль (потрібен для скидання чи видалення)",
  "admin.deleteUserTitle": "Видалити користувача?",
  "admin.resetPassword": "Скинути пароль",
  "admin.passwordRequired": "Введи свій пароль, щоб скинути або видалити користувача.",
  "admin.loadFailed": "Не вдалося завантажити дані адміністрування.",
  "admin.updateFailed": "Не вдалося оновити.",
  "admin.resetFailed": "Не вдалося скинути пароль.",
  "admin.deleteFailed": "Не вдалося видалити користувача.",
  "admin.sessionCount": "Сеанси: {count}",
  "admin.isAdmin": "(адмін)",
};
