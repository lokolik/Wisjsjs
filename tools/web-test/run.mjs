// Headless-Chromium checks for relay-core.js (against a fake chat site) and the Relay UI (with mock.js).
// Usage: npm install && npx playwright install chromium && node run.mjs   → screenshots in ./out
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets');
const outDir = path.join(here, 'out');
fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
const out = (name) => path.join(outDir, name);

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json' };
// /assets/* → app assets; every other path → the fake chat site (so /c/… links stay on one origin).
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://local');
  const file = url.pathname.startsWith('/assets/')
    ? path.join(assets, path.normalize(decodeURIComponent(url.pathname.slice(8))))
    : path.join(here, 'fixtures/fake-chat.html');
  if (!file.startsWith(assets) && !file.startsWith(here)) { res.writeHead(403); res.end(); return; }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const core = fs.readFileSync(path.join(assets, 'inject/relay-core.js'), 'utf8');

const browser = await chromium.launch();
let failures = 0;
async function test(name, fn) {
  try {
    await fn();
    console.log('✓', name);
  } catch (e) {
    failures++;
    console.log('✗', name, '\n   ', e.message.split('\n').slice(0, 8).join('\n    '));
  }
}

const CHAT_SELECTORS = {
  input: '#editor',
  send: 'button[data-testid="send-button"]',
  response: '[data-role="assistant"]',
  busy: 'button[aria-label="Stop streaming"]',
  chatLink: 'a[href^="/c/"]',
  projectLink: 'a[href^="/project/"]'
};

async function chatPage(mode, selectors, extra = {}) {
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  await page.goto(`${base}/chat${mode ? '?mode=' + mode : ''}`);
  await page.addScriptTag({ content: core });
  await page.evaluate(([s, x]) => window.__relay.configure(Object.assign({ selectors: s, stableMs: 900, timeoutMs: 20000 }, x)), [selectors, extra]);
  return page;
}

async function nextEvent(page, type, id, timeout = 15000) {
  const h = await page.waitForFunction(
    ([t, i]) => (window.__relayEvents || []).find((e) => e.type === t && (!i || e.id === i)),
    [type, id], { timeout });
  return h.jsonValue();
}

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

// ---------------- relay-core.js ----------------

await test('core: lists projects and chats via selectors', async () => {
  const page = await chatPage('', CHAT_SELECTORS);
  const chats = await page.evaluate(() => window.__relay.listChats());
  assert.deepEqual(chats.map((c) => [c.kind, c.title]), [['project', 'Проект «Работа»'], ['chat', 'Планы на неделю'], ['chat', 'Скрипт для бэкапа']]);
  assert.ok(chats.every((c) => c.href.startsWith(base)));
  await page.close();
});

await test('core: guesses the chat list without selectors', async () => {
  const page = await chatPage('', {});
  const chats = await page.evaluate(() => window.__relay.listChats());
  assert.deepEqual(chats.map((c) => c.title), ['Планы на неделю', 'Скрипт для бэкапа']);
  await page.close();
});

await test('core: sends into a contenteditable, streams partials, returns reply and code blocks', async () => {
  const page = await chatPage('', CHAT_SELECTORS);
  await page.evaluate(() => window.__relay.send('Создай папку «привет» в загрузках', 'r1'));
  const ev = await nextEvent(page, 'response', 'r1');
  assert.equal(ev.timeout, false);
  assert.match(ev.text, /Сейчас создам папку на вашем устройстве\./);
  assert.equal(ev.code.length, 1);
  assert.deepEqual(JSON.parse(ev.code[0]), { tool: 'mkdir', args: { path: '~/Download/привет' } });
  assert.deepEqual(await page.evaluate(() => window.__sent), ['Создай папку «привет» в загрузках']);
  const partials = await page.evaluate(() => window.__relayEvents.filter((e) => e.type === 'partial' && e.id === 'r1').length);
  assert.ok(partials >= 1, 'expected streamed partial events');
  assert.equal(await page.evaluate(() => document.getElementById('editor').innerText.trim()), '', 'input cleared after send');
  await page.close();
});

