'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// Small generic DOM adapter; the same selectors and lifecycle are also exercised in actual WebViews.
class Element {
  constructor(tag,attrs={},text='') {
    this.tagName=tag.toUpperCase();this.attrs={...attrs};this.children=[];this.parentElement=null;this.innerText=text;
    this.rect={left:0,top:0,right:400,bottom:100,width:400,height:100};this.properties=new Map();
    const initial=this.attrs.style;delete this.attrs.style;
    this.style={getPropertyValue:k=>this.properties.get(k)?.[0]||'',getPropertyPriority:k=>this.properties.get(k)?.[1]||'',
      setProperty:(k,v,p='')=>{this.attrs.style='';this.properties.set(k,[v,p]);},removeProperty:k=>{this.properties.delete(k);}};
    if(initial!==undefined)this.setAttribute('style',initial);
  }
  append(...nodes){for(const node of nodes){if(node.parentElement)node.parentElement.remove(node);this.children.push(node);node.parentElement=this;}return this;}
  remove(node){this.children=this.children.filter(c=>c!==node);node.parentElement=null;}
  get isConnected(){let p=this;while(p.parentElement)p=p.parentElement;return p===document.documentElement;}
  hasAttribute(k){return Object.hasOwn(this.attrs,k);}
  getAttribute(k){if(k==='style')return this.hasAttribute(k)?[...this.properties].map(([n,[v,p]])=>n+': '+v+(p?' !'+p:'')+';').join(' '):null;return this.attrs[k]??null;}
  setAttribute(k,v){if(k==='style'){this.properties.clear();for(const prop of v.split(';')){const p=prop.split(':');if(p.length<2)continue;let value=p.slice(1).join(':').trim();const priority=/!important$/.test(value)?'important':'';value=value.replace(/\s*!important$/,'');this.properties.set(p[0].trim(),[value,priority]);}}this.attrs[k]=String(v);}
  removeAttribute(k){delete this.attrs[k];if(k==='style')this.properties.clear();}
  matches(selector){return selector.split(',').some(s=>{
    s=s.trim();const tag=s.match(/^[A-Za-z]+/);if(tag&&tag[0].toUpperCase()!==this.tagName)return false;
    const attrs=[...s.matchAll(/\[([\w-]+)(\*?=)?(?:"([^"]*)"|'([^']*)'|([^\]]*))?\]/g)];
    return attrs.every(a=>{const value=this.getAttribute(a[1]);if(!a[2])return value!==null;const expected=a[3]??a[4]??a[5];return value!==null&&(a[2]==='*='?value.includes(expected):value===expected);});
  });}
  querySelectorAll(selector){const found=[];for(const child of this.children){if(child.matches(selector))found.push(child);found.push(...child.querySelectorAll(selector));}return found;}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
  contains(node){while(node){if(node===this)return true;node=node.parentElement;}return false;}
  closest(selector){let node=this;while(node){if(node.matches(selector))return node;node=node.parentElement;}return null;}
  get href(){return new URL(this.attrs.href,location.href).href;}
  getBoundingClientRect(){let p=this;while(p){if(p.style.getPropertyValue('display')==='none')return {left:0,top:0,right:0,bottom:0,width:0,height:0};p=p.parentElement;}return this.rect;}
}
const listeners=new Map(),timers=new Map();let timerSerial=0,mutationCallback,observerConnected=false;
const location={origin:'https://x.com',pathname:'/home',href:'https://x.com/home'};
const document={visibilityState:'visible',documentElement:new Element('html'),
  querySelectorAll:s=>document.documentElement.querySelectorAll(s),querySelector:s=>document.documentElement.querySelector(s),
  addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:(name,fn)=>{if(listeners.get(name)===fn)listeners.delete(name);},
  elementFromPoint:(x,y)=>document.querySelectorAll('article').find(a=>{const r=a.getBoundingClientRect();return r.width&&x>=r.left&&x<r.right&&y>=r.top&&y<r.bottom;})||null};
const body=new Element('body');document.documentElement.append(body);
const sandbox={location,document,URL,Map,Set,Object,Number,Date,Math,innerWidth:400,innerHeight:800,
  getComputedStyle:n=>({display:n.style.getPropertyValue('display')||'block',visibility:'visible',opacity:'1'}),
  MutationObserver:class{constructor(fn){mutationCallback=fn;}observe(){observerConnected=true;}disconnect(){observerConnected=false;}},
  setInterval:fn=>{timers.set(++timerSerial,fn);return timerSerial;},clearInterval:id=>timers.delete(id),
  requestAnimationFrame:fn=>fn(),addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:(name,fn)=>{if(listeners.get(name)===fn)listeners.delete(name);},scrollBy:()=>{}};
sandbox.window=sandbox;sandbox.top=sandbox;
const run=()=>vm.runInNewContext(fs.readFileSync('xs-runtime/src/main/assets/xs-cleanup.js','utf8'),sandbox);
const options=(ads=false,subscription=false,recommendations=false)=>({ads,subscription,recommendations});
const setRoute=path=>{location.pathname=path;location.href=location.origin+path;};
const mutate=()=>{if(observerConnected)mutationCallback();};
function article(id,text='visible content'){
  return new Element('article',{'data-testid':'tweet'}).append(
    new Element('a',{href:'/alice/status/'+id}).append(new Element('time',{datetime:'2026-11-01T00:00:00Z'})),
    new Element('div',{'data-testid':'tweetText'},text));
}
function ad(id){return new Element('div',{'data-testid':'placementTracking'}).append(article(id).append(new Element('span',{'data-testid':'promotedIndicator'})));}
function premium(){return new Element('aside',{role:'complementary','data-testid':'premiumPromo',style:'display: grid !important; color: green;'}).append(new Element('h2',{'data-testid':'premiumPromoTitle'}),new Element('a',{href:'/i/premium_sign_up'}));}
function recommendations(){return new Element('aside',{role:'complementary','data-testid':'whoToFollow'}).append(new Element('h2',{'data-testid':'whoToFollowHeading'}),new Element('div',{'data-testid':'UserCell'}).append(new Element('a',{href:'/bob'})));}
const sponsored=ad('1'),normal=article('2'),upsell=premium(),follow=recommendations(),unknown=new Element('aside',{'data-testid':'premiumPromo'}).append(new Element('p',{},'premium marketing words only'));
body.append(sponsored,normal,upsell,follow,unknown);run();let cleanup=sandbox.__xsCleanup;
assert.equal(cleanup.status().hidden,0);assert.equal(observerConnected,false);assert.equal(timers.size,0);
assert.equal(cleanup.apply(options(true)).hidden,1);assert.equal(sponsored.style.getPropertyValue('display'),'none');assert.equal(normal.style.getPropertyValue('display'),'');assert.equal(upsell.style.getPropertyValue('display'),'grid');
assert.equal(cleanup.apply(options(false,true)).hidden,1);assert.equal(sponsored.hasAttribute('style'),false);assert.equal(upsell.style.getPropertyValue('display'),'none');
upsell.style.setProperty('color','blue');mutate();cleanup.apply(options());assert.equal(upsell.style.getPropertyValue('display'),'grid');assert.equal(upsell.style.getPropertyPriority('display'),'important');assert.equal(upsell.style.getPropertyValue('color'),'blue');
cleanup.apply(options(false,true));upsell.style.setProperty('display','flex');mutate();assert.equal(upsell.style.getPropertyValue('display'),'none');cleanup.restore();assert.equal(upsell.style.getPropertyValue('display'),'flex');
assert.equal(cleanup.apply(options(true,true,true)).hidden,3);assert.equal(unknown.style.getPropertyValue('display'),'');cleanup.restore();assert.equal(cleanup.status().hidden,0);assert.equal(timers.size,0);
const marker=sponsored.children[0].querySelector('[data-testid="promotedIndicator"]');cleanup.apply(options(true));sponsored.children[0].remove(marker);mutate();assert.equal(sponsored.hasAttribute('style'),false,'Reused card with no promoted marker is restored');sponsored.children[0].append(marker);
const paid= new Element('span',{'data-testid':'paidFeature'});upsell.append(paid);assert.equal(cleanup.apply(options(false,true)).hidden,0,'Paid entitlements are not promotions');upsell.remove(paid);
for(const path of ['/login','/settings/security','/messages','/compose/post','/i/premium_sign_up','/unknown']){setRoute('/home');cleanup.apply(options(true,true,true));setRoute(path);listeners.get('popstate')();assert.equal(cleanup.status().hidden,0,path);}
setRoute('/home');cleanup.apply(options(true,true,true));const password=new Element('input',{type:'password'});body.append(password);mutate();assert.equal(cleanup.status().hidden,0,'Sensitive same-route SPA overlay restores everything');body.remove(password);
const frame=new Element('iframe');cleanup.apply(options(true,true,true));body.append(frame);mutate();assert.equal(cleanup.status().hidden,0,'Unknown embedded frames fail closed');body.remove(frame);
cleanup.apply(options(true,true,true));document.visibilityState='hidden';listeners.get('visibilitychange')();assert.equal(cleanup.status().hidden,0);assert.equal(timers.size,0);document.visibilityState='visible';listeners.get('visibilitychange')();assert.equal(cleanup.status().hidden,3);
cleanup.restore();for(const node of [...body.children])body.remove(node);for(let i=0;i<220;i++)body.append(ad(String(1000+i)));
assert.equal(cleanup.apply(options(true)).hidden,128);assert.equal(cleanup.status().capped,true);cleanup.stop();assert.equal(cleanup.status().hidden,0);assert.equal(observerConnected,false);assert.equal(timers.size,0);assert.equal(listeners.size,0);
// Real observer integration: a hidden advertisement cannot acquire a feed exposure run.
for(const node of [...body.children])body.remove(node);
const visible=article('700'),hidden=ad('701');visible.rect={left:0,top:0,right:400,bottom:400,width:400,height:400};hidden.children[0].rect={left:0,top:400,right:400,bottom:800,width:400,height:400};body.append(visible,hidden);run();sandbox.__xsCleanup.apply(options(true));
vm.runInNewContext(fs.readFileSync('xs-runtime/src/main/assets/xs-observer.js','utf8'),sandbox);
assert.equal(sandbox.__xsObserver.read().cards.length,1);assert.match(sandbox.__xsObserver.read().cards[0].url,/700$/);
sandbox.__xsObserver.stop();sandbox.__xsCleanup.stop();
console.log('PASS reversible cleanup: independent defaults, exact structures, restore/site edits, paid/sensitive exclusion, rebind, visibility, 128-node cap, observer exclusion');
