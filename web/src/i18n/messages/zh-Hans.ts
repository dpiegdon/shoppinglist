import type { Catalog } from "./en";

/**
 * Simplified Chinese (T-124). Needs a native review before being relied on.
 *
 * The layout-safe language: Chinese text is consistently SHORTER than English, so nothing here can
 * overflow a control that already fits the English string. It is also the one with no plural
 * forms at all, which the abbreviated-unit approach (T-123) makes irrelevant anyway.
 *
 * `backlog` becomes 稍后 ("later"), consistent with the Romance catalogs and for the same reason —
 * the English term carries no meaning for a consumer audience, and the hint string is what defines
 * the state precisely.
 *
 * Punctuation is full-width (：，。？) as Chinese typography requires, EXCEPT where a placeholder
 * sits adjacent to a Latin-script value: "会话：{count}" keeps the full-width colon because the
 * value is a number, while nothing here interpolates a Latin word directly against punctuation.
 */
export const zhHans: Catalog = {
  "app.title": "购物清单",

  "ago.justNow": "刚刚",
  "ago.minutes": "{count} 分钟前",
  "ago.hours": "{count} 小时前",
  "ago.days": "{count} 天前",

  "login.email": "电子邮箱",
  "login.password": "密码",
  "login.submit": "登录",
  "login.register": "创建账户",
  "login.toggleToRegister": "还没有账户？立即注册",
  "login.toggleToLogin": "已有账户？立即登录",
  "login.registrationDisabled": "此服务器已停用注册功能。",
  "login.getAndroidApp": "下载安卓应用",
  "login.error.generic": "出错了，请重试。",

  "redeem.title": "加入购物清单",
  "redeem.hint": "粘贴邀请码，或直接打开邀请链接。",
  "redeem.code": "邀请码",
  "redeem.join": "加入清单",
  "redeem.error": "无法使用此邀请。",

  "common.loading": "加载中…",
  "overview.empty": "还没有清单。创建一个开始吧。",
  "list.registry.empty": "未找到物品。",
  "list.categoryFixed": "已修正 {category} 的大小写：{count}",

  "sync.syncing": "正在同步…",
  "sync.synced": "已同步 {ago}",
  "sync.failed": "同步失败",
  "sync.failedSince": "同步失败 · 上次成功 {ago}",
  "sync.never": "尚未同步",
  "sync.now": "立即同步",

  "lastSeen.activeNow": "当前活跃",

  "item.add": "添加物品",
  "item.edit": "编辑物品",
  "item.name": "名称",
  "item.category": "分类",
  "item.stores": "商店",
  "item.addStore": "添加商店",
  "item.quantity": "数量",
  "item.price": "价格",
  "item.currency": "货币",
  "item.note": "备注",
  "item.statusLabel": "状态",
  "item.status.todo": "待购买",
  "item.status.checked": "已完成",
  "item.status.backlog": "稍后",
  "item.status.backlogHint": "尚未加入清单",
  "item.addAnother": "继续添加",
  "item.saveFailed": "保存失败，请重试。",

  "listProps.title": "清单属性",
  "listProps.name": "名称",
  "listProps.type": "类型",
  "listProps.kind.checklist": "物品包含名称、分类和备注。",
  "listProps.kind.shopping": "物品还包含商店、数量和价格。",
  "listProps.makeShopping": "转为购物清单",
  "listProps.makeChecklist": "转为待办清单",
  "listProps.categories": "分类",
  "listProps.noCategories": "还没有分类。",
  "listProps.moveUp": "上移",
  "listProps.moveDown": "下移",
  "listProps.removeFromOrder": "从排序中移除",
  "listProps.addToOrder": "加入排序",
  "listProps.addCategory": "添加分类…",
  "listProps.clearChecked": "重置已完成",
  "listProps.notes": "备注",
  "listProps.notesPlaceholder": "门禁密码、营业时间，任何值得记住的信息…",
  "listProps.members": "成员",
  "listProps.inviteByEmail": "通过邮箱邀请…",
  "listProps.inviteLink": "邀请链接",
  "listProps.clearCheckedHelp": "将所有已完成的物品移回「稍后」。",
  "listProps.saveNotes": "保存备注",
  "listProps.pendingInvites": "待处理的邀请",
  "listProps.leaveList": "退出清单",
  "listProps.membersFailed": "无法加载成员。",
  "listProps.inviteFailed": "无法发送邀请。",
  "listProps.leaveConfirm": "要退出此清单吗？你将失去访问权限。",

  "action.cancel": "取消",
  "action.save": "保存",
  "action.delete": "删除",
  "action.revoke": "撤销",
  "action.undo": "撤消",
  "action.invite": "邀请",
  "action.create": "创建",
  "action.duplicate": "复制",
  "action.copy": "复制",
  "action.copied": "已复制！",
  "common.saved": "已保存。",

  "nav.overview": "总览",
  "nav.joinList": "加入清单",
  "nav.logOut": "退出登录",
  "nav.menu": "菜单",

  "error.generic": "出错了。",

  "overview.title": "你的清单",
  "overview.newList": "新建清单",
  "overview.name": "名称",
  "overview.type": "类型",
  "overview.kind.checklist": "仅包含名称、分类和备注。",
  "overview.kind.shopping": "为每件物品添加商店、数量和价格。",

  "list.notFound": "未找到清单（或你已无访问权限）。",
  "list.backToOverview": "返回总览",
  "list.backToAllLists": "返回所有清单",
  "list.allItems": "所有物品",
  "list.search": "搜索物品…",
  "list.empty": "此清单还是空的。添加一件物品开始吧。",
  "list.checkedOff": "物品已勾选。",

  "settings.language": "语言",
  "settings.title": "账户设置",
  "settings.serverAdmin": "服务器管理",
  "settings.defaultCurrency": "默认货币",
  "settings.initials": "显示缩写",
  "settings.changePassword": "修改密码",
  "settings.currentPassword": "当前密码",
  "settings.newPassword": "新密码",
  "settings.passwordChanged": "密码已修改。",
  "settings.changeEmail": "修改邮箱",
  "settings.newEmail": "新邮箱",
  "settings.emailChanged": "邮箱已修改。",
  "settings.sessions": "会话",
  "settings.deleteAccount": "删除账户",
  "settings.openServerAdmin": "打开服务器管理",
  "settings.deleteMyAccount": "删除我的账户",
  "settings.password": "密码",
  "settings.deleteConfirm": "这将永久删除你的账户。确定吗？",

  "admin.title": "服务器管理",
  "admin.registration": "注册",
  "admin.allowNewAccounts": "允许新账户",
  "admin.users": "用户",
  "admin.yourPassword": "你的密码（重置或删除时需要）",
  "admin.deleteUserTitle": "删除用户？",
  "admin.resetPassword": "重置密码",
  "admin.passwordRequired": "请输入你的密码以重置或删除用户。",
  "admin.loadFailed": "无法加载管理数据。",
  "admin.updateFailed": "更新失败。",
  "admin.resetFailed": "无法重置密码。",
  "admin.deleteFailed": "无法删除用户。",
  "admin.sessionCount": "会话：{count}",
  "admin.isAdmin": "（管理员）",
};
