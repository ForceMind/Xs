'use strict';
// Deterministic virtual-list/lifecycle stress, with the exact packaged adapter in a VM.
// Wall time is diagnostic only. Limits below are protocol/work bounds, not machine speed.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {performance} = require('node:perf_hooks');
let nodes = [], blocked = false, styleReads = 0, geometryReads = 0, bodyReads = 0, maxRuns = 0, next = 0;
const timers = new Map(), frames = new Map(), listeners = new Map();
let mutation;
const event = (name) => { for (const fn of [...(listeners.get(name) || [])]) fn(); };
const on = (name, fn) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); };
const off = (name, fn) => listeners.get(name)?.delete(fn);
const location = {origin:'https://x.com', pathname:'/home', href:'https://x.com/home'};
const document = {
  visibilityState:'visible', documentElement:{},
  querySelector:() => blocked ? {} : null,
  querySelectorAll:() => nodes,
  elementFromPoint:(x,y) => nodes.find(c => c.isConnected && x >= c.rect.left && x < c.rect.right && y >= c.rect.top && y < c.rect.bottom),
  addEventListener:on, removeEventListener:off
};
function card(id, slot, long = false) {
  const rect = {left:0, top:slot * 200, right:400, bottom:long ? 5000 : slot * 200 + 200, width:400, height:long ? 5000 : 200};
  const text = {get innerText() { bodyReads++; return 'bounded visible text '.repeat(300); }};
  const c = {isConnected:true, rect, parentElement:null, getBoundingClientRect:() => {geometryReads++; return rect;}, contains:hit => hit === c,
    querySelectorAll:() => [{href:`https://x.com/author/status/${id}`,closest:() => c,querySelector:() => ({})}],
    querySelector:s => s === 'time' ? {getAttribute:() => '2026-11-01T00:00:00Z'} : text};
  return c;
}
class TrackedMap extends Map { set(k,v) { super.set(k,v); maxRuns = Math.max(maxRuns, this.size); return this; } }
const sandbox = {location,document,URL,Map:TrackedMap,Set,Number,Date,Math,innerWidth:400,innerHeight:800,
  getComputedStyle:el => { styleReads++; return {display:'block',visibility:'visible',opacity:el.opacity || '1'}; },
  MutationObserver:class {constructor(fn) {this.fn = fn; mutation = this;} observe() {this.active = true;} disconnect() {this.active = false;}},
  setInterval:fn => {const id = ++next; timers.set(id,fn); return id;}, clearInterval:id => timers.delete(id),
  requestAnimationFrame:fn => {const id = ++next; frames.set(id,fn); return id;}, cancelAnimationFrame:id => frames.delete(id),
  addEventListener:on, removeEventListener:off};
sandbox.window = sandbox;
vm.runInNewContext(fs.readFileSync('xs-runtime/src/main/assets/xs-observer.js','utf8'),sandbox);
const read = () => sandbox.__xsObserver.read();
const tick = () => {for (const [id,fn] of [...frames]) {frames.delete(id); fn();} for (const fn of [...timers.values()]) fn();};
const start = performance.now();
let lastRun = null, maxPayload = 0;
for (let batch = 0; batch < 5000; batch++) {
  // Four reused viewport slots traverse 20,000 post identities; the page retains four nodes.
  nodes = Array.from({length:4}, (_,slot) => card(batch * 4 + slot + 1,slot));
  mutation.fn([]); tick();
  const result = read();
  assert.equal(result.cards.length,4);
  assert.notEqual(result.cards[0].run,lastRun); lastRun = result.cards[0].run;
  assert.ok(result.cards.reduce((n,c) => n + c.body.length,0) <= 6000);
  maxPayload = Math.max(maxPayload,Buffer.byteLength(JSON.stringify(JSON.stringify(result))));
  // A redraw of the same visible identity retains the run, not a second exposure.
  nodes = Array.from({length:4}, (_,slot) => card(batch * 4 + slot + 1,slot));
  assert.equal(read().cards[0].run,lastRun);
}
assert.ok(maxRuns <= 8,'one old and one current viewport are bounded during rebind; old keys are pruned');
assert.ok(maxPayload < 65536);
const beforeBody = bodyReads;
for (let i = 0; i < 1000; i++) tick();
assert.equal(bodyReads,beforeBody,'periodic geometry checks never materialize text');
for (let i = 0; i < 1000; i++) {
  blocked = true; mutation.fn([]); tick(); assert.equal(read().cards.length,0);
  blocked = false; assert.notEqual(read().cards[0].run,lastRun); lastRun = read().cards[0].run;
  document.visibilityState = 'hidden'; event('visibilitychange');
  assert.equal(timers.size,0); assert.equal(frames.size,0); assert.equal(read().cards.length,0);
  document.visibilityState = 'visible'; event('visibilitychange'); tick();
  event('freeze'); assert.equal(timers.size,0); assert.equal(read().cards.length,0);
  event('resume'); tick();
  event('pagehide'); assert.equal(read().cards.length,0); event('pageshow'); tick();
  location.pathname = '/messages'; location.href = 'https://x.com/messages'; event('popstate'); tick();
  assert.equal(read().cards.length,0);
  location.pathname = '/home'; location.href = 'https://x.com/home'; event('popstate'); tick();
}
nodes = [card('900000',0,true)]; assert.equal(read().cards[0].readableFraction,1);
nodes[0].parentElement = {opacity:'0'}; assert.equal(read().cards.length,0,'transparent ancestors are not readable');
nodes[0].parentElement = null;
const stable = read().cards[0].run;
nodes[0].isConnected = false; assert.equal(read().cards.length,0);
nodes[0].isConnected = true; assert.notEqual(read().cards[0].run,stable);
nodes = Array.from({length:120}, () => card('900001',0));
assert.equal(read().cards.length,1,'duplicate IDs cannot multiply exposure events');
// Deliberately large attached selector result: JS examines at most 96 article identities.
nodes = Array.from({length:20000}, (_,slot) => card(910000 + slot,slot));
const beforeGeometry = geometryReads, beforeStyles = styleReads; read();
const articleGeometryWork = geometryReads - beforeGeometry, styleWork = styleReads - beforeStyles;
assert.ok(articleGeometryWork <= 96,'at most 96 candidate-article geometry checks');
// This fixture has depth-one articles and four readable cards. Each candidate needs
// one style read, then each readable body needs one additional privacy style check.
// The deliberately minimal text nodes have no rect API, so evidence is conservatively null.
assert.ok(styleWork <= 96 + 4,'96 article styles plus four visible-body privacy checks');
mutation.fn([]); assert.equal(frames.size,1);
sandbox.__xsObserver.stop();
assert.equal(frames.size,0); assert.equal(timers.size,0); assert.equal(mutation.active,false);
assert.equal([...listeners.values()].reduce((n,set) => n + set.size,0),0);
const stoppedWork = styleReads; event('scroll'); tick(); read(); assert.equal(styleReads,stoppedWork);
console.log(JSON.stringify({result:'PASS',virtualPosts:20000,lifecycleCycles:1000,maxActiveRuns:maxRuns,
  maxEscapedPayloadBytes:maxPayload,geometryOnlyBodyReads:0,
  largeAttachedArticleGeometryReads:articleGeometryWork,largeAttachedStyleReads:styleWork,
  elapsedMs:Math.round(performance.now()-start)},null,2));
