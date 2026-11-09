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
  const fresh = params.has('fresh');
  const ROOT = '/storage/emulated/0';

  const INSTALLED = { 'app-chatgpt': '1.2026.301', 'app-claude': '1.251020.4', 'app-hark': '2.3.1', 'app-notion': '0.6.2401' };
  const PROFILE_CHATS = {
    'app-chatgpt': [
      { title: 'Работа', kind: 'project' }, { title: 'Учёба', kind: 'project' },
      { title: 'Скрипт для бэкапа фото', kind: 'chat' }, { title: 'План поездки в Казань', kind: 'chat' },
      { title: 'Разбор договора аренды', kind: 'chat' }, { title: 'Идеи для Notion-шаблона', kind: 'chat' }
    ],
    'app-hark': [
      { title: 'Переезд', kind: 'project' }, { title: 'Поиск работы', kind: 'project' }
    ]
  };
  const SITE_CHATS = {
    chatgpt: [
      { title: 'Работа', href: 'https://chatgpt.com/g/g-p-work/project', kind: 'project' },
      { title: 'Скрипт для бэкапа фото', href: 'https://chatgpt.com/c/a1', kind: 'chat' },
      { title: 'План поездки в Казань', href: 'https://chatgpt.com/c/a2', kind: 'chat' }
    ]
  };
  const LONG_REPLY = [
    'Вот подробный план переезда в новую квартиру — по неделям, чтобы ничего не забыть.',
    '**За месяц до переезда**\nСоставьте список вещей по комнатам и решите, что продать, отдать или выбросить. Закажите грузчиков и машину заранее: в конце месяца их разбирают быстрее всего. Уточните у управляющей компании, нужен ли пропуск для грузовой машины и можно ли пользоваться грузовым лифтом.',
    '**За три недели**\nНачните упаковывать то, чем пользуетесь редко: книги, сезонную одежду, посуду для гостей. Подпишите каждую коробку — комната и короткий список содержимого. Сфотографируйте, как подключена техника, чтобы потом собрать всё так же.',
    '**За две недели**\nПереоформите интернет и коммунальные услуги, подайте заявление на смену адреса в банке, на почте и в сервисах доставки. Проверьте счётчики в новой квартире и сделайте фото показаний.',
    '**За неделю**\nРазберите холодильник, разморозьте морозилку за два дня до переезда. Отдельно соберите «коробку первого дня»: зарядки, документы, лекарства, постельное бельё, чайник, кружки, туалетные принадлежности, инструменты.',
    '**В день переезда**\nПроверьте, что всё погружено, снимите финальные показания счётчиков, сфотографируйте пустую квартиру. В новой квартире сначала расставьте крупную мебель, потом коробки по комнатам.',
    '**После переезда**\nОбновите адрес в госуслугах и страховке, проверьте почтовый ящик, познакомьтесь с соседями. Распакуйте сначала кухню и спальню — остальное спокойно разберёте за неделю.',
    'Если хотите, я сохраню этот план в файл «Переезд.md» в Документы — скажите «сохрани».'
  ].join('\n\n');

  const state = {
    adapters: null,
    custom: {},
    calibrated: fresh ? {} : { 'app-chatgpt': true },
    chats: fresh ? {} : { 'app-chatgpt': PROFILE_CHATS['app-chatgpt'] },
    current: null,
    settings: { approvalMode: 'ask', rootMode: 'auto', autoPreamble: true, maxSteps: 12, mcpEnabled: false, mcpPort: 8765, autoDownload: true },
    device: fresh
      ? { type: 'device', suPresent: true, root: null, storage: false, accessibility: false, sdk: 35, model: 'Google Pixel 9 Pro', storageRoot: ROOT }
      : { type: 'device', suPresent: true, root: true, storage: true, accessibility: true, sdk: 35, model: 'Google Pixel 9 Pro', storageRoot: ROOT },
    mcp: { type: 'mcp', running: false, url: 'http://192.168.1.42:8765/mcp', port: 8765, token: '7f3c9a1e5b2d48f0a6c1e9d3b7a2f4c8' },
    staged: [],
    approvals: {},
    run: 0
  };

  async function base() {
    if (!state.adapters) {
      try {
        state.adapters = (await (await fetch('../adapters.json')).json()).adapters;
      } catch (e) {
        state.adapters = [];
      }
    }
    return state.adapters;
  }

  async function adapters() {
    const list = (await base()).map((a) => Object.assign({ builtin: true }, a, state.custom[a.id] ? Object.assign({ overridden: true }, state.custom[a.id]) : {}))
      .concat(Object.values(state.custom).filter((c) => !state.adapters.some((a) => a.id === c.id)).map((c) => Object.assign({ custom: true }, c)));
    list.forEach((a) => {
      if (a.kind !== 'app') return;
      a.package = a.package || (a.packages || [])[0];
      a.installed = a.custom ? true : !!INSTALLED[a.id];
      a.version = INSTALLED[a.id] || (a.custom ? '1.0' : '');
      a.calibrated = !!state.calibrated[a.id];
    });
    return list;
  }

  const discovered = () => [{ package: 'com.quora.poe', label: 'Poe – Fast AI Chat' }];
  const adaptersEvent = async () => ({ type: 'adapters', adapters: await adapters(), discovered: discovered() });

  function appState(a) {
    if (!a.installed) return 'notInstalled';
    if (!state.device.accessibility) return 'needsAccessibility';
    if (!state.calibrated[a.id]) return 'needsSetup';
    return 'ready';
  }

  function appChats(a) {
    const home = 'app:' + a.package;
    return [{ title: 'Открытый чат в приложении', href: home, kind: 'chat' }]
      .concat((state.chats[a.id] || []).map((c) => ({ title: c.title, href: home + '#chat:' + c.title, kind: c.kind })));
  }

  async function select(id) {
    const a = (await adapters()).find((x) => x.id === id);
    if (!a) return;
    state.current = a;
    if (a.kind === 'app') {
      emit({ type: 'session', adapterId: a.id, state: appState(a), url: 'app:' + a.package, title: a.name });
      emit({ type: 'chats', adapterId: a.id, items: appChats(a), updatedAt: state.chats[a.id] ? Date.now() - 600000 : 0 });
      return;
    }
    await wait(500);
    emit({ type: 'session', adapterId: a.id, state: SITE_CHATS[a.id] ? 'ready' : 'login', url: a.url });
    emit({ type: 'chats', adapterId: a.id, items: SITE_CHATS[a.id] || [] });
  }

  async function calibrate(id, test) {
    const a = (await adapters()).find((x) => x.id === id);
    const step = async (key, detail, ms = 500) => {
      emit({ type: 'calib', adapterId: id, step: key, status: 'running', detail: '' });
      await wait(ms);
      emit({ type: 'calib', adapterId: id, step: key, status: 'done', detail });
    };
    await step('open', 'Приложение открыто', 700);
    await step('input', 'Поле ввода: «Сообщение»', 300);
    await step('send', 'Кнопка: «Отправить»', 600);
    await step('list', 'Лента сообщений найдена — длинные ответы дочитываю прокруткой', 300);
    state.chats[id] = PROFILE_CHATS[id] || [{ title: 'Мой первый чат', kind: 'chat' }];
    await step('chats', 'Чатов и проектов: ' + state.chats[id].length, 900);
    await step('newchat', 'Кнопка нового чата найдена', 300);
    if (test) await step('test', 'Ответ получен: «Готово»', 1200);
    state.calibrated[id] = true;
    emit({ type: 'calibDone', adapterId: id, ok: true, message: a.name + ' настроено' });
    emit(await adaptersEvent());
    if (state.current && state.current.id === id) await select(id);
  }

  async function stream(text, run) {
    const words = text.split(' ');
    let acc = '';
    for (let i = 0; i < words.length; i++) {
      if (run !== state.run) return false;
      acc += (i ? ' ' : '') + words[i];
      if (i % 4 === 3 || i === words.length - 1) emit({ type: 'partial', text: acc });
      await wait(words.length > 80 ? 8 : 45);
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

  async function say(text, step, run) {
    if (!await stream(text, run)) return false;
    emit({ type: 'assistant', step, text, display: text });
    return true;
  }

  async function turn(text, agent, files) {
    const run = ++state.run;
    emit({ type: 'turn', state: 'start' });
    await wait(700);
    try {
      if (agent && files.length && /таблиц|xlsx|excel/i.test(text)) {
        if (!await say(`Прочитал «${files[0].name}» и собрал таблицу расходов по категориям. Файл готов: расходы-октябрь.xlsx — нажмите, чтобы скачать.`, 1, run)) return;
        emit({ type: 'info', text: `Скачиваю из ${state.current.name}: расходы-октябрь.xlsx` });
        await wait(900);
        emit({ type: 'files', source: 'download', items: [{ path: ROOT + '/Download/расходы-октябрь.xlsx', name: 'расходы-октябрь.xlsx', size: 18432, mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }] });
      } else if (/подробн|длинн|расскажи/i.test(text)) {
        await say(LONG_REPLY, 1, run);
      } else if (agent && /папк|folder/i.test(text)) {
        const name = (text.match(/[«"„']([^»"“']+)[»"“']/) || [])[1] || 'привет';
        if (!await say(`Создаю папку «${name}» в «Загрузках».`, 1, run)) return;
        const ok = await tool({ id: 'm' + Date.now(), tool: 'mkdir', args: { path: '~/Download/' + name } }, 'WRITE', `Создана папка: ${ROOT}/Download/${name}`, run);
        if (run !== state.run) return;
        await wait(500);
        await say(ok ? `Готово ✅ Папка **«${name}»** создана: \`${ROOT}/Download/${name}\`. Что-нибудь положить внутрь?` : 'Хорошо, ничего не создаю.', 2, run);
      } else if (agent && /что (лежит|есть)|покажи|список/i.test(text)) {
        if (!await say('Смотрю содержимое «Загрузок».', 1, run)) return;
        await tool({ id: 'm' + Date.now(), tool: 'list_dir', args: { path: '~/Download' } }, 'READ',
          ROOT + '/Download — 4 элем.\n📁 привет/\n📄 invoice-2026-10.pdf (182.4 КБ)\n📄 photo_0412.jpg (3.1 МБ)\n📄 notes.txt (1.2 КБ)', run);
        if (run !== state.run) return;
        await wait(400);
        await say('В «Загрузках» 4 элемента: папка «привет», счёт invoice-2026-10.pdf, фото photo_0412.jpg и notes.txt.', 2, run);
      } else {
        await say(agent
          ? 'Понял задачу. Опишите, что нужно сделать на устройстве — например, создать папку или найти файл, — и я выполню это через инструменты Relay.'
          : 'Конечно! Это обычный ответ в режиме чата — без действий на устройстве.', 1, run);
      }
    } finally {
      if (run === state.run) emit({ type: 'turn', state: 'end' });
    }
  }

  async function handle(m) {
    switch (m.cmd) {
      case 'init':
        emit({
          type: 'init', adapters: await adapters(), discovered: discovered(), settings: state.settings, device: state.device, mcp: state.mcp,
          onboarded: !fresh, attachments: state.staged,
          lastAdapter: fresh ? null : (params.get('adapter') || 'app-chatgpt'), version: '0.2.0 (demo)'
        });
        break;
      case 'scanApps':
        emit(await adaptersEvent());
        break;
      case 'selectAdapter':
        await select(m.id);
        break;
      case 'refreshChats':
        if (!state.current) break;
        if (state.current.kind === 'app') {
          emit({ type: 'toast', text: `Открываю ${state.current.name} и читаю список чатов…` });
          await wait(1200);
          emit({ type: 'chats', adapterId: state.current.id, items: appChats(state.current), updatedAt: Date.now() });
        } else {
          emit({ type: 'chats', adapterId: state.current.id, items: SITE_CHATS[state.current.id] || [] });
        }
        break;
      case 'openChat':
        emit({ type: 'url', adapterId: state.current.id, url: m.href });
        break;
      case 'newChat':
        emit({ type: 'url', adapterId: state.current.id, url: state.current.kind === 'app' ? 'app:' + state.current.package + '#new' : (state.current.newChatUrl || state.current.url) });
        break;
      case 'calibrate':
        calibrate(m.id, m.test);
        break;
      case 'send': {
        const files = state.staged.filter((f) => (m.attachments || []).includes(f.id));
        state.staged = state.staged.filter((f) => !(m.attachments || []).includes(f.id));
        emit({ type: 'attachments', items: state.staged });
        turn(m.text, m.agent, files);
        break;
      }
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
      case 'pickFiles':
        state.staged.push({ id: 'f' + Date.now(), name: 'Отчёт за октябрь.pdf', size: 248000, mime: 'application/pdf' });
        emit({ type: 'attachments', items: state.staged });
        break;
      case 'removeAttachment':
        state.staged = state.staged.filter((f) => f.id !== m.id);
        emit({ type: 'attachments', items: state.staged });
        break;
      case 'openFile':
      case 'shareFile':
        emit({ type: 'toast', text: (m.cmd === 'openFile' ? 'Открываю ' : 'Поделиться: ') + m.path.split('/').pop() });
        break;
      case 'installApp':
        emit({ type: 'toast', text: 'Открываю Google Play: ' + m.package });
        break;
      case 'showSite':
        emit({ type: 'toast', text: state.current && state.current.kind === 'app' ? 'Открываю ' + state.current.name : 'В приложении здесь откроется сайт' });
        break;
      case 'dumpScreen':
        emit({ type: 'files', source: 'dump', items: [{ path: ROOT + '/Download/relay-screen-app-chatgpt.json', name: 'relay-screen-app-chatgpt.json', size: 48211, mime: 'application/json' }] });
        break;
      case 'resetSetup':
        delete state.calibrated[m.id];
        emit(await adaptersEvent());
        if (state.current && state.current.id === m.id) await select(m.id);
        break;
      case 'finishOnboarding':
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
        emit(await adaptersEvent());
        emit({ type: 'toast', text: 'Сохранено' });
        break;
      case 'deleteAdapter':
        delete state.custom[m.id];
        emit(await adaptersEvent());
        break;
      case 'listApps':
        await wait(300);
        emit({ type: 'apps', items: [{ package: 'com.quora.poe', label: 'Poe' }, { package: 'org.telegram.messenger', label: 'Telegram' }] });
        break;
      case 'setSetting':
        state.settings[m.key] = m.value;
        emit({ type: 'settings', settings: state.settings });
        break;
      case 'perm':
        if (m.which === 'accessibility') state.device.accessibility = true;
        if (m.which === 'storage') state.device.storage = true;
        emit(state.device);
        if (state.current) await select(state.current.id);
        break;
      case 'autoSetup':
        await wait(600);
        Object.assign(state.device, { root: true, storage: true, accessibility: true });
        emit(state.device);
        emit({ type: 'toast', text: 'Всё настроено через root' });
        break;
      case 'checkRoot':
        state.device.root = true;
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
