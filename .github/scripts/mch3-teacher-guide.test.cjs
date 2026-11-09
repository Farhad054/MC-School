const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const root = path.resolve(__dirname, '../../backend/src/main/resources/mcp');
const html = fs.readFileSync(path.join(root, 'teacher-guide-gallery.html'), 'utf8');
const compiled = new vm.Script(html.match(/<script>([\s\S]*?)<\/script>/)[1]);
const jpeg = 'data:image/jpeg;base64,' + fs.readFileSync(path.join(root, 'teacher-guide-images/answers-panel.jpg')).toString('base64');
const image = { id: 'answers-panel', title: 'Ответы', description: 'Панель решений' };
const result = { structuredContent: { version: 'test', images: [image] }, _meta: { 'mindcrafti/teacherGuideImages': { 'answers-panel': jpeg } } };
function element(tag = 'div') {
  return { tag, children: [], textContent: '', events: {}, attrs: {},
    append(...children) { this.children.push(...children); }, replaceChildren() { this.children = []; },
    addEventListener(type, callback) { this.events[type] = callback; }, setAttribute(k, v) { this.attrs[k] = v; },
    removeAttribute(k) { delete this[k]; }, showModal() { this.open = true; }, close() { this.open = false; } };
}
function environment(options = {}) {
  const elements = Object.fromEntries(['status', 'gallery', 'zoom', 'zoom-title', 'zoom-image', 'close'].map(id => [id, element()]));
  const events = {}, messages = [], timers = new Map(); let sequence = 0;
  const parent = { postMessage(message) { messages.push(message); } };
  const window = { parent, openai: options.openai, addEventListener(name, fn) { events[name] = fn; } };
  if (options.noHost) window.parent = window;
  const document = { getElementById: id => elements[id], createElement: element, body: { scrollHeight: 600 } };
  compiled.runInNewContext({ window, document, setTimeout(fn) { const id = ++sequence; timers.set(id, fn); return id; }, clearTimeout(id) { timers.delete(id); } });
  return { elements, events, messages, timers, window,
    deliver(params, source = parent) { events.message({ source, data: { jsonrpc: '2.0', method: 'ui/notifications/tool-result', params } }); },
    text() { const walk = el => [el.textContent, ...el.children.map(walk)].flat().join(' '); return Object.values(elements).map(walk).join(' '); } };
}
test('modern host delivers a visible image, load count and accessible enlargement', () => {
  const e = environment();
  assert.equal(e.messages[0].method, 'ui/initialize');
  e.deliver(result, {}); assert.equal(e.elements.gallery.children.length, 0);
  e.deliver(result); const button = e.elements.gallery.children[0].children[0];
  assert.equal(button.attrs['aria-label'], 'Увеличить: Ответы');
  assert.equal(button.children[0].src, jpeg);
  assert.match(e.elements.status.textContent, /0 из 1/);
  button.children[0].events.load(); assert.match(e.elements.status.textContent, /1 из 1/);
  button.events.click(); assert.equal(e.elements.zoom.open, true); assert.equal(e.elements['zoom-image'].src, jpeg);
  e.elements.close.events.click(); assert.equal(e.elements.zoom.open, false);
  assert.equal(e.timers.size, 0);
});
test('legacy metadata and canonical envelope both preserve image delivery', () => {
  for (const metadata of [result._meta, result]) {
    const e = environment({ openai: { toolOutput: result.structuredContent, toolResponseMetadata: metadata } });
    assert.equal(e.elements.gallery.children[0].children[0].children[0].src, jpeg);
  }
});
test('late globals and absent metadata give an honest visible state', () => {
  const e = environment(); e.deliver({ structuredContent: result.structuredContent });
  assert.match(e.text(), /Картинка не передана клиентом/); assert.match(e.elements.status.textContent, /0 из 1/);
  e.window.openai = { toolOutput: result.structuredContent, toolResponseMetadata: result._meta };
  e.events['openai:set_globals'](); assert.equal(e.elements.gallery.children[0].children[0].children[0].src, jpeg);
});
test('external URLs and SVG are not loaded and captions stay literal', () => {
  for (const source of ['https://example.test/private.jpg', 'data:image/svg+xml;base64,PHN2Zz4=', 'data:image/jpeg;base64,' + 'A'.repeat(700001)]) {
    const e = environment(); e.deliver({ structuredContent: { images: [{ ...image, title: '<script>bad</script>' }] }, _meta: { 'mindcrafti/teacherGuideImages': { 'answers-panel': source } } });
    assert.equal(e.elements.gallery.children[0].children.length, 1);
    assert.match(e.text(), /<script>bad<\/script>/); assert.match(e.text(), /Картинка не передана/);
  }
});
test('image failure and stale image loads never claim current gallery success', () => {
  const e = environment(); e.deliver(result); const old = e.elements.gallery.children[0].children[0].children[0];
  e.deliver({ structuredContent: { images: [] } }); old.events.load();
  assert.match(e.elements.status.textContent, /Не удалось получить/);
  e.deliver(result); const button = e.elements.gallery.children[0].children[0]; button.children[0].events.error();
  assert.equal(button.disabled, true); assert.match(e.elements.status.textContent, /Часть изображений недоступна/);
});
test('timeout and standalone view explain missing host instead of claiming screenshots were shown', () => {
  const e = environment(); [...e.timers.values()][0](); assert.match(e.elements.status.textContent, /Клиент не передал/);
  const alone = environment({ noHost: true }); assert.match(alone.elements.status.textContent, /поддержкой панелей/);
  assert.equal(alone.messages.length, 0);
});