await test('core: multi-line preamble arrives intact', async () => {
  const page = await chatPage('', CHAT_SELECTORS);
  const prompt = '[Relay • инструкция для ИИ]\nВызывай инструменты так:\n```relay\n{"tool": "mkdir", "args": {"path": "~/x"}}\n```\n\nЗадача пользователя:\nсоздай папку тест';
  await page.evaluate((p) => window.__relay.send(p, 'r3'), prompt);
  await nextEvent(page, 'response', 'r3');
  const sent = (await page.evaluate(() => window.__sent))[0];
  for (const part of ['[Relay • инструкция для ИИ]', '{"tool": "mkdir", "args": {"path": "~/x"}}', 'создай папку тест']) {
    assert.ok(sent.includes(part), `missing in sent text: ${part}\n---\n${sent}`);
  }
  await page.close();
});

await test('core: generic mode (textarea + Enter, no selectors) reads the reply by page diff', async () => {
  const page = await chatPage('textarea', {});
  await page.evaluate(() => window.__relay.send('Просто ответь', 'r2'));
  const ev = await nextEvent(page, 'response', 'r2', 20000);
  assert.equal(ev.text, 'Готово, задача выполнена без инструментов.');
  assert.deepEqual(await page.evaluate(() => window.__sent), ['Просто ответь']);
  await page.close();
});

await test('core: second message waits for the new reply, not the previous one', async () => {
  const page = await chatPage('', CHAT_SELECTORS);
  await page.evaluate(() => window.__relay.send('Первое', 'a'));
  await nextEvent(page, 'response', 'a');
  await page.evaluate(() => window.__relay.send('Создай папку', 'b'));
  const ev = await nextEvent(page, 'response', 'b');
  assert.match(ev.text, /создам папку/);
  await page.close();
});

await test('core: attaches files through the site file input, waits for upload, then sends', async () => {
  const page = await chatPage('', CHAT_SELECTORS);
  const res = await page.evaluate((data) => window.__relay.attach([
    { name: 'отчёт.pdf', mime: 'application/pdf', b64: data.pdf },
    { name: 'notes.txt', mime: 'text/plain', b64: data.txt }
  ]), { pdf: b64('%PDF-1.4 fake'), txt: b64('hello') });
  assert.deepEqual(res, { ok: true, via: 'input', count: 2 });
  assert.deepEqual(await page.evaluate(() => window.__files), ['отчёт.pdf:13:application/pdf', 'notes.txt:5:text/plain']);
  // The send button is disabled while "uploading"; send() must wait for it.
  await page.evaluate(() => window.__relay.send('Что в файлах?', 'f1', { attachments: 2 }));
  const ev = await nextEvent(page, 'response', 'f1', 20000);
  assert.match(ev.text, /Готово/);
  assert.deepEqual(await page.evaluate(() => window.__sent), ['Что в файлах?']);
  await page.close();
});

await test('core: falls back to pasting files when the site has no file input', async () => {
  const page = await chatPage('paste', CHAT_SELECTORS);
  const res = await page.evaluate((d) => window.__relay.attach([{ name: 'photo.png', mime: 'image/png', b64: d }]), b64('png'));
  assert.equal(res.via, 'paste');
  assert.deepEqual(await page.evaluate(() => window.__files), ['photo.png:3:image/png']);
  await page.close();
});

await test('core: downloads files from the reply automatically', async () => {
  const page = await chatPage('', CHAT_SELECTORS, { autoDownload: true });
  await page.evaluate(() => window.__relay.send('Пришли файл с таблицей', 'd1'));
  const ev = await nextEvent(page, 'response', 'd1');
  assert.deepEqual(ev.files.map((f) => f.name), ['report.csv']);
  assert.deepEqual(await page.evaluate(() => window.__downloads), ['report.csv']);
  await page.close();
});

await test('core: picker turns taps into selectors', async () => {
  const page = await chatPage('', {});
  const cases = [
    ['input', '#editor', '#editor'],
    ['chatLink', 'nav a[href^="/c/bbbb"]', 'a[href^="/c/"]'],
    ['response', '.assistant p', 'div[data-role="assistant"]'],
    ['send', 'button[data-testid="send-button"]', 'button[data-testid="send-button"]']
  ];
  for (const [kind, target, expected] of cases) {
    await page.evaluate(() => { window.__relayEvents = []; });
    await page.evaluate((k) => window.__relay.pick(k), kind);
    await page.click(target);
    const ev = await nextEvent(page, 'picked');
    assert.equal(ev.kind, kind);
    assert.equal(ev.selector, expected, `${kind}: got ${ev.selector}`);
  }
  assert.deepEqual(await page.evaluate(() => window.__sent), [], 'picking must not trigger the site');
  assert.equal(await page.evaluate(() => document.getElementById('editor').innerText.trim()), '', 'temporary text removed after picking send');
  await page.close();
});

