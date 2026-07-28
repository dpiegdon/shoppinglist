import type { Catalog } from "./en";

/**
 * Brazilian Portuguese (T-124). Needs a native review before being relied on.
 *
 * Você-form throughout — the natural register in Brazil, and consistent with the informal tone the
 * other catalogs use.
 *
 * `backlog` becomes "Mais tarde", same reasoning as the Spanish and French catalogs.
 *
 * This is the pt-BR variant specifically. A European Portuguese browser resolves here too
 * (locales.ts falls back from pt-PT to the variant we ship, since that beats falling all the way
 * back to English), so a few choices differ from what a Lisbon reader would expect —
 * "tela"/"celular" rather than "ecrã"/"telemóvel" and so on. None of those words appear here, but
 * it is worth a reviewer knowing which variant this is meant to be.
 */
export const ptBR: Catalog = {
  "app.title": "Lista de compras",

  "ago.justNow": "agora mesmo",
  "ago.minutes": "há {count} min",
  "ago.hours": "há {count} h",
  "ago.days": "há {count} d",

  "login.email": "E-mail",
  "login.password": "Senha",
  "login.submit": "Entrar",
  "login.register": "Criar conta",
  "login.toggleToRegister": "Não tem conta? Cadastre-se",
  "login.toggleToLogin": "Já tem conta? Entrar",
  "login.registrationDisabled": "O cadastro está desativado neste servidor.",
  "login.getAndroidApp": "Baixar o app Android",
  "login.error.generic": "Algo deu errado. Tente novamente.",

  "redeem.title": "Entrar em uma lista de compras",
  "redeem.hint": "Cole o código do convite ou abra o link do convite diretamente.",
  "redeem.code": "Código do convite",
  "redeem.join": "Entrar na lista",
  "redeem.error": "Não foi possível usar este convite.",

  "common.loading": "Carregando…",
  "overview.empty": "Nenhuma lista ainda. Crie uma para começar.",
  "list.registry.empty": "Nenhum item encontrado.",
  "list.categoryFixed": "Maiúsculas corrigidas em {category}: {count}",

  "sync.syncing": "Sincronizando…",
  "sync.synced": "Sincronizado {ago}",
  "sync.failed": "Falha na sincronização",
  "sync.failedSince": "Falha na sincronização · último sucesso {ago}",
  "sync.never": "Ainda não sincronizado",
  "sync.now": "Sincronizar agora",

  "lastSeen.activeNow": "Ativo agora",

  "item.add": "Adicionar item",
  "item.edit": "Editar item",
  "item.name": "Nome",
  "item.category": "Categoria",
  "item.stores": "Lojas",
  "item.addStore": "Adicionar loja",
  "item.quantity": "Quantidade",
  "item.price": "Preço",
  "item.currency": "Moeda",
  "item.note": "Observação",
  "item.statusLabel": "Status",
  "item.status.todo": "Para comprar",
  "item.status.checked": "Feito",
  "item.status.backlog": "Mais tarde",
  "item.status.backlogHint": "Ainda não está na lista",
  "item.addAnother": "Adicionar outro",
  "item.saveFailed": "Não foi possível salvar. Tente novamente.",

  "listProps.title": "Propriedades da lista",
  "listProps.name": "Nome",
  "listProps.type": "Tipo",
  "listProps.kind.checklist": "Os itens têm nome, categoria e observação.",
  "listProps.kind.shopping": "Os itens têm também lojas, quantidade e preço.",
  "listProps.makeShopping": "Transformar em lista de compras",
  "listProps.makeChecklist": "Transformar em checklist",
  "listProps.categories": "Categorias",
  "listProps.noCategories": "Nenhuma categoria ainda.",
  "listProps.moveUp": "Mover para cima",
  "listProps.moveDown": "Mover para baixo",
  "listProps.removeFromOrder": "Remover da ordem",
  "listProps.addToOrder": "Adicionar à ordem",
  "listProps.addCategory": "Adicionar categoria…",
  "listProps.clearChecked": "Redefinir concluídos",
  "listProps.notes": "Observações",
  "listProps.notesPlaceholder": "Código do portão, horários, qualquer coisa que valha lembrar…",
  "listProps.members": "Membros",
  "listProps.inviteByEmail": "Convidar por e-mail…",
  "listProps.inviteLink": "Link do convite",
  "listProps.clearCheckedHelp": "Devolve todos os itens concluídos para «Mais tarde».",
  "listProps.saveNotes": "Salvar observações",
  "listProps.pendingInvites": "Convites pendentes",
  "listProps.leaveList": "Sair da lista",
  "listProps.membersFailed": "Não foi possível carregar os membros.",
  "listProps.inviteFailed": "Não foi possível enviar o convite.",
  "listProps.leaveConfirm": "Sair desta lista? Você perderá o acesso a ela.",

  "action.cancel": "Cancelar",
  "action.save": "Salvar",
  "action.delete": "Excluir",
  "action.revoke": "Revogar",
  "action.undo": "Desfazer",
  "action.invite": "Convidar",
  "action.create": "Criar",
  "action.duplicate": "Duplicar",
  "action.copy": "Copiar",
  "action.copied": "Copiado!",
  "common.saved": "Salvo.",

  "nav.overview": "Visão geral",
  "nav.joinList": "Entrar em uma lista",
  "nav.logOut": "Sair",
  "nav.menu": "Menu",

  "error.generic": "Algo deu errado.",

  "overview.title": "Suas listas",
  "overview.newList": "Nova lista",
  "overview.name": "Nome",
  "overview.type": "Tipo",
  "overview.kind.checklist": "Apenas nomes, categorias e observações.",
  "overview.kind.shopping": "Adiciona lojas, quantidade e preço a cada item.",

  "list.notFound": "Lista não encontrada (ou você não tem mais acesso).",
  "list.backToOverview": "Voltar para a visão geral",
  "list.backToAllLists": "Voltar para todas as listas",
  "list.allItems": "Todos os itens",
  "list.search": "Buscar itens…",
  "list.empty": "Nada nesta lista ainda. Adicione um item para começar.",
  "list.checkedOff": "Item marcado.",

  "settings.language": "Idioma",
  "settings.title": "Configurações da conta",
  "settings.serverAdmin": "Administração do servidor",
  "settings.defaultCurrency": "Moeda padrão",
  "settings.initials": "Mostrar iniciais",
  "settings.changePassword": "Alterar senha",
  "settings.currentPassword": "Senha atual",
  "settings.newPassword": "Nova senha",
  "settings.passwordChanged": "Senha alterada.",
  "settings.changeEmail": "Alterar e-mail",
  "settings.newEmail": "Novo e-mail",
  "settings.emailChanged": "E-mail alterado.",
  "settings.sessions": "Sessões",
  "settings.deleteAccount": "Excluir conta",
  "settings.openServerAdmin": "Abrir administração do servidor",
  "settings.deleteMyAccount": "Excluir minha conta",
  "settings.password": "Senha",
  "settings.deleteConfirm": "Isso excluirá sua conta permanentemente. Tem certeza?",

  "admin.title": "Administração do servidor",
  "admin.registration": "Cadastro",
  "admin.allowNewAccounts": "Permitir novas contas",
  "admin.users": "Usuários",
  "admin.yourPassword": "Sua senha (necessária para redefinir ou excluir)",
  "admin.deleteUserTitle": "Excluir usuário?",
  "admin.resetPassword": "Redefinir senha",
  "admin.passwordRequired": "Digite sua senha para redefinir ou excluir um usuário.",
  "admin.loadFailed": "Não foi possível carregar os dados de administração.",
  "admin.updateFailed": "Não foi possível atualizar.",
  "admin.resetFailed": "Não foi possível redefinir a senha.",
  "admin.deleteFailed": "Não foi possível excluir o usuário.",
  "admin.sessionCount": "Sessões: {count}",
  "admin.isAdmin": "(admin)",
};
