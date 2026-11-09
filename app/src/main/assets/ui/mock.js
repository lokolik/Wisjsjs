/*
 * Browser-only stand-in for the Android host (window.RelayHost). Loaded only when the native bridge is absent,
 * so the UI can be previewed and screenshot-tested in a desktop browser. Never used inside the app.
 */
(function () {
  // Inside the app the UI is a file:// page; never fake the host there, even if the bridge is missing.
  if (window.RelayHost || !/^https?:$/.test(location.protocol)) return;

  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const emit = (m) => setTimeout(() => window.relayUI && window.relayUI.receive(JSON.parse(JSON.stringify(m))), 0);
  const params = new URLSearchParams(location.search);

  const FALLBACK = [
    { id: 'chatgpt', name: 'ChatGPT', kind: 'web', domain: 'chatgpt.com', url: 'https://chatgpt.com/', newChatUrl: 'https://chatgpt.com/', color: '#10A37F', icon: 'GPT', selectors: {} },
    { id: 'claude', name: 'Claude', kind: 'web', domain: 'claude.ai', url: 'https://claude.ai/new', newChatUrl: 'https://claude.ai/new', color: '#D97757', icon: 'C', selectors: {} },
    { id: 'notion', name: 'Notion AI', kind: 'web', domain: 'notion.so', url: 'https://www.notion.so/ai', color: '#FFFFFF', icon: 'N', selectors: {} },
    { id: 'app-hark', name: 'Hark Pro', kind: 'app', package: 'com.hark.android', color: '#F5C542', icon: 'H' }
  ];
  const CHATS = {
    chatgpt: [
      { title: 'Работа', href: 'https://chatgpt.com/g/g-p-work/project', kind: 'project' },
      { title: 'Учёба', href: 'https://chatgpt.com/g/g-p-study/project', kind: 'project' },
      { title: 'Скрипт для бэкапа фото', href: 'https://chatgpt.com/c/a1', kind: 'chat' },
      { title: 'План поездки в Казань', href: 'https://chatgpt.com/c/a2', kind: 'chat' },
      { title: 'Разбор договора аренды', href: 'https://chatgpt.com/c/a3', kind: 'chat' },
      { title: 'Идеи для Notion-шаблона', href: 'https://chatgpt.com/c/a4', kind: 'chat' },
      { title: 'Рецепт сырников', href: 'https://chatgpt.com/c/a5', kind: 'chat' }
    ]
  };

  const state = {
    adapters: null,
    custom: {},
    current: null,
    settings: { approvalMode: 'ask', rootMode: 'auto', autoPreamble: true, maxSteps: 12, mcpEnabled: false, mcpPort: 8765 },
    device: { type: 'device', suPresent: true, root: true, storage: true, accessibility: false, sdk: 35, model: 'Google Pixel 9 Pro', storageRoot: '/storage/emulated/0' },
    mcp: { type: 'mcp', running: false, url: 'http://192.168.1.42:8765/mcp', port: 8765, token: '7f3c9a1e5b2d48f0a6c1e9d3b7a2f4c8' },
    approvals: {},
    run: 0
  };

  async function adapters() {
    if (!state.adapters) {
      try {
        const r = await fetch('../adapters.json');
        state.adapters = (await r.json()).adapters;
      } catch (e) {
        state.adapters = FALLBACK;
      }
      state.adapters.forEach((a) => {
        a.builtin = true;
        if (a.kind === 'app') a.installed = ['app-hark', 'app-chatgpt', 'app-claude'].includes(a.id);
      });
    }
    return state.adapters.map((a) => Object.assign({}, a, state.custom[a.id] ? Object.assign({ overridden: true }, state.custom[a.id]) : {}))
      .concat(Object.values(state.custom).filter((c) => !state.adapters.some((a) => a.id === c.id)).map((c) => Object.assign({ custom: true }, c)));
  }

  async function stream(text, run) {
    const words = text.split(' ');
    let acc = '';
    for (let i = 0; i < words.length; i++) {
      if (run !== state.run) return false;
      acc += (i ? ' ' : '') + words[i];
      if (i % 3 === 2 || i === words.length - 1) emit({ type: 'partial', text: acc });
      await wait(55);
    }
    return true;
  }

  async function tool(call, risk, output, run) {
    let decision = 'once';
    const auto = state.settings.approvalMode === 'all' || (state.settings.approvalMode === 'read' && risk === 'READ');
    if (!auto) {
      emit({ type: 'tool', status: 'pending', risk, call, source: 'chat' });
      emit({ type: 'approval', risk, call, source: 'chat' });
      decision = await new Promise((r) => { state.approvals[call.id] = r; });
    }
    if (run !== state.run) return false;
    if (decision === 'deny') {
      emit({ type: 'tool', status: 'denied', risk, call, result: { ok: false, output: 'Пользователь отклонил это действие.' } });
      return false;
    }
    emit({ type: 'tool', status: 'running', risk, call });
    await wait(650);
    emit({ type: 'tool', status: 'done', risk, call, result: { ok: true, output } });
    return true;
  }

  async function turn(text, agent) {
    const run = ++state.run;
    emit({ type: 'turn', state: 'start' });
    await wait(700);
    const folder = /папк|folder/i.test(text);
    const list = /что (лежит|есть)|покажи|список/i.test(text);
    try {
      if (agent && folder) {
        const name = (text.match(/[«"„']([^»"“']+)[»"“']/) || [])[1] || 'привет';
        const intro = `Создаю папку «${name}» в «Загрузках».`;
        if (!await stream(intro, run)) return;
        emit({ type: 'assistant', step: 1, text: intro + '\nrelay\n{"tool":"mkdir","args":{"path":"~/Download/' + name + '"}}', display: intro });
        const ok = await tool({ id: 'm' + Date.now(), tool: 'mkdir', args: { path: '~/Download/' + name } }, 'WRITE', `Создана папка: /storage/emulated/0/Download/${name}`, run);
        if (run !== state.run) return;
        await wait(500);
        const done = ok ? `Готово ✅ Папка **«${name}»** создана: \`/storage/emulated/0/Download/${name}\`. Что-нибудь положить внутрь?` : 'Хорошо, ничего не создаю.';
        if (!await stream(done, run)) return;
        emit({ type: 'assistant', step: 2, text: done, display: done });
      } else if (agent && list) {
        const intro = 'Смотрю содержимое «Загрузок».';
        if (!await stream(intro, run)) return;
        emit({ type: 'assistant', step: 1, text: intro, display: intro });
        await tool({ id: 'm' + Date.now(), tool: 'list_dir', args: { path: '~/Download' } }, 'READ',
          '/storage/emulated/0/Download — 4 элем.\n📁 привет/\n📄 invoice-2026-10.pdf (182.4 КБ)\n📄 photo_0412.jpg (3.1 МБ)\n📄 notes.txt (1.2 КБ)', run);
        if (run !== state.run) return;
        await wait(400);
        const done = 'В «Загрузках» 4 элемента: папка «привет», счёт invoice-2026-10.pdf, фото photo_0412.jpg и notes.txt.';
        if (!await stream(done, run)) return;
        emit({ type: 'assistant', step: 2, text: done, display: done });
      } else {
        const reply = agent
          ? 'Понял задачу. Опишите, что нужно сделать на устройстве — например, создать папку или найти файл, — и я выполню это через инструменты Relay.'
          : 'Конечно! Это обычный ответ в режиме чата — без действий на устройстве.';
        if (!await stream(reply, run)) return;
        emit({ type: 'assistant', step: 1, text: reply, display: reply });
      }
    } finally {
      if (run === state.run) emit({ type: 'turn', state: 'end' });
    }
  }

  async function handle(m) {
    switch (m.cmd) {
      case 'init':
        emit({
          type: 'init', adapters: await adapters(), settings: state.settings, device: state.device, mcp: state.mcp,
          lastAdapter: params.has('fresh') ? null : (params.get('adapter') || 'chatgpt'), version: '0.1.0 (demo)'
        });
        break;
      case 'selectAdapter': {
        const a = (await adapters()).find((x) => x.id === m.id);
        state.current = a;
        if (a.kind === 'app') {
          emit({ type: 'session', adapterId: a.id, state: a.installed ? (state.device.accessibility ? 'ready' : 'needsAccessibility') : 'notInstalled', url: 'app:' + a.package });
          emit({ type: 'chats', adapterId: a.id, items: [{ title: 'Текущий чат в приложении', href: 'app:' + a.package, kind: 'chat' }] });
          break;
        }
        await wait(500);
        emit({ type: 'session', adapterId: a.id, state: CHATS[a.id] ? 'ready' : 'login', url: a.url });
        emit({ type: 'chats', adapterId: a.id, items: CHATS[a.id] || [] });
        break;
      }
      case 'refreshChats':
        if (state.current) emit({ type: 'chats', adapterId: state.current.id, items: CHATS[state.current.id] || [] });
        break;
      case 'openChat':
        emit({ type: 'url', adapterId: state.current.id, url: m.href });
        break;
      case 'newChat':
        emit({ type: 'url', adapterId: state.current.id, url: state.current.newChatUrl || state.current.url });
        break;
      case 'send':
        turn(m.text, m.agent);
        break;
      case 'stop':
        state.run++;
        Object.values(state.approvals).forEach((r) => r('deny'));
        emit({ type: 'info', text: 'Остановлено' });
        emit({ type: 'turn', state: 'end' });
        break;
      case 'approve': {
        const r = state.approvals[m.callId];
        delete state.approvals[m.callId];
        if (r) r(m.decision);
        break;
      }
      case 'showSite':
        emit({ type: 'toast', text: 'В приложении здесь откроется сайт ' + (state.current ? state.current.name : '') });
        break;
      case 'pick':
        await wait(600);
        emit({ type: 'picked', kind: m.kind, selector: { input: '#prompt-textarea', send: 'button[data-testid="send-button"]', response: '[data-message-author-role="assistant"]', chatLink: 'a[href^="/c/"]' }[m.kind] || 'div.example', count: 3, sample: '' });
        break;
      case 'diagnose':
        emit({ type: 'diagnose', input: true, inputVia: 'selector', send: true, chats: 5, counts: { input: 1, response: 3, chatLink: 5 }, lastResponse: 'Здравствуйте! Чем помочь?' });
        break;
      case 'saveAdapter':
        state.custom[m.adapter.id] = m.adapter;
        emit({ type: 'adapters', adapters: await adapters() });
        emit({ type: 'toast', text: 'Сохранено' });
        break;
      case 'deleteAdapter':
        delete state.custom[m.id];
        emit({ type: 'adapters', adapters: await adapters() });
        break;
      case 'listApps':
        await wait(300);
        emit({
          type: 'apps', items: [
            { package: 'com.anthropic.claude', label: 'Claude' }, { package: 'com.hark.android', label: 'Hark Pro' },
            { package: 'com.openai.chatgpt', label: 'ChatGPT' }, { package: 'notion.id', label: 'Notion' },
            { package: 'org.telegram.messenger', label: 'Telegram' }
          ]
        });
        break;
      case 'setSetting':
        state.settings[m.key] = m.value;
        emit({ type: 'settings', settings: state.settings });
        break;
      case 'perm':
        if (m.which === 'accessibility') state.device.accessibility = true;
        if (m.which === 'storage') state.device.storage = true;
        emit(state.device);
        break;
      case 'checkRoot':
        emit(state.device);
        emit({ type: 'toast', text: 'Root-доступ получен' });
        break;
      case 'mcp':
        state.mcp.running = m.enabled;
        emit(state.mcp);
        break;
      case 'rotateToken':
        state.mcp.token = Math.random().toString(16).slice(2) + Math.random().toString(16).slice(2);
        emit(state.mcp);
        break;
      case 'copy':
        try { await navigator.clipboard.writeText(m.text); } catch (e) { /* ignore */ }
        emit({ type: 'toast', text: 'Скопировано' });
        break;
      case 'resendPreamble':
      case 'clearSites':
        emit({ type: 'toast', text: 'Готово' });
        break;
    }
  }

  window.RelayHost = { post: (json) => { handle(JSON.parse(json)); } };
})();