await test('core: diagnose reports what the selectors match', async () => {
  const page = await chatPage('', {});
  const d = await page.evaluate((s) => window.__relay.diagnose(s), CHAT_SELECTORS);
  assert.equal(d.input, true);
  assert.equal(d.inputVia, 'selector');
  assert.equal(d.send, true);
  assert.equal(d.counts.response, 1);
  assert.equal(d.chats, 3);
  assert.equal(d.lastResponse, 'Здравствуйте! Чем помочь?');
  await page.close();
});

await test('core: reports a clear error when there is no input (e.g. login page)', async () => {
  const page = await browser.newPage();
  await page.setContent('<main><h1>Sign in</h1><input type="password"></main>');
  await page.addScriptTag({ content: core });
  await page.evaluate(() => window.__relay.send('hi', 'e1'));
  const ev = await nextEvent(page, 'error', 'e1');
  assert.match(ev.message, /поле ввода/);
  await page.close();
});

// ---------------- UI (mock host) ----------------

async function uiPage(viewport, query, mobile = true) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, isMobile: mobile, hasTouch: mobile, locale: 'ru-RU' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(`${base}/assets/ui/index.html${query || ''}`);
  return { page, ctx, errors };
}
const PHONE = { width: 390, height: 844 };
const TABLET = { width: 1180, height: 820 };
const connected = (page) => page.waitForFunction(() => document.querySelector('#topSub').textContent.includes('подключено'));
const idle = (page) => page.waitForFunction(() => !window.relayUI._state.busy, null, { timeout: 20000 });

