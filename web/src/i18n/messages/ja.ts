import type { Catalog } from "./en";

/**
 * Japanese (T-124). Needs a native review before being relied on.
 *
 * です・ます (polite) register throughout. Unlike the European catalogs, where the informal form is
 * the natural choice for a household app, plain form in a Japanese UI reads brusque — politeness
 * is the neutral default here, not a formal one.
 *
 * Like Chinese: no plural forms, and text is shorter than English, so nothing can overflow a
 * control that already fits the source string.
 *
 * `backlog` becomes あとで ("later"), consistent with the other catalogs and for the same reason.
 * Written in kana rather than 後で — the softer form suits a consumer app, and it is what most
 * Japanese apps use for this kind of deferral action.
 *
 * Punctuation is full-width (：、。？). Numbers stay ASCII, matching the wire format (T-125).
 */
export const ja: Catalog = {
  "app.title": "買い物リスト",

  "ago.justNow": "たった今",
  "ago.minutes": "{count} 分前",
  "ago.hours": "{count} 時間前",
  "ago.days": "{count} 日前",

  "login.email": "メールアドレス",
  "login.password": "パスワード",
  "login.submit": "ログイン",
  "login.register": "アカウントを作成",
  "login.toggleToRegister": "アカウントがありませんか？ 新規登録",
  "login.toggleToLogin": "アカウントをお持ちですか？ ログイン",
  "login.registrationDisabled": "このサーバーでは新規登録が無効になっています。",
  "login.getAndroidApp": "Android アプリを入手",
  "login.error.generic": "問題が発生しました。もう一度お試しください。",

  "redeem.title": "買い物リストに参加",
  "redeem.hint": "招待コードを貼り付けるか、招待リンクを直接開いてください。",
  "redeem.code": "招待コード",
  "redeem.join": "リストに参加",
  "redeem.error": "この招待を利用できませんでした。",

  "common.loading": "読み込み中…",
  "overview.empty": "リストがまだありません。作成して始めましょう。",
  "list.registry.empty": "アイテムが見つかりません。",
  "list.categoryFixed": "{category} の表記を修正しました：{count}",

  "sync.syncing": "同期中…",
  "sync.synced": "同期済み {ago}",
  "sync.failed": "同期に失敗しました",
  "sync.failedSince": "同期に失敗しました · 最終成功 {ago}",
  "sync.never": "まだ同期していません",
  "sync.now": "今すぐ同期",

  "lastSeen.activeNow": "現在アクティブ",

  "item.add": "アイテムを追加",
  "item.edit": "アイテムを編集",
  "item.name": "名前",
  "item.category": "カテゴリ",
  "item.stores": "店舗",
  "item.addStore": "店舗を追加",
  "item.quantity": "数量",
  "item.price": "価格",
  "item.currency": "通貨",
  "item.note": "メモ",
  "item.statusLabel": "ステータス",
  "item.status.todo": "購入予定",
  "item.status.checked": "完了",
  "item.status.backlog": "あとで",
  "item.status.backlogHint": "まだリストに入っていません",
  "item.addAnother": "続けて追加",
  "item.saveFailed": "保存できませんでした。もう一度お試しください。",

  "listProps.title": "リストのプロパティ",
  "listProps.name": "名前",
  "listProps.type": "種類",
  "listProps.kind.checklist": "アイテムには名前、カテゴリ、メモがあります。",
  "listProps.kind.shopping": "アイテムには店舗、数量、価格もあります。",
  "listProps.makeShopping": "買い物リストに変更",
  "listProps.makeChecklist": "チェックリストに変更",
  "listProps.categories": "カテゴリ",
  "listProps.noCategories": "カテゴリがまだありません。",
  "listProps.moveUp": "上へ移動",
  "listProps.moveDown": "下へ移動",
  "listProps.removeFromOrder": "並び順から削除",
  "listProps.addToOrder": "並び順に追加",
  "listProps.addCategory": "カテゴリを追加…",
  "listProps.clearChecked": "完了済みをリセット",
  "listProps.notes": "メモ",
  "listProps.notesPlaceholder": "ゲートの暗証番号、営業時間など、覚えておきたいこと…",
  "listProps.members": "メンバー",
  "listProps.inviteByEmail": "メールで招待…",
  "listProps.inviteLink": "招待リンク",
  "listProps.clearCheckedHelp": "完了済みのアイテムをすべて「あとで」に戻します。",
  "listProps.saveNotes": "メモを保存",
  "listProps.pendingInvites": "保留中の招待",
  "listProps.leaveList": "リストから退出",
  "listProps.membersFailed": "メンバーを読み込めませんでした。",
  "listProps.inviteFailed": "招待を送信できませんでした。",
  "listProps.leaveConfirm": "このリストから退出しますか？ アクセスできなくなります。",

  "action.cancel": "キャンセル",
  "action.save": "保存",
  "action.delete": "削除",
  "action.revoke": "取り消す",
  "action.undo": "元に戻す",
  "action.invite": "招待",
  "action.create": "作成",
  "action.duplicate": "複製",
  "action.copy": "コピー",
  "action.copied": "コピーしました！",
  "common.saved": "保存しました。",

  "nav.overview": "概要",
  "nav.joinList": "リストに参加",
  "nav.logOut": "ログアウト",
  "nav.menu": "メニュー",

  "error.generic": "問題が発生しました。",

  "overview.title": "あなたのリスト",
  "overview.newList": "新しいリスト",
  "overview.name": "名前",
  "overview.type": "種類",
  "overview.kind.checklist": "名前、カテゴリ、メモのみ。",
  "overview.kind.shopping": "各アイテムに店舗、数量、価格を追加します。",

  "list.notFound": "リストが見つかりません（またはアクセス権がありません）。",
  "list.backToOverview": "概要に戻る",
  "list.backToAllLists": "すべてのリストに戻る",
  "list.allItems": "すべてのアイテム",
  "list.search": "アイテムを検索…",
  "list.empty": "このリストにはまだ何もありません。アイテムを追加してください。",
  "list.checkedOff": "アイテムをチェックしました。",

  "settings.language": "言語",
  "settings.title": "アカウント設定",
  "settings.serverAdmin": "サーバー管理",
  "settings.defaultCurrency": "デフォルトの通貨",
  "settings.initials": "イニシャルを表示",
  "settings.changePassword": "パスワードを変更",
  "settings.currentPassword": "現在のパスワード",
  "settings.newPassword": "新しいパスワード",
  "settings.passwordChanged": "パスワードを変更しました。",
  "settings.changeEmail": "メールアドレスを変更",
  "settings.newEmail": "新しいメールアドレス",
  "settings.emailChanged": "メールアドレスを変更しました。",
  "settings.sessions": "セッション",
  "settings.deleteAccount": "アカウントを削除",
  "settings.openServerAdmin": "サーバー管理を開く",
  "settings.deleteMyAccount": "自分のアカウントを削除",
  "settings.password": "パスワード",
  "settings.deleteConfirm": "アカウントが完全に削除されます。よろしいですか？",

  "admin.title": "サーバー管理",
  "admin.registration": "新規登録",
  "admin.allowNewAccounts": "新規アカウントを許可",
  "admin.users": "ユーザー",
  "admin.yourPassword": "あなたのパスワード（リセット・削除に必要）",
  "admin.deleteUserTitle": "ユーザーを削除しますか？",
  "admin.resetPassword": "パスワードをリセット",
  "admin.passwordRequired": "ユーザーをリセットまたは削除するには、パスワードを入力してください。",
  "admin.loadFailed": "管理データを読み込めませんでした。",
  "admin.updateFailed": "更新できませんでした。",
  "admin.resetFailed": "パスワードをリセットできませんでした。",
  "admin.deleteFailed": "ユーザーを削除できませんでした。",
  "admin.sessionCount": "セッション：{count}",
  "admin.isAdmin": "（管理者）",
};
