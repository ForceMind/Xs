'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
let cards = [], occluded = false, dialog = false;
const location = {origin:'https://x.com', pathname:'/home', href:'https://x.com/home'};
const document = {
  visibilityState:'visible', documentElement:{},
  querySelector:()=>dialog?{}:null,
  querySelectorAll:()=>cards,
  elementFromPoint:(x,y)=>occluded?{}:cards.find(c=>x>=c.rect.left&&x<c.rect.right&&y>=c.rect.top&&y<c.rect.bottom),
  addEventListener(){},removeEventListener(){}
};
function card(id,rect,body='可见正文') {
  const c = {isConnected:true,rect,getBoundingClientRect:()=>rect,
    contains:hit=>hit===c,
    querySelectorAll:()=>[{href:'https://x.com/alice/status/'+id,closest:()=>c,querySelector:()=>({})}],
    querySelector:selector=>selector==='time'?{getAttribute:()=> '2026-11-01T00:00:00Z'}:{innerText:body}};
  return c;
}
const sandbox={location,document,URL,Map,Set,Number,Date,Math,innerWidth:400,innerHeight:800,
  getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),
  MutationObserver:class{observe(){}disconnect(){}},
  setInterval:()=>1,clearInterval(){},addEventListener(){},removeEventListener(){},
  requestAnimationFrame:fn=>fn()};
sandbox.window=sandbox;
vm.runInNewContext(fs.readFileSync('xs-runtime/src/main/assets/xs-observer.js','utf8'),sandbox);
const read=()=>sandbox.__xsObserver.read();
cards=[card('123',{left:0,top:0,right:400,bottom:5000,width:400,height:5000})];
let first=read();assert.equal(first.cards.length,1);assert.equal(first.cards[0].readableFraction,1);
const run=first.cards[0].run;
cards=[card('123',{left:0,top:0,right:400,bottom:5000,width:400,height:5000})];
assert.equal(read().cards[0].run,run,'DOM rebind preserves one continuous exposure');
occluded=true;assert.equal(read().cards.length,0);occluded=false;
assert.notEqual(read().cards[0].run,run,'Real interruption creates another interval');
cards=[card('124',{left:0,top:328,right:400,bottom:1128,width:400,height:800})];
assert.equal(read().cards.length,0,'59% long-card overlap is ineligible');
cards=[card('125',{left:0,top:320,right:400,bottom:1120,width:400,height:800})];
assert.equal(read().cards.length,1,'60% is eligible');
dialog=true;assert.equal(read().cards.length,0);dialog=false;
location.pathname='/messages';location.href='https://x.com/messages';assert.equal(read().cards.length,0);
location.pathname='/home';location.href='https://x.com/home';
cards=[card('126',{left:0,top:0,right:400,bottom:400,width:400,height:400},'"\n文'.repeat(16000)),
  card('127',{left:0,top:400,right:400,bottom:800,width:400,height:400},'"\n文'.repeat(16000))];
const bounded=read();assert.equal(bounded.cards.length,2);
assert.ok(Buffer.byteLength(JSON.stringify(JSON.stringify(bounded)),'utf8')<65536,'Escaped CJK/quote/newline transport stays bounded');
sandbox.__xsObserver.stop();assert.equal(read().cards.length,0);
console.log('PASS packaged DOM observer: long-card geometry, 59/60%, overlays, sensitive routes, rebind/reentry, bounded escaped payload');