await test('ui: first run — wizard checks the phone, finds AI apps and auto-sets one up', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '?fresh=1');
  await page.waitForSelector('.sheet .wiz-steps');
  await page.waitForTimeout(400);
  await page.screenshot({ path: out('01-wizard-phone.png') });

  await page.click('[data-act="auto-setup"]');
  await page.waitForFunction(() => window.relayUI._state.device.accessibility === true);
  await page.click('[data-act="wiz-next"]');
  await page.waitForSelector('[data-wizapp]');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('02-wizard-apps.png') });
  assert.equal(await page.locator('[data-wizapp]').count(), 4, 'four installed AI apps');
  assert.ok(await page.locator('details.more [data-install]').count() >= 5, 'install buttons for missing apps');

  await page.click('[data-wizapp="app-hark"]');
  await page.click('[data-act="wiz-next"]');
  await page.click('[data-act="wiz-run"]');
  await page.waitForSelector('.cal-result.ok', { timeout: 15000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('03-wizard-autosetup.png') });
  assert.equal(await page.locator('.cal-step.done').count(), 7);

  await page.click('[data-act="wiz-finish"]');
  await connected(page);
  assert.equal(await page.locator('#topSub').innerText().then((t) => t.includes('Hark Pro')), true);
  await page.click('#menuBtn');
  await page.waitForSelector('.chat-item >> text=Переезд');
  await page.waitForTimeout(400);
  await page.screenshot({ path: out('04-app-chats.png') });
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: app — agent task with approval', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '');
  await connected(page);
  await page.fill('#input', 'Создай папку «привет» в Загрузках');
  await page.click('#sendBtn');
  await page.waitForSelector('.tool.ask [data-approve="once"]');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('05-approval.png') });
  await page.click('[data-approve="once"]');
  await page.waitForSelector('.tool .status.done');
  await idle(page);
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('06-done.png') });
  const texts = await page.locator('.msg.ai .bubble').allInnerTexts();
  assert.ok(texts.some((t) => t.includes('Готово')), 'final assistant message rendered');
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: long reply is collapsed, expands in place and opens in the reader', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '');
  await connected(page);
  await page.fill('#input', 'Расскажи подробно, как спланировать переезд');
  await page.click('#sendBtn');
  await page.waitForSelector('.msg.ai.collapsed .expand', { timeout: 20000 });
  await idle(page);
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('07-long-collapsed.png') });
  const collapsedH = await page.locator('.msg.ai.collapsed .bubble').evaluate((e) => e.getBoundingClientRect().height);
  assert.ok(collapsedH <= 345, `collapsed height ${collapsedH}`);
  await page.click('.msg.ai .expand');
  await page.waitForSelector('.msg.ai:not(.collapsed) .expand');
  const fullH = await page.locator('.msg.ai .bubble').last().evaluate((e) => e.getBoundingClientRect().height);
  assert.ok(fullH > collapsedH + 300, `expanded height ${fullH}`);
  await page.click('[data-read]');
  await page.waitForSelector('.sheet .reader');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('08-reader.png') });
  assert.match(await page.locator('.sheet .reader').innerText(), /После переезда/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: attach a file, AI returns a file that lands in Downloads', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '');
  await connected(page);
  await page.click('#attachBtn');
  await page.waitForSelector('.att-row .att-chip');
  await page.fill('#input', 'Сделай таблицу расходов из отчёта');
  await page.waitForTimeout(200);
  await page.screenshot({ path: out('09-attachment.png') });
  await page.click('#sendBtn');
  await page.waitForSelector('.files-card .file-row', { timeout: 15000 });
  await idle(page);
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('10-file-from-ai.png') });
  assert.equal(await page.locator('.msg.user .msg-files .att-chip').count(), 1);
  assert.equal(await page.locator('.att-row .att-chip').count(), 0, 'composer cleared');
  assert.match(await page.locator('.files-card').innerText(), /расходы-октябрь\.xlsx/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: services sheet lists installed apps first and offers to install the rest', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '');
  await connected(page);
  await page.click('#menuBtn');
  await page.click('#connBtn');
  await page.waitForSelector('.sheet .tile[data-pick="app-hark"]');
  await page.waitForTimeout(400);
  await page.screenshot({ path: out('11-services.png') });
  assert.equal(await page.locator('.sheet .tile .tile-ok').count(), 4);
  assert.equal(await page.locator('.sheet [data-app="com.quora.poe"]').count(), 1, 'discovered AI app');
  await page.click('.sheet details.more summary');
  await page.click('.sheet [data-install="com.google.android.apps.bard"]');
  await page.waitForSelector('.toast >> text=Google Play');
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: app that is not installed shows an install banner', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '?adapter=app-gemini');
  await page.waitForSelector('#banner [data-install="com.google.android.apps.bard"]');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('12-not-installed.png') });
  assert.match(await page.locator('#topSub').innerText(), /не установлено/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: settings — app section, files and MCP', async () => {
  const { page, ctx, errors } = await uiPage(PHONE, '');
  await connected(page);
  await page.click('#menuBtn');
  await page.click('#settingsBtn');
  await page.waitForSelector('.sheet [data-act="dump"]');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('13-settings-app.png') });
  await page.click('.sheet .switch:has([data-act="mcp"])');
  await page.waitForSelector('.sheet .codebox');
  assert.ok((await page.locator('.sheet .codebox').first().innerText()).includes('/mcp'));
  assert.equal(await page.locator('.sheet [data-toggle="autoDownload"]').isChecked(), true);
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: tablet — two-pane layout with app chats and a read-only tool', async () => {
  const { page, ctx, errors } = await uiPage(TABLET, '', true);
  await page.waitForSelector('.chat-item >> text=Работа');
  assert.ok(await page.locator('#sidebar').isVisible());
  await page.fill('#input', 'Покажи, что лежит в Загрузках');
  await page.click('#sendBtn');
  await page.waitForSelector('[data-approve="once"]');
  await page.click('[data-approve="once"]');
  await idle(page);
  await page.click('.tool details summary');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('14-tablet.png') });
  assert.deepEqual(errors, []);
  await ctx.close();
});

await test('ui: tablet — site editor with picker and diagnostics', async () => {
  const { page, ctx, errors } = await uiPage(TABLET, '?adapter=chatgpt', true);
  await page.waitForSelector('.chat-item');
  await page.click('#connBtn');
  await page.click('.tile[data-pick="chatgpt"] .tile-edit');
  await page.waitForSelector('.sheet [data-pickel="response"]');
  await page.click('.sheet [data-pickel="response"]');
  await page.waitForFunction(() => document.querySelector('.sheet [data-sel="response"]').value.includes('assistant'));
  await page.click('.sheet [data-act="diagnose"]');
  await page.waitForSelector('.sheet .diag');
  await page.waitForTimeout(300);
  await page.screenshot({ path: out('15-tablet-site-editor.png') });
  assert.deepEqual(errors, []);
  await ctx.close();
});

await browser.close();
server.close();
console.log(failures ? `\n${failures} test(s) failed` : '\nAll web checks passed');
process.exit(failures ? 1 : 0);
