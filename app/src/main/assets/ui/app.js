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
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>'
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
    needsAccessibility: ['включите спец. возможности', 'bad'],
    notInstalled: ['приложение не установлено', 'bad']
  };
  const STATUS_TEXT = { pending: 'ждёт ответа', running: 'выполняется', done: 'готово', error: 'ошибка', denied: 'отклонено', stale: 'прервано' };
  const BUSY_TEXT = 'Агент работает — дождитесь ответа или нажмите «Стоп»';
  const PICK_LABELS = {
    input: ['Поле ввода', 'Куда Relay вставляет сообщение'],
    send: ['Кнопка «Отправить»', 'Пусто — нажатие Enter'],
    response: ['Ответ ИИ', 'Любой блок с ответом ассистента'],
    busy: ['Кнопка «Стоп»', 'Видна, пока ИИ печатает'],
    chatLink: ['Ссылка на чат', 'Элемент в списке чатов'],
    projectLink: ['Ссылка на проект', 'Если у сервиса есть проекты']
  };
  const PALETTE = ['#8B5CF6', '#22D3EE', '#F472B6', '#34D399', '#F59E0B', '#60A5FA', '#FB7185', '#A3E635'];

  // ---------- host channel ----------

  const host = (cmd, data) => {
    try { window.RelayHost.post(JSON.stringify(Object.assign({ cmd }, data || {}))); } catch (e) { console.error(e); }
  };

  // ---------- state ----------

  const S = {
    adapters: [], adapter: null, state: 'idle', url: '', chats: [], key: null, msgs: [],
    settings: {}, device: {}, mcp: {}, apps: null, version: '',
    busy: false, partial: '', lastTurnEnd: 0, agent: localStorage.getItem('relay.agent') !== '0',
    filter: '', sheet: null, editor: null, diag: null,
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

  function keyFor(url) {
    const a = S.adapter;
    if (!a) return null;
    if (a.kind === 'app') return a.id + '|app';
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
    return `<span class="avatar ${size} ${dark}" style="background:${esc(color)}">${esc(a.icon || (a.name || '?').slice(0, 2))}</span>`;
  }

  // ---------- rendering: chrome ----------

  function renderStatic() {
    $('#brandLogo').innerHTML = ic('logo');
    $('#newChatBtn').innerHTML = ic('plus', 'sm') + '<span>Новый чат</span>';
    $('#refreshBtn').innerHTML = ic('refresh', 'sm');
    $('.search-ic').innerHTML = ic('search', 'sm');
    $('#settingsBtn').innerHTML = ic('settings', 'sm') + '<span>Настройки</span>';
    $('#menuBtn').innerHTML = ic('menu');
  }

  function stateBadge() {
    const [text, cls] = STATE_TEXT[S.state] || STATE_TEXT.idle;
    return `<i class="dot ${cls}"></i>${esc(text)}`;
  }

  function renderConn() {
    const a = S.adapter;
    $('#connBtn').innerHTML = a
      ? `${avatar(a)}<span class="meta"><span class="name">${esc(a.name)}${a.kind === 'app' ? ' · приложение' : ''}</span><span class="state">${stateBadge()}</span></span><span class="chev">${ic('chevron', 'sm')}</span>`
      : `${avatar(null)}<span class="meta"><span class="name">Выберите ИИ</span><span class="state">сайт или приложение</span></span><span class="chev">${ic('chevron', 'sm')}</span>`;
    const d = S.device;
    const root = d.root === true ? '<span class="chip ok">' + ic('hash', 'sm') + 'ROOT</span>'
      : d.suPresent ? '<span class="chip warn">su</span>' : '<span class="chip">без root</span>';
    $('#badges').innerHTML = root + (S.mcp.running ? '<span class="chip accent">MCP</span>' : '');
  }

  function currentChatTitle() {
    if (!S.adapter) return 'Relay';
    const u = normUrl(S.url);
    const hit = S.chats.find((c) => normUrl(c.href) === u);
    if (hit) return hit.title;
    if (S.adapter.kind === 'app') return 'Текущий чат';
    return (S.key || '').endsWith('|new') ? 'Новый чат' : S.adapter.name;
  }

  function renderTop() {
    const a = S.adapter;
    $('#topAvatar').outerHTML = `<span id="topAvatar">${avatar(a, 'sm')}</span>`;
    $('#topTitle').textContent = currentChatTitle();
    $('#topSub').innerHTML = a ? `${stateBadge()}<span>· ${esc(a.name)}</span>` : '<i class="dot"></i>не подключено';
    const site = $('#siteBtn');
    site.innerHTML = ic(a && a.kind === 'app' ? 'grid' : 'globe', 'sm');
    site.title = a && a.kind === 'app' ? 'Открыть приложение' : 'Открыть сайт';
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
      html = `${ic('alert')}<span class="txt">Приложение ${esc(a.name)} (${esc(a.package)}) не установлено.</span>`;
    }
    b.innerHTML = html;
    b.hidden = !html;
  }

  function renderChats() {
    const list = $('#chatList');
    const a = S.adapter;
    if (!a) {
      list.innerHTML = '<div class="list-empty">Подключите ИИ-сервис, и здесь появятся его чаты и проекты.</div>';
      return;
    }
    const f = S.filter.trim().toLowerCase();
    const items = S.chats.filter((c) => !f || (c.title || '').toLowerCase().includes(f));
    const cur = normUrl(S.url);
    const row = (c) => `<button class="chat-item ${normUrl(c.href) === cur ? 'active' : ''}" data-href="${esc(c.href)}">${ic(c.kind === 'project' ? 'layers' : 'chat', 'sm')}<span>${esc(c.title || 'Без названия')}</span></button>`;
    const projects = items.filter((c) => c.kind === 'project');
    const chats = items.filter((c) => c.kind !== 'project');
    let html = '';
    if (projects.length) html += '<div class="group-title">Проекты</div>' + projects.map(row).join('');
    if (chats.length) html += `<div class="group-title">${a.kind === 'app' ? 'Приложение' : 'Чаты'}</div>` + chats.map(row).join('');
    if (!html) {
      html = S.state === 'login' || S.state === 'loading'
        ? `<div class="list-empty">${S.state === 'loading' ? 'Загружаю сайт…' : 'Чаты появятся после входа в аккаунт.'}<br><button class="btn sm" data-act="site">${ic('globe', 'sm')}Открыть сайт</button></div>`
        : `<div class="list-empty">${f ? 'Ничего не найдено.' : 'Список чатов не найден на странице. Попробуйте «Версия для ПК» или укажите селектор чата в настройках сервиса.'}</div>`;
    }
    list.innerHTML = html;
  }

  function renderComposer() {
    const chip = $('#agentChip');
    chip.className = 'mode-chip' + (S.agent ? ' on' : '');
    chip.innerHTML = S.agent ? ic('zap') + 'Агент' : ic('chat') + 'Чат';
    $('#composerHint').textContent = S.agent ? 'Файлы · shell · приложения на устройстве' : 'Только переписка, без действий';
    const btn = $('#sendBtn');
    const has = $('#input').value.trim().length > 0;
    btn.className = 'send-btn' + (S.busy ? ' stop' : has ? '' : ' idle');
    btn.innerHTML = ic(S.busy ? 'stop' : 'send');
    btn.setAttribute('aria-label', S.busy ? 'Остановить' : 'Отправить');
    $('#input').placeholder = S.adapter ? `Задача для ${S.adapter.name}…` : 'Сначала выберите ИИ…';
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

  function msgNode(m) {
    switch (m.role) {
      case 'user':
        return el(`<div class="msg user" data-id="${esc(m.id)}"><div class="bubble">${esc(m.text)}</div></div>`);
      case 'assistant':
        return el(`<div class="msg ai" data-id="${esc(m.id)}">${avatar(S.adapter, 'sm')}<div class="body"><div class="who">${esc(m.who || (S.adapter && S.adapter.name) || 'ИИ')}</div><div class="bubble md">${md(m.text)}</div></div></div>`);
      case 'tool':
        return toolNode(m);
      case 'error':
        return el(`<div class="note err" data-id="${esc(m.id)}">${ic('alert', 'sm')}<span>${esc(m.text)}</span></div>`);
      default:
        return el(`<div class="note" data-id="${esc(m.id)}">${ic('info', 'sm')}<span>${esc(m.text)}</span></div>`);
    }
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
        <p>Пишите задачу здесь — она уходит в чат выбранного ИИ-сервиса, а действия выполняются прямо на этом устройстве.</p>
        <button class="btn primary" data-act="services">${ic('sparkles', 'sm')}Выбрать ИИ</button>
        <div class="steps">
          <div class="step"><span class="num">1</span><span><b>Выберите сервис</b> — ChatGPT, Claude, Notion AI, Hark Pro или любой сайт/приложение.</span></div>
          <div class="step"><span class="num">2</span><span><b>Войдите один раз</b> своим аккаунтом. Без API-ключей.</span></div>
          <div class="step"><span class="num">3</span><span><b>Ставьте задачи</b> — «создай папку», «найди файл», «запусти приложение». Опасные действия вы подтверждаете.</span></div>
        </div>
      </div>`);
    }
    const tips = [
      ['folderPlus', 'Создай папку «привет» в Загрузках'],
      ['folder', 'Покажи, что лежит в Загрузках'],
      ['search', 'Найди все PDF в памяти телефона'],
      ['phone', 'Сколько свободного места на телефоне?']
    ];
    return el(`<div class="hero">
      <div class="hero-logo">${avatar(a, 'lg')}</div>
      <h1>Что сделать?</h1>
      <p>Сообщение уйдёт в ${a.kind === 'app' ? 'приложение' : 'чат'} <b>${esc(a.name)}</b>. ${S.agent ? 'Режим агента: ИИ может работать с файлами и приложениями — каждое действие вы видите и подтверждаете.' : 'Режим чата: только переписка.'}</p>
      <div class="suggest">${tips.map(([i, t]) => `<button data-suggest="${esc(t)}">${ic(i, 'sm')}<span>${esc(t)}</span></button>`).join('')}</div>
    </div>`);
  }

  function liveNode() {
    if (!S.busy) return null;
    if (S.partial) {
      return el(`<div class="msg ai live">${avatar(S.adapter, 'sm')}<div class="body"><div class="who">${esc(S.adapter ? S.adapter.name : 'ИИ')} печатает…</div><div class="bubble md caret">${md(S.partial)}</div></div></div>`);
    }
    if (S.msgs.some((m) => m.role === 'tool' && (m.needsApproval || m.status === 'running'))) return null;
    return el(`<div class="msg ai live">${avatar(S.adapter, 'sm')}<div class="body"><div class="who">${esc(S.adapter ? S.adapter.name : 'ИИ')}</div><div class="typing"><i></i><i></i><i></i></div></div></div>`);
  }

  function renderThread() {
    const inner = $('#threadInner');
    inner.innerHTML = '';
    S.pinned = true;
    S.unseen = false;
    if (!S.msgs.length && !S.busy) {
      inner.appendChild(heroNode());
      $('#thread').scrollTop = 0;
      renderJump();
      return;
    }
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
    b.hidden = S.pinned;
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
    if (m.role === 'user') S.pinned = true;
    const hero = $('.hero', $('#threadInner'));
    if (hero) hero.remove();
    S.msgs.push(m);
    const inner = $('#threadInner');
    const live = $('.live', inner);
    inner.insertBefore(msgNode(m), live || null);
    persist();
    renderLive();
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
    const node = $(`[data-id="${CSS.escape(m.id)}"]`, $('#threadInner'));
    if (node) node.replaceWith(msgNode(m));
    persist();
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
    S.url = a.kind === 'app' ? 'app:' + a.package : a.url;
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
    const text = input.value.trim();
    if (!text) return;
    if (!S.adapter) { openServices(); toast('Сначала выберите, куда отправлять задачу'); return; }
    pushMsg({ role: 'user', text });
    input.value = '';
    autosize();
    host('send', { text, agent: S.agent });
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

  function openSheet(name, html) {
    const layer = $('#sheetLayer');
    S.sheet = name;
    layer.innerHTML = `<div class="sheet-backdrop" data-act="close-sheet"></div><div class="sheet" role="dialog" aria-modal="true"><div class="handle"></div>${html}</div>`;
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

  const head = (title, sub) => `<div class="sheet-head"><div class="txt"><h2>${esc(title)}</h2>${sub ? `<p class="sub">${sub}</p>` : ''}</div><button class="icon-btn close" data-act="close-sheet" aria-label="Закрыть">${ic('x')}</button></div>`;

  function tile(a) {
    const active = S.adapter && S.adapter.id === a.id ? 'active' : '';
    const off = a.kind === 'app' && a.installed === false ? 'off' : '';
    const sub = a.kind === 'app' ? (a.installed === false ? 'не установлено' : 'приложение') : (a.domain || hostOf(a.url));
    const edit = a.kind === 'web' ? `<button class="tile-edit" data-edit="${esc(a.id)}" aria-label="Настроить">${ic('edit')}</button>` : (a.custom ? `<button class="tile-edit" data-del="${esc(a.id)}" aria-label="Удалить">${ic('x')}</button>` : '');
    return `<div class="tile ${active} ${off}" role="button" tabindex="0" data-pick="${esc(a.id)}">${edit}${avatar(a, 'lg')}<span class="tile-name">${esc(a.name)}</span><span class="tile-sub">${esc(sub)}</span></div>`;
  }

  function hostOf(u) { try { return new URL(u).host.replace(/^www\./, ''); } catch (e) { return ''; } }

  function servicesHtml() {
    const web = S.adapters.filter((a) => a.kind === 'web');
    const apps = S.adapters.filter((a) => a.kind === 'app');
    return head('Подключить ИИ', 'Вход — вашим обычным аккаунтом прямо в Relay. Без API-ключей и без взлома: Relay просто печатает в чат за вас.') +
      `<div class="group-title">Сайты с ИИ</div><div class="tiles">${web.map(tile).join('')}
        <div class="tile add" role="button" tabindex="0" data-act="add-site"><span class="avatar lg">${ic('plus')}</span><span class="tile-name">Свой сайт</span><span class="tile-sub">любой чат с ИИ</span></div></div>
       <div class="group-title">Приложения на устройстве</div><div class="tiles">${apps.map(tile).join('')}
        <div class="tile add" role="button" tabindex="0" data-act="add-app"><span class="avatar lg">${ic('grid')}</span><span class="tile-name">Другое</span><span class="tile-sub">из установленных</span></div></div>
       <p class="hint">Приложения (например, Hark Pro) управляются через спец. возможности Android: Relay вставляет текст, нажимает «Отправить» и читает ответ с экрана. С root служба включается автоматически.</p>`;
  }
  function openServices() { openDrawer(false); openSheet('services', servicesHtml()); }

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
        `<button class="app-row" data-app="${esc(x.package)}" data-label="${esc(x.label)}">${avatar({ name: x.label, icon: x.label.slice(0, 2), color: PALETTE[x.package.length % PALETTE.length] }, 'sm')}<span><b>${esc(x.label)}</b><small>${esc(x.package)}</small></span></button>`).join('');
    return head('Приложение', 'Выберите приложение с ИИ, которым Relay будет управлять.') +
      `<label class="search"><span class="search-ic">${ic('search', 'sm')}</span><input id="appSearch" placeholder="Поиск приложений" value="${esc(S.appFilter || '')}"></label>
       <div id="appList" style="margin-top:8px">${list}</div>`;
  }

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
    return head(e.name || 'Сервис', 'Настройка подключения. Пустые поля — автоопределение. Кнопка 🎯 открывает сайт: просто тапните нужный элемент.') +
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

  function settingsHtml() {
    const s = S.settings, d = S.device, m = S.mcp;
    const seg = (key, opts) => `<div class="seg" data-setting="${key}">${opts.map(([v, t]) => `<button class="${String(s[key]) === v ? 'on' : ''}" data-v="${v}">${t}</button>`).join('')}</div>`;
    const rootChip = d.root === true ? '<span class="chip ok">есть</span>' : d.root === false ? '<span class="chip bad">не выдан</span>' : d.suPresent ? '<span class="chip warn">не проверен</span>' : '<span class="chip">нет</span>';
    const claudeCmd = `claude mcp add --transport http relay-phone ${m.url || ''} --header "Authorization: Bearer ${m.token || ''}"`;
    return head('Настройки', esc(d.model || '') + (S.version ? ' · Relay ' + esc(S.version) : '')) +
      `<div class="set-section"><h3>Устройство</h3><div class="card">
        <div class="row">${ic('hash', 'lead')}<span class="rt"><b>Root-доступ ${rootChip}</b><small>С root Relay сам выдаёт себе разрешения и может выполнять команды от su.</small></span><button class="btn sm" data-act="check-root">Проверить</button></div>
        <div class="row col"><span class="rt"><b>Использовать root</b><small>«Авто» — только когда обычных прав не хватает или ИИ явно просит root.</small></span>${seg('rootMode', [['auto', 'Авто'], ['off', 'Никогда']])}</div>
      </div></div>
      <div class="set-section"><h3>Разрешения</h3><div class="card">
        <div class="row">${ic('folder', 'lead')}<span class="rt"><b>Доступ ко всем файлам ${d.storage ? '<span class="chip ok">есть</span>' : '<span class="chip warn">нет</span>'}</b><small>Чтобы ИИ мог работать с «Загрузками», «Документами» и т.д.</small></span>${d.storage ? '' : '<button class="btn sm" data-perm="storage">Выдать</button>'}</div>
        <div class="row">${ic('hand', 'lead')}<span class="rt"><b>Спец. возможности ${d.accessibility ? '<span class="chip ok">вкл</span>' : '<span class="chip">выкл</span>'}</b><small>Нужно только для приложений (Hark Pro и др.), не для сайтов. На Android 13+ без root сначала разрешите «ограниченные настройки» в сведениях о Relay.</small></span>${d.accessibility ? '' : '<button class="btn sm" data-perm="accessibility">Включить</button>'}</div>
      </div></div>
      <div class="set-section"><h3>Агент</h3><div class="card">
        <div class="row col"><span class="rt"><b>Подтверждение действий</b><small>Удаление и shell всегда требуют подтверждения, кроме режима «Без вопросов».</small></span>${seg('approvalMode', [['ask', 'Спрашивать'], ['read', 'Авто-чтение'], ['all', 'Без вопросов']])}</div>
        <div class="row"><span class="rt"><b>Инструкция для ИИ</b><small>В первое сообщение каждого чата Relay добавляет описание инструментов.</small></span><label class="switch"><input type="checkbox" data-toggle="autoPreamble" ${s.autoPreamble ? 'checked' : ''}><span></span></label></div>
        <div class="row"><span class="rt"><b>Отправить инструкцию заново</b><small>Если ИИ «забыл», как вызывать инструменты в этом чате.</small></span><button class="btn sm" data-act="resend-preamble">Повторить</button></div>
        <div class="row"><span class="rt"><b>Макс. шагов за задачу</b><small>Защита от бесконечных циклов.</small></span><input class="text-in" style="width:76px;text-align:center" type="number" min="1" max="50" data-number="maxSteps" value="${esc(s.maxSteps || 12)}"></div>
      </div></div>
      <div class="set-section"><h3>MCP-сервер</h3><div class="card">
        <div class="row">${ic('server', 'lead')}<span class="rt"><b>Коннектор для Claude Code и др.</b><small>Те же инструменты телефона по протоколу MCP в вашей Wi-Fi сети. Каждый вызов подтверждается здесь.</small></span><label class="switch"><input type="checkbox" data-act="mcp" ${m.running ? 'checked' : ''}><span></span></label></div>
        ${m.running ? `<div class="row col"><span class="rt"><b>Адрес</b></span><div class="codebox"><span>${esc(m.url)}</span><button class="icon-btn" data-copy="${esc(m.url)}">${ic('copy', 'sm')}</button></div>
          <span class="rt"><b>Токен</b></span><div class="codebox"><span>${esc(m.token)}</span><button class="icon-btn" data-copy="${esc(m.token)}">${ic('copy', 'sm')}</button></div>
          <span class="rt"><b>Команда для Claude Code</b></span><div class="codebox"><span>${esc(claudeCmd)}</span><button class="icon-btn" data-copy="${esc(claudeCmd)}">${ic('copy', 'sm')}</button></div>
          <button class="btn sm ghost" data-act="rotate-token">Сменить токен</button></div>` : ''}
      </div></div>
      <div class="set-section"><h3>Аккаунты</h3><div class="card">
        <div class="row"><span class="rt"><b>Выйти со всех сайтов</b><small>Удаляет cookies и данные сайтов внутри Relay.</small></span><button class="btn sm danger" data-act="clear-sites">Выйти</button></div>
      </div></div>
      <p class="hint">Relay не взламывает сервисы и не использует скрытые API: он открывает обычный сайт или приложение под вашим аккаунтом и печатает за вас — как если бы вы делали это руками.</p>`;
  }
  function openSettings() { openDrawer(false); openSheet('settings', settingsHtml()); }
  function rerenderSheet() {
    if (S.sheet === 'settings') refreshSheet(settingsHtml());
    else if (S.sheet === 'services') refreshSheet(servicesHtml());
    else if (S.sheet === 'editor') refreshSheet(editorHtml());
    else if (S.sheet === 'apps') { const l = $('#appList'); if (l) l.outerHTML = el(`<div>${appsHtml()}</div>`).querySelector('#appList').outerHTML; }
  }

  // ---------- events from host ----------

  function receive(msg) {
    switch (msg.type) {
      case 'init':
        S.adapters = msg.adapters || [];
        S.settings = msg.settings || {};
        S.device = msg.device || {};
        S.mcp = msg.mcp || {};
        S.version = msg.version || '';
        if (msg.lastAdapter && S.adapters.some((a) => a.id === msg.lastAdapter)) selectAdapter(msg.lastAdapter);
        else renderAll();
        break;
      case 'adapters': {
        S.adapters = msg.adapters || [];
        if (S.adapter) S.adapter = S.adapters.find((a) => a.id === S.adapter.id) || null;
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
      case 'info':
        pushMsg({ role: 'info', text: msg.text });
        break;
      case 'error':
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

  function onClick(e) {
    const t = e.target.closest('button, [role="button"], [data-act]');
    if (!t) return;
    const d = t.dataset;

    if (d.approve) {
      const card = t.closest('.tool');
      const id = card && card.dataset.id;
      const m = S.msgs.find((x) => x.id === id);
      if (m) { m.needsApproval = false; m.status = d.approve === 'deny' ? 'denied' : 'running'; card.replaceWith(msgNode(m)); persist(); }
      host('approve', { callId: id, decision: d.approve });
      return;
    }
    if (d.suggest) { $('#input').value = d.suggest; autosize(); renderComposer(); $('#input').focus(); return; }
    if (d.href) {
      // The site WebView is shared by all chats of a service: navigating now would abandon the running reply.
      if (S.busy && normUrl(d.href) !== normUrl(S.url)) { toast(BUSY_TEXT); return; }
      S.url = d.href; handleUrl(d.href); host('openChat', { href: d.href }); openDrawer(false); return;
    }
    if (d.edit) { e.stopPropagation(); openEditor(d.edit); return; }
    if (d.del) { e.stopPropagation(); host('deleteAdapter', { id: d.del }); return; }
    if (d.pick) {
      if (S.busy && S.adapter && S.adapter.id !== d.pick) { toast(BUSY_TEXT); return; }
      closeSheet(); if (!S.adapter || S.adapter.id !== d.pick) selectAdapter(d.pick); return;
    }
    if (d.app) {
      const id = 'app-' + d.app.replace(/[^\w]/g, '-');
      host('saveAdapter', { adapter: { id, name: d.label, kind: 'app', group: 'apps', package: d.app, icon: d.label.slice(0, 2), color: PALETTE[d.app.length % PALETTE.length] } });
      closeSheet();
      setTimeout(() => selectAdapter(id), 150);
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
      case 'close-sheet': closeSheet(); break;
      case 'services': openServices(); break;
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
        host('newChat'); openDrawer(false); break;
      case 'jumpBtn': scrollDown(true); break;
      case 'refreshBtn': host('refreshChats'); toast('Обновляю список…'); break;
      case 'settingsBtn': openSettings(); break;
      case 'menuBtn': openDrawer(true); break;
      case 'siteBtn': host('showSite'); break;
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
