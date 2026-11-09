/* Relay UI. Talks to the native host through window.RelayHost.post(json) / window.relayUI.receive(msg). */
(function () {
  'use strict';

  // ---------- helpers ----------

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
  const normUrl = (u) => String(u || '').split('#')[0].split('?')[0].replace(/\/+$/, '');
  const uid = () => Math.random().toString(36).slice(2, 10);
  const human = (n) => {
    n = Number(n) || 0;
    if (n < 1024) return n + ' Б';
    const u = ['КБ', 'МБ', 'ГБ'];
    let i = -1;
    do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
    return n.toFixed(n < 10 ? 1 : 0) + ' ' + u[i];
  };
  const words = (t) => String(t || '').trim().split(/\s+/).filter(Boolean).length;

  const ICON = {
    logo: '<path d="M5 9h12"/><path d="M13 5l4 4-4 4"/><path d="M19 15H7"/><path d="M11 11l-4 4 4 4"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h10"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
    globe: '<circle cx="12" cy="12" r="9.5"/><path d="M2.5 12h19M12 2.5a14.5 14.5 0 0 1 0 19M12 2.5a14.5 14.5 0 0 0 0 19"/>',
    send: '<path d="M12 19V5M5.5 11.5L12 5l6.5 6.5"/>',
    stop: '<rect x="7" y="7" width="10" height="10" rx="2.2" fill="currentColor" stroke="none"/>',
    folder: '<path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2.2h7.5A2.5 2.5 0 0 1 21 9.7v7.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/>',
    folderPlus: '<path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2.2h7.5A2.5 2.5 0 0 1 21 9.7v7.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/><path d="M12 10.5v6M9 13.5h6"/>',
    file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="M21 15l-5-5L5 21"/>',
    table: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M3 10h18M3 15h18M9 4v16"/>',
    edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
    terminal: '<path d="M4 17l6-5-6-5"/><path d="M12 19h8"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6"/>',
    move: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    copy: '<rect x="9" y="9" width="12" height="12" rx="2.5"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
    link: '<path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1 1"/><path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1-1"/>',
    grid: '<rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2"/>',
    phone: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    x: '<path d="M18 6L6 18M6 6l12 12"/>',
    chevron: '<path d="M6 9l6 6 6-6"/>',
    zap: '<path d="M13 2L4 14h8l-1 8 9-12h-8z"/>',
    chat: '<path d="M20 15a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z"/>',
    layers: '<path d="M12 2l10 5-10 5L2 7z"/><path d="M2 17l10 5 10-5M2 12l10 5 10-5"/>',
    target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/><path d="M12 1v3M12 20v3M1 12h3M20 12h3"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="M9 12l2 2 4-4"/>',
    server: '<rect x="3" y="3" width="18" height="8" rx="2.5"/><rect x="3" y="13" width="18" height="8" rx="2.5"/><path d="M7 7h.01M7 17h.01"/>',
    hash: '<path d="M4 9h16M4 15h16M10 3L8 21M16 3l-2 18"/>',
    sparkles: '<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 16l.7 1.8 1.8.7-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7z"/>',
    info: '<circle cx="12" cy="12" r="9.5"/><path d="M12 16v-4M12 8h.01"/>',
    alert: '<path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
    login: '<path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><path d="M10 17l5-5-5-5M15 12H3"/>',
    hand: '<path d="M18 11V6a2 2 0 0 0-4 0v5M14 10V4a2 2 0 0 0-4 0v6M10 10.5V6a2 2 0 0 0-4 0v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/>',
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    clip: '<path d="M21.4 11.1l-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5"/>',
    download: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M5 21h14"/>',
    share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4"/>',
    book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z"/><path d="M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5"/>',
    wand: '<path d="M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8L19 13M17.8 6.2L19 5M12.2 6.2L11 5"/><path d="M15 9l-12 12"/>',
    store: '<path d="M4 7h16l-1.5 12.5a2 2 0 0 1-2 1.5h-9a2 2 0 0 1-2-1.5z"/><path d="M9 7V5a3 3 0 0 1 6 0v2"/>',
    expand: '<path d="M7 10l5 5 5-5"/>'
  };
  const ic = (name, cls = '') => `<svg class="ic ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[name] || ''}</svg>`;

  const TOOLS = {
    list_dir: { icon: 'folder', name: 'Папка', label: (a) => `Показать ${code(a.path || '~')}` },
    read_file: { icon: 'file', name: 'Чтение', label: (a) => `Прочитать ${code(a.path)}` },
    search_files: { icon: 'search', name: 'Поиск', label: (a) => `Найти «${esc(a.pattern || a.name || a.query || '')}» в ${code(a.path || '~')}` },
    write_file: { icon: 'edit', name: 'Запись', label: (a) => `${a.append ? 'Дописать в' : 'Записать'} ${code(a.path)}` },
    mkdir: { icon: 'folderPlus', name: 'Новая папка', label: (a) => `Создать папку ${code(a.path)}` },
    move: { icon: 'move', name: 'Перемещение', label: (a) => `${code(a.from)} → ${code(a.to)}` },
    copy: { icon: 'copy', name: 'Копирование', label: (a) => `${code(a.from)} → ${code(a.to)}` },
    delete: { icon: 'trash', name: 'Удаление', label: (a) => `Удалить ${code(a.path)}${a.recursive ? ' со всем содержимым' : ''}` },
    shell: { icon: 'terminal', name: 'Shell', label: (a) => code((a.root ? '# ' : '$ ') + (a.command || a.cmd || '')) },
    download_file: { icon: 'download', name: 'Скачивание', label: (a) => `Скачать ${code(a.url)}${a.path ? ' в ' + code(a.path) : ''}` },
    attach_file: { icon: 'clip', name: 'Файл для ИИ', label: (a) => `Отправить в чат ${code(a.path)}` },
    open_file: { icon: 'eye', name: 'Открыть файл', label: (a) => `Открыть ${code(a.path)}` },
    open_url: { icon: 'link', name: 'Ссылка', label: (a) => `Открыть ${code(a.url)}` },
    launch_app: { icon: 'grid', name: 'Приложение', label: (a) => `Запустить ${code(a.package || a.app || a.name)}` },
    device_info: { icon: 'phone', name: 'Устройство', label: () => 'Сведения об устройстве' }
  };
  function code(s) { return `<code>${esc(s == null ? '' : s)}</code>`; }

  const STATE_TEXT = {
    idle: ['не подключено', ''],
    loading: ['загрузка…', 'warn'],
    ready: ['подключено', 'ok'],
    login: ['нужен вход', 'login'],
    needsSetup: ['нужна автонастройка', 'login'],
    needsAccessibility: ['включите спец. возможности', 'bad'],
    notInstalled: ['не установлено', 'bad']
  };
  const STATUS_TEXT = { pending: 'ждёт ответа', running: 'выполняется', done: 'готово', error: 'ошибка', denied: 'отклонено', stale: 'прервано' };
  const BUSY_TEXT = 'Агент работает — дождитесь ответа или нажмите «Стоп»';
  const PICK_LABELS = {
    input: ['Поле ввода', 'Куда Relay вставляет сообщение'],
    send: ['Кнопка «Отправить»', 'Пусто — нажатие Enter'],
    response: ['Ответ ИИ', 'Тапните любую видимую часть любого ответа — длинный ответ не нужно показывать целиком'],
    busy: ['Кнопка «Стоп»', 'Видна, пока ИИ печатает'],
    chatLink: ['Ссылка на чат', 'Элемент в списке чатов'],
    projectLink: ['Ссылка на проект', 'Если у сервиса есть проекты']
  };
  const CALIB_STEPS = [
    ['open', 'Открыть приложение'],
    ['input', 'Поле ввода'],
    ['send', 'Кнопка «Отправить»'],
    ['list', 'Лента сообщений'],
    ['chats', 'Чаты и проекты'],
    ['newchat', 'Новый чат'],
    ['test', 'Пробное сообщение']
  ];
  const FILE_SOURCES = { download: 'Скачано из ИИ', new: 'Новые файлы на устройстве', dump: 'Снимок экрана приложения', tool: 'Файл' };
  const PALETTE = ['#8B5CF6', '#22D3EE', '#F472B6', '#34D399', '#F59E0B', '#60A5FA', '#FB7185', '#A3E635'];
  const LONG_CHARS = 1200;
  const LONG_LINES = 24;

  // ---------- host channel ----------

  const host = (cmd, data) => {
    try { window.RelayHost.post(JSON.stringify(Object.assign({ cmd }, data || {}))); } catch (e) { console.error(e); }
  };

  // ---------- state ----------

  const S = {
    adapters: [], discovered: [], adapter: null, state: 'idle', url: '', chats: [], chatsAt: 0, key: null, msgs: [],
    settings: {}, device: {}, mcp: {}, apps: null, version: '', attachments: [], onboarded: true,
    busy: false, partial: '', lastTurnEnd: 0, agent: localStorage.getItem('relay.agent') !== '0',
    filter: '', sheet: null, editor: null, diag: null, wiz: null, calib: null,
    pinned: true, unseen: false, live: new Set()
  };

  const store = {
    load(key) {
      let list;
      try { list = JSON.parse(localStorage.getItem('relay.h.' + key) || '[]'); } catch (e) { list = []; }
      // Approval/running cards stay interactive only while the host is still waiting on that call.
      return list.map((m) => (m.role === 'tool' && (m.needsApproval || m.status === 'running' || m.status === 'pending') && !S.live.has(m.id)
        ? Object.assign({}, m, { needsApproval: false, status: 'stale' }) : m));
    },
    save(key, msgs) {
      if (!key) return;
      try { localStorage.setItem('relay.h.' + key, JSON.stringify(msgs.filter((m) => !m.ephemeral).slice(-200))); } catch (e) { /* quota */ }
    }
  };
  const persist = () => store.save(S.key, S.msgs);

  const isApp = (a) => !!a && a.kind === 'app';
  const appHome = (a) => 'app:' + (a.package || '');

  function keyFor(url) {
    const a = S.adapter;
    if (!a) return null;
    if (isApp(a)) return a.id + '|' + (url || appHome(a));
    const u = normUrl(url || a.url);
    if (!a.singleThread && (u === normUrl(a.newChatUrl || a.url) || u === normUrl(a.url))) return a.id + '|new';
    return a.id + '|' + u;
  }

  // ---------- avatars ----------

  function luminance(hex) {
    const m = /^#?([0-9a-f]{6})/i.exec(hex || '');
    if (!m) return 0;
    const n = parseInt(m[1], 16);
    return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
  }
  function avatar(a, size = '') {
    if (!a) return `<span class="avatar empty ${size}">${ic('logo', 'sm')}</span>`;
    const color = a.color || '#8B5CF6';
    const dark = luminance(color) > 0.7 ? 'dark-text' : '';
    return `<span class="avatar ${size} ${dark}" style="background:${esc(color)}">${esc(a.icon || (a.name || a.label || '?').slice(0, 2))}</span>`;
  }

  function fileIcon(f) {
    const m = (f.mime || '') + ' ' + (f.name || '');
    if (/image\//.test(m)) return 'image';
    if (/sheet|excel|csv|tsv/.test(m)) return 'table';
    return 'file';
  }

  // ---------- rendering: chrome ----------

  function renderStatic() {
    $('#brandLogo').innerHTML = ic('logo');
    $('#newChatBtn').innerHTML = ic('plus', 'sm') + '<span>Новый чат</span>';
    $('#refreshBtn').innerHTML = ic('refresh', 'sm');
    $('.search-ic').innerHTML = ic('search', 'sm');
    $('#settingsBtn').innerHTML = ic('settings', 'sm') + '<span>Настройки</span>';
    $('#menuBtn').innerHTML = ic('menu');
    $('#attachBtn').innerHTML = ic('clip');
  }

  function stateBadge() {
    const [text, cls] = STATE_TEXT[S.state] || STATE_TEXT.idle;
    return `<i class="dot ${cls}"></i>${esc(text)}`;
  }

  function renderConn() {
    const a = S.adapter;
    $('#connBtn').innerHTML = a
      ? `${avatar(a)}<span class="meta"><span class="name">${esc(a.name)}${isApp(a) ? ' · приложение' : ' · сайт'}</span><span class="state">${stateBadge()}</span></span><span class="chev">${ic('chevron', 'sm')}</span>`
      : `${avatar(null)}<span class="meta"><span class="name">Выберите ИИ</span><span class="state">приложение или сайт</span></span><span class="chev">${ic('chevron', 'sm')}</span>`;
    const d = S.device;
    const root = d.root === true ? '<span class="chip ok">' + ic('hash', 'sm') + 'ROOT</span>'
      : d.suPresent ? '<span class="chip warn">su</span>' : '<span class="chip">без root</span>';
    $('#badges').innerHTML = root + (S.mcp.running ? '<span class="chip accent">MCP</span>' : '');
  }

  function currentChatTitle() {
    if (!S.adapter) return 'Relay';
    const u = isApp(S.adapter) ? S.url : normUrl(S.url);
    const hit = S.chats.find((c) => (isApp(S.adapter) ? c.href : normUrl(c.href)) === u);
    if (hit && !(isApp(S.adapter) && hit.href === appHome(S.adapter))) return hit.title;
    if (isApp(S.adapter)) return /#new$/.test(S.url) ? 'Новый чат' : S.adapter.name;
    return (S.key || '').endsWith('|new') ? 'Новый чат' : S.adapter.name;
  }

  function renderTop() {
    const a = S.adapter;
    $('#topAvatar').outerHTML = `<span id="topAvatar">${avatar(a, 'sm')}</span>`;
    $('#topTitle').textContent = currentChatTitle();
    $('#topSub').innerHTML = a ? `${stateBadge()}<span>· ${esc(a.name)}${isApp(a) ? ' (приложение)' : ''}</span>` : '<i class="dot"></i>не подключено';
    const site = $('#siteBtn');
    site.innerHTML = ic(isApp(a) ? 'grid' : 'globe', 'sm');
    site.title = isApp(a) ? 'Открыть приложение' : 'Открыть сайт';
    site.hidden = !a;
    renderBanner();
  }

  function renderBanner() {
    const b = $('#banner');
    const a = S.adapter;
    let html = '';
    b.className = 'banner';
    if (!window.RelayHost) {
      b.className = 'banner bad';
      html = `${ic('alert')}<span class="txt">Нет связи с приложением Relay. Закройте его и откройте заново.</span>`;
    } else if (a && S.state === 'login') {
      html = `${ic('login')}<span class="txt"><b>Войдите в ${esc(a.name)}</b> один раз — своим обычным аккаунтом. Relay запомнит сессию.</span><button class="btn sm" data-act="site">Открыть</button>`;
    } else if (a && S.state === 'needsAccessibility') {
      b.className = 'banner bad';
      html = `${ic('hand')}<span class="txt">Чтобы управлять приложением ${esc(a.name)}, включите службу Relay в спец. возможностях.</span><button class="btn sm" data-act="a11y">Включить</button>`;
    } else if (a && S.state === 'notInstalled') {
      b.className = 'banner bad';
      html = `${ic('store')}<span class="txt"><b>${esc(a.name)} не установлено</b> на этом телефоне.</span><button class="btn sm" data-install="${esc(a.package)}">Установить</button>`;
    } else if (a && S.state === 'needsSetup') {
      b.className = 'banner setup';
      html = `${ic('wand')}<span class="txt"><b>Автонастройка ${esc(a.name)}</b>: Relay сам найдёт поле ввода, кнопку «Отправить» и чаты.</span><button class="btn sm primary" data-wizard="${esc(a.id)}">Настроить</button>`;
    }
    b.innerHTML = html;
    b.hidden = !html;
  }

  function renderChats() {
    const list = $('#chatList');
    const a = S.adapter;
    if (!a) {
      list.innerHTML = '<div class="list-empty">Подключите ИИ, и здесь появятся его чаты и проекты.</div>';
      return;
    }
    const f = S.filter.trim().toLowerCase();
    const items = S.chats.filter((c) => !f || (c.title || '').toLowerCase().includes(f));
    const cur = isApp(a) ? S.url : normUrl(S.url);
    const row = (c) => `<button class="chat-item ${(isApp(a) ? c.href : normUrl(c.href)) === cur ? 'active' : ''}" data-href="${esc(c.href)}">${ic(c.kind === 'project' ? 'layers' : 'chat', 'sm')}<span>${esc(c.title || 'Без названия')}</span></button>`;
    const projects = items.filter((c) => c.kind === 'project');
    const chats = items.filter((c) => c.kind !== 'project');
    let html = '';
    if (projects.length) html += '<div class="group-title">Проекты</div>' + projects.map(row).join('');
    if (chats.length) html += '<div class="group-title">Чаты</div>' + chats.map(row).join('');
    if (isApp(a)) {
      const only = S.chats.length <= 1;
      html += `<div class="list-empty">${only ? 'Список чатов приложения ещё не прочитан. ' : ''}${S.chatsAt ? 'Обновлено ' + ago(S.chatsAt) + '. ' : ''}<button class="btn sm" data-act="refresh-app-chats">${ic('refresh', 'sm')}${only ? 'Прочитать чаты' : 'Обновить'}</button></div>`;
    } else if (!html) {
      html = S.state === 'login' || S.state === 'loading'
        ? `<div class="list-empty">${S.state === 'loading' ? 'Загружаю сайт…' : 'Чаты появятся после входа в аккаунт.'}<br><button class="btn sm" data-act="site">${ic('globe', 'sm')}Открыть сайт</button></div>`
        : `<div class="list-empty">${f ? 'Ничего не найдено.' : 'Список чатов не найден на странице. Попробуйте «Версия для ПК» или укажите селектор чата в настройках сервиса.'}</div>`;
    }
    list.innerHTML = html;
  }

  function ago(ts) {
    const s = Math.max(1, Math.round((Date.now() - ts) / 1000));
    if (s < 60) return 'только что';
    if (s < 3600) return Math.round(s / 60) + ' мин назад';
    if (s < 86400) return Math.round(s / 3600) + ' ч назад';
    return Math.round(s / 86400) + ' дн назад';
  }

  function renderAttachments() {
    const row = $('#attRow');
    row.hidden = !S.attachments.length;
    row.innerHTML = S.attachments.map((f) => `<span class="att-chip">${ic(fileIcon(f), 'sm')}<span class="att-name">${esc(f.name)}</span><small>${human(f.size)}</small><button class="att-x" data-unattach="${esc(f.id)}" aria-label="Убрать">${ic('x', 'sm')}</button></span>`).join('');
  }

  function renderComposer() {
    const chip = $('#agentChip');
    chip.className = 'mode-chip' + (S.agent ? ' on' : '');
    chip.innerHTML = S.agent ? ic('zap') + 'Агент' : ic('chat') + 'Чат';
    $('#composerHint').textContent = S.agent ? 'Файлы · shell · приложения' : 'Только переписка';
    const btn = $('#sendBtn');
    const has = $('#input').value.trim().length > 0 || S.attachments.length > 0;
    btn.className = 'send-btn' + (S.busy ? ' stop' : has ? '' : ' idle');
    btn.innerHTML = ic(S.busy ? 'stop' : 'send');
    btn.setAttribute('aria-label', S.busy ? 'Остановить' : 'Отправить');
    $('#input').placeholder = S.adapter ? `Задача для ${S.adapter.name}…` : 'Сначала выберите ИИ…';
    renderAttachments();
  }

  function renderAll() {
    renderConn();
    renderTop();
    renderChats();
    renderComposer();
    renderThread();
  }

  // ---------- rendering: thread ----------

  function md(text) {
    const parts = String(text || '').split('```');
    return parts.map((p, i) => {
      if (i % 2) {
        const nl = p.indexOf('\n');
        const lang = nl > -1 ? p.slice(0, nl).trim() : '';
        const body = (nl > -1 ? p.slice(nl + 1) : p).replace(/\n$/, '');
        return `<pre><div class="code-h">${esc(lang || 'code')}</div><code>${esc(body)}</code></pre>`;
      }
      const html = esc(p)
        .replace(/`([^`\n]+)`/g, '<code class="inl">$1</code>')
        .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
        .replace(/(https?:\/\/[^\s<]+[^\s<.,;:!?)])/g, '<a href="$1" target="_blank" rel="noopener">$1</a>');
      return html.split(/\n{2,}/).map((x) => x.trim()).filter(Boolean).map((x) => `<p>${x.replace(/\n/g, '<br>')}</p>`).join('');
    }).join('');
  }

  const isLong = (t) => String(t || '').length > LONG_CHARS || String(t || '').split('\n').length > LONG_LINES;

  function msgNode(m) {
    switch (m.role) {
      case 'user': {
        const files = (m.files || []).length
          ? `<div class="msg-files">${m.files.map((f) => `<span class="att-chip sm">${ic(fileIcon(f), 'sm')}<span class="att-name">${esc(f.name)}</span></span>`).join('')}</div>` : '';
        return el(`<div class="msg user" data-id="${esc(m.id)}"><div class="user-col">${files}<div class="bubble">${esc(m.text)}</div></div></div>`);
      }
      case 'assistant': {
        const long = isLong(m.text);
        const collapsed = long && !m.expanded;
        return el(`<div class="msg ai ${collapsed ? 'collapsed' : ''}" data-id="${esc(m.id)}">${avatar(S.adapter, 'sm')}<div class="body">
          <div class="who">${esc(m.who || (S.adapter && S.adapter.name) || 'ИИ')}</div>
          <div class="bubble md">${md(m.text)}</div>
          ${long ? `<button class="expand" data-expand="${esc(m.id)}">${ic('expand', 'sm')}${collapsed ? `Показать полностью · ${words(m.text)} слов` : 'Свернуть'}</button>` : ''}
          <div class="msg-actions">
            <button data-copymsg="${esc(m.id)}">${ic('copy', 'sm')}Копировать</button>
            ${long ? `<button data-read="${esc(m.id)}">${ic('book', 'sm')}Читать</button>` : ''}
          </div></div></div>`);
      }
      case 'tool':
        return toolNode(m);
      case 'files':
        return filesNode(m);
      case 'error':
        return el(`<div class="note err" data-id="${esc(m.id)}">${ic('alert', 'sm')}<span>${esc(m.text)}</span></div>`);
      default:
        return el(`<div class="note" data-id="${esc(m.id)}">${ic('info', 'sm')}<span>${esc(m.text)}</span></div>`);
    }
  }

  function filesNode(m) {
    const rows = (m.items || []).map((f) => {
      const dir = String(f.path || '').replace(/\/[^/]*$/, '').replace(/^\/storage\/emulated\/0/, '~');
      return `<div class="file-row"><span class="file-ic">${ic(fileIcon(f))}</span><span class="fr-t"><b>${esc(f.name)}</b><small>${human(f.size)} · ${esc(dir)}</small></span>
        <button class="icon-btn" data-open="${esc(f.path)}" aria-label="Открыть">${ic('eye', 'sm')}</button>
        <button class="icon-btn" data-share="${esc(f.path)}" aria-label="Поделиться">${ic('share', 'sm')}</button></div>`;
    }).join('');
    return el(`<div class="files-card" data-id="${esc(m.id)}"><div class="files-h">${ic(m.source === 'download' ? 'download' : 'folder', 'sm')}${esc(FILE_SOURCES[m.source] || 'Файлы')}</div>${rows}</div>`);
  }

  function toolNode(m) {
    const meta = Object.prototype.hasOwnProperty.call(TOOLS, m.tool) ? TOOLS[m.tool]
      : { icon: 'sparkles', name: m.tool, label: () => code(JSON.stringify(m.args)) };
    const args = m.args || {};
    const risk = m.risk || 'WRITE';
    const src = m.source === 'mcp' ? '<span class="chip accent">MCP</span>' : '';
    let tail = '';
    if (m.needsApproval) {
      const allowAlways = risk !== 'DANGER';
      tail = `<div class="ask-q">${ic(risk === 'DANGER' ? 'alert' : 'shield', 'sm')}${risk === 'DANGER' ? 'Опасное действие — проверьте внимательно' : 'Разрешить это действие?'}</div>
        <div class="ask-args">${esc(JSON.stringify(args, null, 2))}</div>
        <div class="ask-actions">
          <button class="btn sm" data-approve="deny">Отклонить</button>
          ${allowAlways ? `<button class="btn sm" data-approve="always">Всегда «${esc(m.tool)}»</button>` : ''}
          <button class="btn sm primary" data-approve="once">${ic('check', 'sm')}Разрешить</button>
        </div>`;
    } else if (m.result && m.result.output) {
      tail = `<details ${m.status === 'error' ? 'open' : ''}><summary>${ic('chevron', 'sm')}Результат</summary><pre class="out">${esc(m.result.output)}</pre></details>`;
    }
    const st = m.needsApproval ? 'pending' : m.status;
    const spinner = st === 'running' ? '<i class="spin"></i>' : '';
    return el(`<div class="tool risk-${esc(risk)} ${m.needsApproval ? 'ask' : ''}" data-id="${esc(m.id)}">
      <div class="tool-head">
        <span class="tool-ic">${ic(meta.icon)}</span>
        <span class="tool-title"><span class="tool-name">${esc(meta.name)} ${src}</span><span class="tool-label">${meta.label(args)}</span></span>
        <span class="status ${esc(st)}">${spinner}${esc(STATUS_TEXT[st] || st)}</span>
      </div>${tail}</div>`);
  }

  function heroNode() {
    const a = S.adapter;
    if (!a) {
      return el(`<div class="hero">
        <div class="hero-logo">${ic('logo')}</div>
        <h1>Claude Code для любых ИИ</h1>
        <p>Пишите задачу здесь — она уходит в чат выбранного ИИ-приложения, а действия выполняются прямо на этом телефоне.</p>
        <button class="btn primary" data-act="wizard">${ic('wand', 'sm')}Настроить автоматически</button>
        <div class="steps">
          <div class="step"><span class="num">1</span><span><b>Relay проверит телефон</b> — root, доступ к файлам, спец. возможности.</span></div>
          <div class="step"><span class="num">2</span><span><b>Найдёт ИИ-приложения</b> — ChatGPT, Claude, Notion, Hark Pro, ГигаЧат, Алиса…</span></div>
          <div class="step"><span class="num">3</span><span><b>Настроит выбранное сам</b> — найдёт поле ввода, кнопку «Отправить» и ваши чаты.</span></div>
        </div>
      </div>`);
    }
    const tips = [
      ['folderPlus', 'Создай папку «привет» в Загрузках'],
      ['folder', 'Покажи, что лежит в Загрузках'],
      ['table', 'Сделай таблицу расходов и сохрани её в Документы'],
      ['phone', 'Сколько свободного места на телефоне?']
    ];
    return el(`<div class="hero">
      <div class="hero-logo">${avatar(a, 'lg')}</div>
      <h1>Что сделать?</h1>
      <p>Сообщение уйдёт в ${isApp(a) ? 'приложение' : 'чат'} <b>${esc(a.name)}</b>. ${S.agent ? 'Режим агента: ИИ может работать с файлами и приложениями — каждое действие вы видите и подтверждаете.' : 'Режим чата: только переписка.'}</p>
      <div class="suggest">${tips.map(([i, t]) => `<button data-suggest="${esc(t)}">${ic(i, 'sm')}<span>${esc(t)}</span></button>`).join('')}</div>
    </div>`);
  }

  function liveNode() {
    if (!S.busy) return null;
    const name = S.adapter ? S.adapter.name : 'ИИ';
    if (S.partial) {
      return el(`<div class="msg ai live">${avatar(S.adapter, 'sm')}<div class="body"><div class="who">${esc(name)} печатает…</div><div class="bubble md caret">${md(S.partial)}</div></div></div>`);
    }
    if (S.msgs.some((m) => m.role === 'tool' && (m.needsApproval || m.status === 'running'))) return null;
    const where = isApp(S.adapter) ? `<div class="live-note">${ic('grid', 'sm')}Relay работает в приложении ${esc(name)} — не трогайте экран</div>` : '';
    return el(`<div class="msg ai live">${avatar(S.adapter, 'sm')}<div class="body"><div class="who">${esc(name)}</div><div class="typing"><i></i><i></i><i></i></div>${where}</div></div>`);
  }

  function renderThread() {
    const inner = $('#threadInner');
    inner.innerHTML = '';
    S.unseen = false;
    if (!S.msgs.length && !S.busy) {
      // The empty-chat hero reads from the top; it must not be glued to the bottom on resizes.
      S.pinned = false;
      inner.appendChild(heroNode());
      $('#thread').scrollTop = 0;
      renderJump();
      return;
    }
    S.pinned = true;
    S.msgs.forEach((m) => inner.appendChild(msgNode(m)));
    renderLive(false);
    scrollDown(true);
  }

  function renderLive(scroll = true) {
    const inner = $('#threadInner');
    const old = $('.live', inner);
    if (old) old.remove();
    const node = liveNode();
    if (node) inner.appendChild(node);
    if (scroll) scrollDown();
  }

  // ---------- thread scrolling ----------
  // The thread sticks to the newest message unless the user scrolled up. Keyboard, rotation and streaming
  // resizes re-pin it; otherwise the "↓" button offers the way back.

  const NEAR_BOTTOM = 72;
  let userScrollAt = 0;

  function scrollDown(force) {
    const t = $('#thread');
    if (force) S.pinned = true;
    if (S.pinned) {
      t.scrollTop = t.scrollHeight;
      S.unseen = false;
    }
    renderJump();
  }

  function renderJump() {
    const b = $('#jumpBtn');
    if (!b) return;
    b.hidden = S.pinned || (!S.msgs.length && !S.busy);
    b.classList.toggle('new', !!S.unseen);
  }

  function initScroll() {
    const t = $('#thread');
    const inner = $('#threadInner');
    $('#jumpBtn').innerHTML = ic('chevron');
    const mark = () => { userScrollAt = Date.now(); };
    ['wheel', 'touchstart', 'touchmove', 'pointerdown', 'keydown'].forEach((ev) => t.addEventListener(ev, mark, { passive: true }));
    t.addEventListener('scroll', () => {
      const near = t.scrollHeight - t.scrollTop - t.clientHeight <= NEAR_BOTTOM;
      // Layout-driven scroll events (keyboard, rotation) must not unpin; only a user gesture can.
      if (near) { S.pinned = true; S.unseen = false; } else if (Date.now() - userScrollAt < 1500) S.pinned = false;
      renderJump();
    }, { passive: true });
    if (!window.ResizeObserver) return;
    let lastHeight = inner.offsetHeight;
    const ro = new ResizeObserver(() => {
      const h = inner.offsetHeight;
      if (S.pinned) t.scrollTop = t.scrollHeight;
      else if (h > lastHeight) S.unseen = true;
      lastHeight = h;
      renderJump();
    });
    ro.observe(t);
    ro.observe(inner);
  }

  function pushMsg(m) {
    m.id = m.id || uid();
    m.ts = Date.now();
    if (m.role === 'user' || !S.msgs.length) S.pinned = true;
    const hero = $('.hero', $('#threadInner'));
    if (hero) hero.remove();
    S.msgs.push(m);
    const inner = $('#threadInner');
    const live = $('.live', inner);
    inner.insertBefore(msgNode(m), live || null);
    persist();
    renderLive();
  }

  function replaceMsg(m) {
    const node = $(`[data-id="${CSS.escape(m.id)}"]`, $('#threadInner'));
    if (node) node.replaceWith(msgNode(m));
    persist();
  }

  function upsertTool(call, patch) {
    let m = S.msgs.find((x) => x.role === 'tool' && x.id === call.id);
    if (!m) {
      m = { id: call.id, role: 'tool', tool: call.tool, args: call.args || {} };
      Object.assign(m, patch);
      pushMsg(m);
      return;
    }
    Object.assign(m, patch);
    replaceMsg(m);
    renderLive();
  }

  // ---------- actions ----------

  function selectAdapter(id) {
    const a = S.adapters.find((x) => x.id === id);
    if (!a) return;
    // Replies of a running turn would otherwise land in (and be saved to) the newly opened chat.
    if (S.busy && S.adapter && S.adapter.id !== id) { toast(BUSY_TEXT); return; }
    S.adapter = a;
    S.state = 'loading';
    S.chats = [];
    S.chatsAt = 0;
    S.url = isApp(a) ? appHome(a) : a.url;
    S.key = keyFor(S.url);
    S.msgs = store.load(S.key);
    S.partial = '';
    renderAll();
    host('selectAdapter', { id });
  }

  function handleUrl(url) {
    if (!url || !S.adapter) return;
    const prevKey = S.key;
    S.url = url;
    const newKey = keyFor(url);
    if (newKey !== prevKey) {
      const migrating = prevKey && prevKey.endsWith('|new') && (S.busy || Date.now() - S.lastTurnEnd < 15000) && S.msgs.length;
      if (migrating) {
        store.save(newKey, S.msgs);
        store.save(prevKey, []);
        S.key = newKey;
      } else {
        S.key = newKey;
        S.msgs = store.load(newKey);
        renderThread();
      }
    }
    renderChats();
    renderTop();
  }

  function submit() {
    if (S.busy) { host('stop'); return; }
    const input = $('#input');
    let text = input.value.trim();
    if (!text && S.attachments.length) text = 'Посмотри прикреплённые файлы.';
    if (!text) return;
    if (!S.adapter) { openWizardOrServices(); toast('Сначала выберите, куда отправлять задачу'); return; }
    const files = S.attachments.map((f) => ({ name: f.name, size: f.size, mime: f.mime }));
    const ids = S.attachments.map((f) => f.id);
    pushMsg({ role: 'user', text, files });
    input.value = '';
    S.attachments = [];
    autosize();
    host('send', { text, agent: S.agent, attachments: ids });
    renderComposer();
  }

  function autosize() {
    const t = $('#input');
    t.style.height = 'auto';
    t.style.height = Math.min(200, t.scrollHeight) + 'px';
  }

  function openDrawer(open) { $('#app').classList.toggle('drawer-open', open); }

  function toast(text) {
    const box = $('#toasts');
    const t = el(`<div class="toast">${esc(text)}</div>`);
    box.appendChild(t);
    setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 320); }, 2600);
  }

  // ---------- sheets ----------

  function openSheet(name, html, cls = '') {
    const layer = $('#sheetLayer');
    S.sheet = name;
    layer.innerHTML = `<div class="sheet-backdrop" data-act="close-sheet"></div><div class="sheet ${cls}" role="dialog" aria-modal="true"><div class="handle"></div>${html}</div>`;
    layer.hidden = false;
  }

  function refreshSheet(html) {
    const sheet = $('#sheetLayer .sheet');
    if (!sheet) return;
    const top = sheet.scrollTop;
    sheet.innerHTML = '<div class="handle"></div>' + html;
    sheet.scrollTop = top;
  }

  function closeSheet() {
    S.sheet = null;
    S.editor = null;
    S.diag = null;
    const layer = $('#sheetLayer');
    layer.hidden = true;
    layer.innerHTML = '';
  }

  const head = (title, sub, closable = true) => `<div class="sheet-head"><div class="txt"><h2>${esc(title)}</h2>${sub ? `<p class="sub">${sub}</p>` : ''}</div>${closable ? `<button class="icon-btn close" data-act="close-sheet" aria-label="Закрыть">${ic('x')}</button>` : ''}</div>`;

  function hostOf(u) { try { return new URL(u).host.replace(/^www\./, ''); } catch (e) { return ''; } }

  function appSub(a) {
    if (a.installed === false) return 'не установлено';
    return [a.version ? 'v' + a.version.split(' ')[0] : 'установлено', a.calibrated ? 'настроено' : ''].filter(Boolean).join(' · ');
  }

  function tile(a) {
    const active = S.adapter && S.adapter.id === a.id ? 'active' : '';
    const off = isApp(a) && a.installed === false ? 'off' : '';
    const sub = isApp(a) ? appSub(a) : (a.domain || hostOf(a.url));
    const edit = !isApp(a) ? `<button class="tile-edit" data-edit="${esc(a.id)}" aria-label="Настроить">${ic('edit')}</button>` : (a.custom ? `<button class="tile-edit" data-del="${esc(a.id)}" aria-label="Удалить">${ic('x')}</button>` : '');
    const ok = isApp(a) && a.installed ? `<span class="tile-ok">${ic('check', 'sm')}</span>` : '';
    return `<div class="tile ${active} ${off}" role="button" tabindex="0" data-pick="${esc(a.id)}">${edit}${ok}${avatar(a, 'lg')}<span class="tile-name">${esc(a.name)}</span><span class="tile-sub">${esc(sub)}</span></div>`;
  }

  function installRow(a) {
    return `<div class="app-row static">${avatar(a, 'sm')}<span class="ar-t"><b>${esc(a.name)}</b><small>${esc(a.package)}</small></span><button class="btn sm" data-install="${esc(a.package)}">${ic('store', 'sm')}Установить</button></div>`;
  }

  function discoveredRow(d) {
    return `<button class="app-row" data-app="${esc(d.package)}" data-label="${esc(d.label)}">${avatar({ name: d.label, icon: d.label.slice(0, 2), color: PALETTE[d.package.length % PALETTE.length] }, 'sm')}<span class="ar-t"><b>${esc(d.label)}</b><small>${esc(d.package)}</small></span><span class="chip ok">найдено</span></button>`;
  }

  function servicesHtml() {
    const apps = S.adapters.filter(isApp);
    const installed = apps.filter((a) => a.installed);
    const missing = apps.filter((a) => !a.installed);
    const web = S.adapters.filter((a) => !isApp(a));
    return head('Подключить ИИ', 'Relay проверил, какие ИИ-приложения стоят на телефоне. Без API-ключей и без взлома: Relay печатает в чат за вас.') +
      `<div class="group-title">${ic('phone', 'sm')}Установлено на телефоне · ${installed.length}</div>
       ${installed.length ? `<div class="tiles">${installed.map(tile).join('')}</div>` : '<div class="list-empty">Известных ИИ-приложений не найдено.</div>'}
       ${S.discovered.length ? `<div class="group-title">Похожие на ИИ</div>${S.discovered.map(discoveredRow).join('')}` : ''}
       <div class="sheet-actions" style="margin-top:12px"><button class="btn sm" data-act="add-app">${ic('plus', 'sm')}Другое</button><button class="btn sm" data-act="rescan">${ic('refresh', 'sm')}Обновить</button></div>
       ${missing.length ? `<details class="more"><summary>${ic('chevron', 'sm')}Не установлены · ${missing.length}</summary>${missing.map(installRow).join('')}</details>` : ''}
       <div class="group-title">${ic('globe', 'sm')}Сайты</div><div class="tiles">${web.map(tile).join('')}
        <div class="tile add" role="button" tabindex="0" data-act="add-site"><span class="avatar lg">${ic('plus')}</span><span class="tile-name">Свой сайт</span><span class="tile-sub">любой чат с ИИ</span></div></div>`;
  }
  function openServices() { openDrawer(false); openSheet('services', servicesHtml()); host('scanApps'); }
  function openWizardOrServices() { if (!S.onboarded) openWizard(0); else openServices(); }

  function addSiteHtml() {
    return head('Свой сайт', 'Любой веб-чат с ИИ: корпоративный бот, Open WebUI, локальная модель…') +
      `<div class="field"><label>Название</label><input class="text-in" id="fName" placeholder="Например, Мой ИИ"></div>
       <div class="field"><label>Адрес</label><input class="text-in" id="fUrl" inputmode="url" placeholder="https://…"></div>
       <p class="hint">Relay сам попробует найти поле ввода и ответы. Если не выйдет — откройте настройки сервиса (✎) и просто тапните нужные элементы на сайте.</p>
       <div class="sheet-actions"><button class="btn" data-act="services">Назад</button><button class="btn primary" data-act="create-site">Добавить</button></div>`;
  }

  function appsHtml() {
    const items = S.apps;
    const f = (S.appFilter || '').toLowerCase();
    const list = items == null ? '<div class="list-empty">Загружаю список приложений…</div>'
      : items.filter((x) => !f || x.label.toLowerCase().includes(f) || x.package.toLowerCase().includes(f)).slice(0, 200).map((x) =>
        `<button class="app-row" data-app="${esc(x.package)}" data-label="${esc(x.label)}">${avatar({ name: x.label, icon: x.label.slice(0, 2), color: PALETTE[x.package.length % PALETTE.length] }, 'sm')}<span class="ar-t"><b>${esc(x.label)}</b><small>${esc(x.package)}</small></span></button>`).join('');
    return head('Приложение', 'Выберите приложение с ИИ, которым Relay будет управлять.') +
      `<label class="search"><span class="search-ic">${ic('search', 'sm')}</span><input id="appSearch" placeholder="Поиск приложений" value="${esc(S.appFilter || '')}"></label>
       <div id="appList" style="margin-top:8px">${list}</div>`;
  }

  // ----- onboarding wizard -----

  function openWizard(step, appId) {
    openDrawer(false);
    const first = S.adapters.find((a) => isApp(a) && a.installed);
    S.wiz = { step: step || 0, app: appId || (S.adapter && isApp(S.adapter) ? S.adapter.id : first && first.id) || null, test: true };
    S.calib = null;
    openSheet('wizard', wizardHtml(), 'tall');
    host('scanApps');
  }

  function wizardHtml() {
    const w = S.wiz;
    const names = ['Телефон', 'Приложения', 'Автонастройка'];
    const dots = `<div class="wiz-steps">${names.map((t, i) => `<div class="wiz-step ${i === w.step ? 'on' : ''} ${i < w.step ? 'done' : ''}"><span>${i < w.step ? ic('check', 'sm') : i + 1}</span><b>${t}</b></div>`).join('')}</div>`;
    const body = w.step === 0 ? wizDevice() : w.step === 1 ? wizApps() : wizCalib();
    return head('Настройка Relay', 'Relay сам проверит телефон и настроит выбранное ИИ-приложение.') + dots + body;
  }

  function statusChip(ok, yes, no, warn) {
    return ok ? `<span class="chip ok">${ic('check', 'sm')}${yes}</span>` : `<span class="chip ${warn ? 'warn' : 'bad'}">${no}</span>`;
  }

  function wizDevice() {
    const d = S.device;
    const rootOk = d.root === true;
    const allOk = d.storage && d.accessibility;
    return `<div class="card">
        <div class="row">${ic('hash', 'lead')}<span class="rt"><b>Root ${d.root === true ? statusChip(true, 'есть') : d.suPresent ? '<span class="chip warn">su найден</span>' : '<span class="chip">нет</span>'}</b>
          <small>${d.suPresent ? 'С root Relay выдаст все разрешения сам — одной кнопкой.' : 'Без root тоже работает: разрешения выдаются кнопками ниже.'}</small></span>
          ${d.suPresent && !allOk ? `<button class="btn sm primary" data-act="auto-setup">${ic('zap', 'sm')}Всё через root</button>` : d.suPresent && !rootOk ? '<button class="btn sm" data-act="check-root">Проверить</button>' : ''}</div>
        <div class="row">${ic('folder', 'lead')}<span class="rt"><b>Доступ к файлам ${statusChip(d.storage, 'есть', 'нет', true)}</b><small>Чтобы ИИ работал с «Загрузками», «Документами» и т.д.</small></span>${d.storage ? '' : '<button class="btn sm" data-perm="storage">Выдать</button>'}</div>
        <div class="row">${ic('hand', 'lead')}<span class="rt"><b>Спец. возможности ${statusChip(d.accessibility, 'вкл', 'выкл', true)}</b><small>Нужны, чтобы Relay печатал в ИИ-приложениях и нажимал «Отправить».</small></span>${d.accessibility ? '' : '<button class="btn sm" data-perm="accessibility">Включить</button>'}</div>
        ${!d.accessibility && d.sdk >= 33 && !rootOk ? `<div class="row">${ic('info', 'lead')}<span class="rt"><b>Переключатель серый?</b><small>Android 13+ блокирует службы у приложений не из Play: «О приложении» → ⋮ → «Разрешить ограниченные настройки», затем включите снова.</small></span><button class="btn sm" data-perm="appinfo">Открыть</button></div>` : ''}
      </div>
      <div class="sheet-actions"><button class="btn ghost" data-act="wiz-skip">Пропустить</button><button class="btn primary" data-act="wiz-next">${allOk ? 'Далее' : 'Далее без этого'}</button></div>`;
  }

  function wizApps() {
    const apps = S.adapters.filter(isApp);
    const installed = apps.filter((a) => a.installed);
    const missing = apps.filter((a) => !a.installed);
    const pick = (a) => `<button class="app-row ${S.wiz.app === a.id ? 'selected' : ''}" data-wizapp="${esc(a.id)}">${avatar(a, 'sm')}<span class="ar-t"><b>${esc(a.name)}</b><small>${esc(appSub(a))}</small></span><span class="radio"></span></button>`;
    return `${installed.length ? `<div class="group-title">${ic('phone', 'sm')}Найдено на телефоне · ${installed.length}</div>${installed.map(pick).join('')}` : `<div class="list-empty">ИИ-приложений из списка на телефоне нет. Установите любое или используйте сайт.</div>`}
      ${S.discovered.length ? `<div class="group-title">Похожие на ИИ</div>${S.discovered.map(discoveredRow).join('')}` : ''}
      ${missing.length ? `<details class="more"><summary>${ic('chevron', 'sm')}Не установлены · ${missing.length}</summary>${missing.map(installRow).join('')}</details>` : ''}
      <div class="sheet-actions"><button class="btn" data-act="wiz-back">Назад</button><button class="btn primary" data-act="wiz-next" ${S.wiz.app ? '' : 'disabled'}>Далее</button></div>
      <p class="hint">Нужен сайт, а не приложение? <a href="#" data-act="wiz-sites">Подключить сайт</a></p>`;
  }

  function wizCalib() {
    const a = S.adapters.find((x) => x.id === S.wiz.app);
    if (!a) return '<div class="list-empty">Приложение не выбрано.</div><div class="sheet-actions"><button class="btn" data-act="wiz-back">Назад</button></div>';
    const c = S.calib;
    const steps = CALIB_STEPS.filter(([k]) => k !== 'test' || S.wiz.test).map(([k, t]) => {
      const s = c && c.steps[k];
      const st = s ? s.status : 'idle';
      const iconHtml = st === 'done' ? ic('check', 'sm') : st === 'error' ? ic('x', 'sm') : st === 'warn' ? '!' : st === 'running' ? '<i class="spin"></i>' : '';
      return `<div class="cal-step ${st}"><span class="cal-ic">${iconHtml}</span><span class="rt"><b>${esc(t)}</b>${s && s.detail ? `<small>${esc(s.detail)}</small>` : ''}</span></div>`;
    }).join('');
    const running = c && c.running;
    const done = c && c.done;
    const ready = a.installed && S.device.accessibility;
    return `<div class="cal-head">${avatar(a, 'lg')}<span class="rt"><b>${esc(a.name)}</b><small>${esc(appSub(a))}</small></span></div>
      ${!a.installed ? `<div class="banner bad">${ic('store')}<span class="txt">Сначала установите ${esc(a.name)}.</span><button class="btn sm" data-install="${esc(a.package)}">Установить</button></div>` : ''}
      ${a.installed && !S.device.accessibility ? `<div class="banner bad">${ic('hand')}<span class="txt">Включите службу Relay в спец. возможностях.</span><button class="btn sm" data-perm="accessibility">Включить</button></div>` : ''}
      <p class="hint" style="margin-top:12px">Relay откроет ${esc(a.name)} и сам найдёт поле ввода, кнопку «Отправить», ленту сообщений и ваши чаты. Займёт около 20 секунд — не трогайте экран.</p>
      <label class="check-row"><input type="checkbox" data-act="wiz-test" ${S.wiz.test ? 'checked' : ''} ${running ? 'disabled' : ''}><span>Отправить пробное сообщение и проверить чтение ответа <small>(появится в чате приложения)</small></span></label>
      <div class="cal-list">${steps}</div>
      ${done ? `<div class="cal-result ${c.ok ? 'ok' : 'bad'}">${ic(c.ok ? 'check' : 'alert', 'sm')}${esc(c.message || (c.ok ? 'Готово' : 'Есть проблемы'))}</div>` : ''}
      <div class="sheet-actions">
        ${running ? '<button class="btn danger" data-act="stop">Остановить</button>' : `<button class="btn" data-act="wiz-back">Назад</button>`}
        ${running ? '' : done ? `<button class="btn" data-act="wiz-run">${ic('refresh', 'sm')}Ещё раз</button><button class="btn primary" data-act="wiz-finish">${ic('check', 'sm')}Готово</button>`
    : `<button class="btn primary" data-act="wiz-run" ${ready ? '' : 'disabled'}>${ic('wand', 'sm')}Начать автонастройку</button>`}
      </div>
      ${running || done ? '' : '<p class="hint"><a href="#" data-act="wiz-finish">Пропустить — Relay будет искать элементы на ходу</a></p>'}`;
  }

  // ----- site editor -----

  function editorHtml() {
    const e = S.editor;
    const sel = e.selectors || {};
    const rows = Object.keys(PICK_LABELS).map((k) => `
      <div class="field"><label>${esc(PICK_LABELS[k][0])}</label><span class="desc">${esc(PICK_LABELS[k][1])}</span>
        <div class="sel-row"><input class="text-in mono" data-sel="${k}" value="${esc(sel[k] || '')}" placeholder="авто"><button class="icon-btn boxed" data-pickel="${k}" title="Выбрать на сайте">${ic('target', 'sm')}</button></div></div>`).join('');
    const d = S.diag;
    const diag = d ? `<div class="diag">
        <span class="${d.input ? 'ok' : 'bad'}">${d.input ? '✓' : '✕'} Поле ввода ${d.input ? (d.inputVia === 'selector' ? 'по селектору' : 'найдено автоматически') : 'не найдено'}</span>
        <span class="${d.send ? 'ok' : ''}">${d.send ? '✓ Кнопка отправки найдена' : '• Кнопка отправки не найдена (будет Enter, она может появиться после ввода текста)'}</span>
        <span>• Чатов в списке: ${d.chats}</span>
        ${Object.keys(d.counts || {}).map((k) => `<span class="${d.counts[k] ? 'ok' : 'bad'}">${d.counts[k] ? '✓' : '✕'} ${esc((PICK_LABELS[k] || [k])[0])}: ${d.counts[k]}</span>`).join('')}
        ${d.lastResponse ? `<span>Последний ответ: «${esc(d.lastResponse)}»</span>` : ''}
      </div>` : '';
    return head(e.name || 'Сервис', 'Пустые поля — автоопределение. Кнопка 🎯 открывает сайт: просто тапните нужный элемент.') +
      (e.note ? `<p class="hint" style="margin-top:0">${esc(e.note)}</p>` : '') +
      `<div class="field"><label>Название</label><input class="text-in" data-f="name" value="${esc(e.name || '')}"></div>
       <div class="field"><label>Адрес</label><input class="text-in" data-f="url" value="${esc(e.url || '')}"></div>
       <div class="field"><label>Адрес нового чата</label><input class="text-in" data-f="newChatUrl" value="${esc(e.newChatUrl || '')}" placeholder="как адрес"></div>
       <div class="field"><label>Как отправлять</label><div class="seg" data-seg="submit">
         ${[['auto', 'Авто'], ['button', 'Кнопка'], ['enter', 'Enter']].map(([v, t]) => `<button class="${(e.submit || 'auto') === v ? 'on' : ''}" data-v="${v}">${t}</button>`).join('')}</div></div>
       <div class="card"><div class="row"><span class="rt"><b>Версия для ПК</b><small>Показывает боковую панель с чатами на сайтах, где на телефоне её нет</small></span><label class="switch"><input type="checkbox" data-f="desktop" ${e.desktop ? 'checked' : ''}><span></span></label></div></div>
       ${rows}
       ${diag}
       <div class="sheet-actions">
         <button class="btn" data-act="diagnose">${ic('eye', 'sm')}Проверить</button>
         <button class="btn primary" data-act="save-adapter">${ic('check', 'sm')}Сохранить</button>
       </div>
       <div class="sheet-actions" style="margin-top:10px">
         ${e.overridden ? '<button class="btn ghost" data-act="reset-adapter">Сбросить к стандартным</button>' : ''}
         ${e.custom ? '<button class="btn danger" data-act="delete-adapter">Удалить сервис</button>' : ''}
       </div>`;
  }

  function openEditor(id) {
    const a = S.adapters.find((x) => x.id === id);
    if (!a) return;
    S.editor = JSON.parse(JSON.stringify(a));
    S.editor.selectors = S.editor.selectors || {};
    S.diag = null;
    openSheet('editor', editorHtml());
  }

  function readEditor() {
    const e = S.editor;
    const sheet = $('#sheetLayer .sheet');
    if (!e || !sheet) return e;
    $$('[data-f]', sheet).forEach((i) => { e[i.dataset.f] = i.type === 'checkbox' ? i.checked : i.value.trim(); });
    $$('[data-sel]', sheet).forEach((i) => { e.selectors[i.dataset.sel] = i.value.trim(); });
    return e;
  }

  function adapterPayload(e) {
    const out = {};
    ['id', 'name', 'kind', 'group', 'domain', 'url', 'newChatUrl', 'color', 'icon', 'desktop', 'submit', 'singleThread', 'singleThreadTitle', 'note', 'package'].forEach((k) => { if (e[k] !== undefined && e[k] !== '') out[k] = e[k]; });
    const sel = {};
    const before = (S.adapters.find((a) => a.id === e.id) || {}).selectors || {};
    // A cleared field is sent as "" so the host can drop a built-in selector instead of keeping it.
    Object.keys(e.selectors || {}).forEach((k) => { if (e.selectors[k] || before[k]) sel[k] = e.selectors[k] || ''; });
    out.selectors = sel;
    if (out.url && !out.domain) out.domain = hostOf(out.url);
    return out;
  }

  // ----- reader & settings -----

  function openReader(m) {
    openSheet('reader', head(S.adapter ? S.adapter.name : 'Ответ', `${words(m.text)} слов · ${m.text.length} символов`) +
      `<div class="reader md">${md(m.text)}</div>
       <div class="sheet-actions"><button class="btn" data-copymsg="${esc(m.id)}">${ic('copy', 'sm')}Копировать</button><button class="btn primary" data-act="close-sheet">Закрыть</button></div>`, 'tall');
  }

  function settingsHtml() {
    const s = S.settings, d = S.device, m = S.mcp, a = S.adapter;
    const seg = (key, opts) => `<div class="seg" data-setting="${key}">${opts.map(([v, t]) => `<button class="${String(s[key]) === v ? 'on' : ''}" data-v="${v}">${t}</button>`).join('')}</div>`;
    const rootChip = d.root === true ? '<span class="chip ok">есть</span>' : d.root === false ? '<span class="chip bad">не выдан</span>' : d.suPresent ? '<span class="chip warn">не проверен</span>' : '<span class="chip">нет</span>';
    const claudeCmd = `claude mcp add --transport http relay-phone ${m.url || ''} --header "Authorization: Bearer ${m.token || ''}"`;
    const appSection = isApp(a) ? `<div class="set-section"><h3>${esc(a.name)} (приложение)</h3><div class="card">
        <div class="row">${ic('wand', 'lead')}<span class="rt"><b>Автонастройка ${a.calibrated ? '<span class="chip ok">готово</span>' : '<span class="chip warn">не выполнена</span>'}</b><small>Relay заново найдёт поле ввода, кнопку и чаты — если приложение обновилось и что-то перестало работать.</small></span><button class="btn sm" data-wizard="${esc(a.id)}">Запустить</button></div>
        <div class="row">${ic('eye', 'lead')}<span class="rt"><b>Снимок экрана для отладки</b><small>Сохраняет, что Relay «видит» в приложении, в файл в «Загрузках». Пришлите его разработчику, если что-то не находится.</small></span><button class="btn sm" data-act="dump">Сохранить</button></div>
        ${a.calibrated ? `<div class="row"><span class="rt"><b>Сбросить настройку</b><small>Забыть найденные элементы и список чатов.</small></span><button class="btn sm ghost" data-reset="${esc(a.id)}">Сбросить</button></div>` : ''}
      </div></div>` : '';
    return head('Настройки', esc(d.model || '') + (S.version ? ' · Relay ' + esc(S.version) : '')) +
      appSection +
      `<div class="set-section"><h3>Устройство</h3><div class="card">
        <div class="row">${ic('wand', 'lead')}<span class="rt"><b>Мастер настройки</b><small>Проверить телефон, найти ИИ-приложения и настроить их автоматически.</small></span><button class="btn sm" data-act="wizard">Открыть</button></div>
        <div class="row">${ic('hash', 'lead')}<span class="rt"><b>Root-доступ ${rootChip}</b><small>С root Relay сам выдаёт себе разрешения и может выполнять команды от su.</small></span><button class="btn sm" data-act="check-root">Проверить</button></div>
        <div class="row col"><span class="rt"><b>Использовать root</b><small>«Авто» — только когда обычных прав не хватает или ИИ явно просит root.</small></span>${seg('rootMode', [['auto', 'Авто'], ['off', 'Никогда']])}</div>
      </div></div>
      <div class="set-section"><h3>Разрешения</h3><div class="card">
        <div class="row">${ic('folder', 'lead')}<span class="rt"><b>Доступ ко всем файлам ${d.storage ? '<span class="chip ok">есть</span>' : '<span class="chip warn">нет</span>'}</b><small>Чтобы ИИ мог работать с «Загрузками», «Документами» и т.д.</small></span>${d.storage ? '' : '<button class="btn sm" data-perm="storage">Выдать</button>'}</div>
        <div class="row">${ic('hand', 'lead')}<span class="rt"><b>Спец. возможности ${d.accessibility ? '<span class="chip ok">вкл</span>' : '<span class="chip">выкл</span>'}</b><small>Нужно для приложений (Hark Pro, ChatGPT, Notion…), не для сайтов.</small></span>${d.accessibility ? '' : '<button class="btn sm" data-perm="accessibility">Включить</button>'}</div>
      </div></div>
      <div class="set-section"><h3>Агент</h3><div class="card">
        <div class="row col"><span class="rt"><b>Подтверждение действий</b><small>Удаление и shell всегда требуют подтверждения, кроме режима «Без вопросов».</small></span>${seg('approvalMode', [['ask', 'Спрашивать'], ['read', 'Авто-чтение'], ['all', 'Без вопросов']])}</div>
        <div class="row"><span class="rt"><b>Инструкция для ИИ</b><small>В первое сообщение каждого чата Relay добавляет описание инструментов.</small></span><label class="switch"><input type="checkbox" data-toggle="autoPreamble" ${s.autoPreamble ? 'checked' : ''}><span></span></label></div>
        <div class="row"><span class="rt"><b>Отправить инструкцию заново</b><small>Если ИИ «забыл», как вызывать инструменты в этом чате.</small></span><button class="btn sm" data-act="resend-preamble">Повторить</button></div>
        <div class="row"><span class="rt"><b>Макс. шагов за задачу</b><small>Защита от бесконечных циклов.</small></span><input class="text-in" style="width:76px;text-align:center" type="number" min="1" max="50" data-number="maxSteps" value="${esc(s.maxSteps || 12)}"></div>
      </div></div>
      <div class="set-section"><h3>Файлы</h3><div class="card">
        <div class="row">${ic('download', 'lead')}<span class="rt"><b>Скачивать файлы из ответов</b><small>Если ИИ прислал файл (PDF, таблицу, картинку), Relay нажмёт «Скачать» и положит его в «Загрузки».</small></span><label class="switch"><input type="checkbox" data-toggle="autoDownload" ${s.autoDownload !== false ? 'checked' : ''}><span></span></label></div>
        <div class="row">${ic('clip', 'lead')}<span class="rt"><b>Отправка файлов в ИИ</b><small>Кнопка 📎 или «Поделиться → Relay» из любого приложения. Приложениям файл передаётся через «Поделиться», сайтам — как обычная загрузка.</small></span></div>
      </div></div>
      <div class="set-section"><h3>MCP-сервер</h3><div class="card">
        <div class="row">${ic('server', 'lead')}<span class="rt"><b>Коннектор для Claude Code и др.</b><small>Те же инструменты телефона по протоколу MCP в вашей Wi-Fi сети. Каждый вызов подтверждается здесь.</small></span><label class="switch"><input type="checkbox" data-act="mcp" ${m.running ? 'checked' : ''}><span></span></label></div>
        ${m.running ? `<div class="row col"><span class="rt"><b>Адрес</b></span><div class="codebox"><span>${esc(m.url)}</span><button class="icon-btn" data-copy="${esc(m.url)}">${ic('copy', 'sm')}</button></div>
          <span class="rt"><b>Токен</b></span><div class="codebox"><span>${esc(m.token)}</span><button class="icon-btn" data-copy="${esc(m.token)}">${ic('copy', 'sm')}</button></div>
          <span class="rt"><b>Команда для Claude Code</b></span><div class="codebox"><span>${esc(claudeCmd)}</span><button class="icon-btn" data-copy="${esc(claudeCmd)}">${ic('copy', 'sm')}</button></div>
          <button class="btn sm ghost" data-act="rotate-token">Сменить токен</button></div>` : ''}
      </div></div>
      <div class="set-section"><h3>Аккаунты</h3><div class="card">
        <div class="row"><span class="rt"><b>Выйти со всех сайтов</b><small>Удаляет cookies и данные сайтов внутри Relay. Аккаунты в приложениях не затрагиваются.</small></span><button class="btn sm danger" data-act="clear-sites">Выйти</button></div>
      </div></div>
      <p class="hint">Relay не взламывает сервисы и не использует скрытые API: он открывает обычное приложение или сайт под вашим аккаунтом и печатает за вас — как если бы вы делали это руками.</p>`;
  }
  function openSettings() { openDrawer(false); openSheet('settings', settingsHtml()); }
  function rerenderSheet() {
    if (S.sheet === 'settings') refreshSheet(settingsHtml());
    else if (S.sheet === 'services') refreshSheet(servicesHtml());
    else if (S.sheet === 'editor') refreshSheet(editorHtml());
    else if (S.sheet === 'wizard') refreshSheet(wizardHtml());
    else if (S.sheet === 'apps') { const l = $('#appList'); if (l) l.outerHTML = el(`<div>${appsHtml()}</div>`).querySelector('#appList').outerHTML; }
  }

  // ---------- events from host ----------

  function setAdapters(list, discovered) {
    S.adapters = list || [];
    if (discovered) S.discovered = discovered;
    if (S.adapter) S.adapter = S.adapters.find((a) => a.id === S.adapter.id) || null;
  }

  function receive(msg) {
    switch (msg.type) {
      case 'init':
        setAdapters(msg.adapters, msg.discovered);
        S.settings = msg.settings || {};
        S.device = msg.device || {};
        S.mcp = msg.mcp || {};
        S.version = msg.version || '';
        S.onboarded = msg.onboarded !== false;
        S.attachments = msg.attachments || [];
        if (msg.lastAdapter && S.adapters.some((a) => a.id === msg.lastAdapter)) selectAdapter(msg.lastAdapter);
        else renderAll();
        if (!S.onboarded) openWizard(0);
        break;
      case 'adapters': {
        setAdapters(msg.adapters, msg.discovered);
        if (S.editor) {
          const fresh = S.adapters.find((a) => a.id === S.editor.id);
          if (fresh) { S.editor = JSON.parse(JSON.stringify(fresh)); S.editor.selectors = S.editor.selectors || {}; } else closeSheet();
        }
        renderConn(); renderTop(); rerenderSheet();
        break;
      }
      case 'session':
        if (!S.adapter || msg.adapterId !== S.adapter.id) break;
        S.state = msg.state;
        if (msg.url) handleUrl(msg.url);
        renderConn(); renderTop(); renderChats();
        break;
      case 'url':
        if (S.adapter && msg.adapterId === S.adapter.id) handleUrl(msg.url);
        break;
      case 'chats':
        if (!S.adapter || msg.adapterId !== S.adapter.id) break;
        S.chats = msg.items || [];
        S.chatsAt = msg.updatedAt || 0;
        renderChats(); renderTop();
        break;
      case 'turn':
        S.busy = msg.state === 'start';
        S.partial = '';
        if (!S.busy) S.lastTurnEnd = Date.now();
        renderComposer(); renderLive();
        break;
      case 'partial':
        S.partial = msg.text || '';
        renderLive();
        break;
      case 'assistant': {
        S.partial = '';
        const text = msg.display != null ? msg.display : msg.text;
        if (text && text.trim()) pushMsg({ role: 'assistant', text, step: msg.step });
        else renderLive();
        break;
      }
      case 'tool':
        if (msg.status === 'pending' || msg.status === 'running') S.live.add(msg.call.id);
        else S.live.delete(msg.call.id);
        upsertTool(msg.call, { status: msg.status, risk: msg.risk, result: msg.result || null, source: msg.source, needsApproval: false });
        break;
      case 'approval':
        S.live.add(msg.call.id);
        upsertTool(msg.call, { status: 'pending', risk: msg.risk, source: msg.source, needsApproval: true });
        if (S.sheet || $('#app').classList.contains('drawer-open')) toast('Нужно ваше подтверждение');
        break;
      case 'files':
        if (msg.items && msg.items.length) pushMsg({ role: 'files', source: msg.source, items: msg.items });
        break;
      case 'attachments':
        S.attachments = msg.items || [];
        renderComposer();
        break;
      case 'compose':
        $('#input').value = msg.text || '';
        autosize(); renderComposer();
        break;
      case 'calib':
        if (!S.calib) S.calib = { steps: {}, running: true };
        S.calib.steps[msg.step] = { status: msg.status, detail: msg.detail };
        if (S.sheet === 'wizard') refreshSheet(wizardHtml());
        break;
      case 'calibDone':
        S.calib = Object.assign(S.calib || { steps: {} }, { running: false, done: true, ok: msg.ok, message: msg.message });
        if (S.sheet === 'wizard') refreshSheet(wizardHtml());
        else toast(msg.message || 'Автонастройка завершена');
        break;
      case 'info':
        pushMsg({ role: 'info', text: msg.text });
        break;
      case 'error':
        if (S.calib && S.calib.running && S.sheet === 'wizard') { S.calib.running = false; toast(msg.message); refreshSheet(wizardHtml()); break; }
        pushMsg({ role: 'error', text: msg.message });
        break;
      case 'toast':
        toast(msg.text);
        break;
      case 'device':
        S.device = msg; renderConn(); rerenderSheet();
        break;
      case 'mcp':
        S.mcp = msg; renderConn(); rerenderSheet();
        break;
      case 'settings':
        S.settings = msg.settings || {}; rerenderSheet();
        break;
      case 'apps':
        S.apps = msg.items || []; rerenderSheet();
        break;
      case 'picked':
        if (S.editor) {
          readEditor();
          S.editor.selectors[msg.kind] = msg.selector;
          refreshSheet(editorHtml());
          toast(`${(PICK_LABELS[msg.kind] || [msg.kind])[0]}: найдено ${msg.count}`);
        }
        break;
      case 'pickCancelled':
        toast('Выбор отменён');
        break;
      case 'diagnose':
        S.diag = msg;
        if (S.sheet === 'editor') {
          readEditor();
          refreshSheet(editorHtml());
          const box = $('#sheetLayer .diag');
          if (box) box.scrollIntoView({ block: 'nearest' });
        }
        break;
      case 'back':
        if (S.sheet) closeSheet();
        else if ($('#app').classList.contains('drawer-open')) openDrawer(false);
        else host('exit');
        break;
    }
  }

  // ---------- wiring ----------

  function finishWizard() {
    const id = S.wiz && S.wiz.app;
    S.onboarded = true;
    host('finishOnboarding');
    closeSheet();
    if (id && (!S.adapter || S.adapter.id !== id)) selectAdapter(id);
  }

  function onClick(e) {
    const t = e.target.closest('button, a, [role="button"], [data-act]');
    if (!t) return;
    const d = t.dataset;
    if (t.tagName === 'A' && d.act) e.preventDefault();

    if (d.approve) {
      const card = t.closest('.tool');
      const id = card && card.dataset.id;
      const m = S.msgs.find((x) => x.id === id);
      if (m) { m.needsApproval = false; m.status = d.approve === 'deny' ? 'denied' : 'running'; card.replaceWith(msgNode(m)); persist(); }
      host('approve', { callId: id, decision: d.approve });
      return;
    }
    if (d.expand) {
      const m = S.msgs.find((x) => x.id === d.expand);
      if (m) { m.expanded = !m.expanded; replaceMsg(m); }
      return;
    }
    if (d.copymsg) { const m = S.msgs.find((x) => x.id === d.copymsg); if (m) host('copy', { text: m.text }); return; }
    if (d.read) { const m = S.msgs.find((x) => x.id === d.read); if (m) openReader(m); return; }
    if (d.open) { host('openFile', { path: d.open }); return; }
    if (d.share) { host('shareFile', { path: d.share }); return; }
    if (d.unattach) { S.attachments = S.attachments.filter((f) => f.id !== d.unattach); renderComposer(); host('removeAttachment', { id: d.unattach }); return; }
    if (d.install) { e.stopPropagation(); host('installApp', { package: d.install }); return; }
    if (d.wizard) { openWizard(2, d.wizard); return; }
    if (d.wizapp) { S.wiz.app = d.wizapp; refreshSheet(wizardHtml()); return; }
    if (d.reset) { host('resetSetup', { id: d.reset }); return; }
    if (d.suggest) { $('#input').value = d.suggest; autosize(); renderComposer(); $('#input').focus(); return; }
    if (d.href) {
      // The site WebView is shared by all chats of a service, and replies of a running turn would land in
      // the newly opened chat: switching waits until the turn ends.
      if (S.busy && normUrl(d.href) !== normUrl(S.url)) { toast(BUSY_TEXT); return; }
      handleUrl(d.href); host('openChat', { href: d.href }); openDrawer(false); return;
    }
    if (d.edit) { e.stopPropagation(); openEditor(d.edit); return; }
    if (d.del) { e.stopPropagation(); host('deleteAdapter', { id: d.del }); return; }
    if (d.pick) {
      if (S.busy && S.adapter && S.adapter.id !== d.pick) { toast(BUSY_TEXT); return; }
      const a = S.adapters.find((x) => x.id === d.pick);
      closeSheet();
      if (!S.adapter || S.adapter.id !== d.pick) selectAdapter(d.pick);
      if (a && isApp(a) && a.installed && !a.calibrated) setTimeout(() => openWizard(2, a.id), 200);
      return;
    }
    if (d.app) {
      const id = 'app-' + d.app.replace(/[^\w]/g, '-');
      host('saveAdapter', { adapter: { id, name: d.label, kind: 'app', group: 'apps', package: d.app, icon: d.label.slice(0, 2), color: PALETTE[d.app.length % PALETTE.length] } });
      if (S.sheet === 'wizard') { S.wiz.app = id; setTimeout(() => refreshSheet(wizardHtml()), 200); return; }
      closeSheet();
      setTimeout(() => selectAdapter(id), 200);
      return;
    }
    if (d.pickel) {
      readEditor();
      if (!S.adapter || S.adapter.id !== S.editor.id) selectAdapter(S.editor.id);
      host('pick', { kind: d.pickel });
      return;
    }
    if (d.perm) { host('perm', { which: d.perm }); return; }
    if (d.copy) { host('copy', { text: d.copy }); return; }
    if (t.closest('[data-seg]')) {
      const seg = t.closest('[data-seg]');
      readEditor();
      S.editor[seg.dataset.seg] = d.v;
      refreshSheet(editorHtml());
      return;
    }
    if (t.closest('[data-setting]')) { host('setSetting', { key: t.closest('[data-setting]').dataset.setting, value: d.v }); return; }

    switch (d.act || t.id) {
      case 'close-sheet': if (S.sheet === 'wizard' && S.calib && S.calib.running) return; closeSheet(); break;
      case 'services': openServices(); break;
      case 'wizard': openWizard(0); break;
      case 'wiz-next': S.wiz.step = Math.min(2, S.wiz.step + 1); S.calib = null; refreshSheet(wizardHtml()); break;
      case 'wiz-back': S.wiz.step = Math.max(0, S.wiz.step - 1); refreshSheet(wizardHtml()); break;
      case 'wiz-skip': S.wiz.step = 1; refreshSheet(wizardHtml()); break;
      case 'wiz-run':
        S.calib = { steps: {}, running: true };
        refreshSheet(wizardHtml());
        host('calibrate', { id: S.wiz.app, test: S.wiz.test });
        break;
      case 'wiz-finish': finishWizard(); break;
      case 'wiz-sites': S.onboarded = true; host('finishOnboarding'); openServices(); break;
      case 'auto-setup': host('autoSetup'); toast('Выдаю разрешения через root…'); break;
      case 'stop': host('stop'); break;
      case 'rescan': host('scanApps'); toast('Проверяю установленные приложения…'); break;
      case 'refresh-app-chats': host('refreshChats'); openDrawer(false); break;
      case 'dump': host('dumpScreen'); closeSheet(); break;
      case 'add-site': openSheet('addSite', addSiteHtml()); break;
      case 'add-app': S.apps = null; S.appFilter = ''; openSheet('apps', appsHtml()); host('listApps'); break;
      case 'create-site': {
        let url = ($('#fUrl').value || '').trim();
        const name = ($('#fName').value || '').trim() || hostOf(url.includes('://') ? url : 'https://' + url) || 'Мой ИИ';
        if (!url) { toast('Укажите адрес сайта'); break; }
        if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
        const id = 'site-' + (hostOf(url) || 'x').replace(/[^\w]/g, '-') + '-' + uid().slice(0, 4);
        host('saveAdapter', { adapter: { id, name, kind: 'web', group: 'custom', url, newChatUrl: url, domain: hostOf(url), icon: name.slice(0, 2), color: PALETTE[name.length % PALETTE.length], selectors: {}, submit: 'auto', desktop: false } });
        closeSheet();
        setTimeout(() => { selectAdapter(id); toast('Сайт добавлен. Войдите в аккаунт через кнопку 🌐'); }, 150);
        break;
      }
      case 'save-adapter': host('saveAdapter', { adapter: adapterPayload(readEditor()) }); break;
      case 'diagnose': {
        const e2 = readEditor();
        if (!S.adapter || S.adapter.id !== e2.id) { selectAdapter(e2.id); toast('Сайт загружается — нажмите «Проверить» ещё раз через пару секунд'); break; }
        host('diagnose', { selectors: adapterPayload(e2).selectors });
        break;
      }
      case 'reset-adapter':
      case 'delete-adapter': host('deleteAdapter', { id: S.editor.id }); closeSheet(); break;
      case 'check-root': host('checkRoot'); break;
      case 'resend-preamble': host('resendPreamble'); break;
      case 'rotate-token': host('rotateToken'); break;
      case 'clear-sites': host('clearSites'); break;
      case 'site': host('showSite'); break;
      case 'a11y': host('perm', { which: 'accessibility' }); break;
      case 'connBtn': case 'titleBtn': openServices(); break;
      case 'newChatBtn':
        if (S.busy) { toast(BUSY_TEXT); break; }
        if (S.adapter && isApp(S.adapter)) {
          const k = keyFor(appHome(S.adapter) + '#new');
          store.save(k, []);
          if (S.key === k) { S.msgs = []; renderThread(); }
        }
        host('newChat'); openDrawer(false);
        break;
      case 'jumpBtn': scrollDown(true); break;
      case 'refreshBtn': host('refreshChats'); if (!isApp(S.adapter)) toast('Обновляю список…'); break;
      case 'settingsBtn': openSettings(); break;
      case 'menuBtn': openDrawer(true); break;
      case 'siteBtn': host('showSite'); break;
      case 'attachBtn': host('pickFiles'); break;
      case 'agentChip':
        S.agent = !S.agent;
        localStorage.setItem('relay.agent', S.agent ? '1' : '0');
        renderComposer();
        if (!S.msgs.length) renderThread();
        break;
      case 'sendBtn': submit(); break;
    }
  }

  function onChange(e) {
    const t = e.target;
    if (t.dataset.toggle) host('setSetting', { key: t.dataset.toggle, value: t.checked });
    else if (t.dataset.number) host('setSetting', { key: t.dataset.number, value: Number(t.value) || 12 });
    else if (t.dataset.act === 'mcp') host('mcp', { enabled: t.checked });
    else if (t.dataset.act === 'wiz-test') S.wiz.test = t.checked;
  }

  function init() {
    renderStatic();
    initScroll();
    document.addEventListener('click', onClick);
    document.addEventListener('change', onChange);
    $('#scrim').addEventListener('click', () => openDrawer(false));
    const input = $('#input');
    input.addEventListener('input', () => { autosize(); renderComposer(); });
    input.addEventListener('keydown', (e) => {
      const desktopLike = window.matchMedia('(pointer: fine)').matches;
      if (e.key === 'Enter' && ((desktopLike && !e.shiftKey) || e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); }
    });
    $('#chatSearch').addEventListener('input', (e) => { S.filter = e.target.value; renderChats(); });
    document.addEventListener('input', (e) => {
      if (e.target.id === 'appSearch') { S.appFilter = e.target.value; rerenderSheet(); }
    });
    renderAll();
    host('init');
  }

  window.relayUI = { receive, _state: S };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
