import fs from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const html = fs.readFileSync(new URL('app/src/main/assets/index.html', root), 'utf8');
let code = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n');
const close = code.lastIndexOf('})();');
code = code.slice(0, close) + 'window.__gpsTest={TRACK,state,rt,onPos,onErr,tickerStep,metersOf,currentMeters};' + code.slice(close);

function element(){
  const classes=new Set();
  return {style:{},dataset:{},value:'',textContent:'',innerHTML:'',className:'',
    classList:{add:(...x)=>x.forEach(v=>classes.add(v)),remove:(...x)=>x.forEach(v=>classes.delete(v)),toggle:(x,v)=>v?(classes.add(x),true):(classes.delete(x),false),contains:x=>classes.has(x)},
    addEventListener(){},removeEventListener(){},setAttribute(){},getAttribute(){return null},querySelector(){return element()},querySelectorAll(){return[]},closest(){return null},scrollIntoView(){},click(){}};
}
const elements=new Map(), el=s=>{if(!elements.has(s))elements.set(s,element());return elements.get(s)};
const storage=new Map();
const document={querySelector:el,querySelectorAll:()=>[],addEventListener(){},removeEventListener(){},createElement:element,body:element(),documentElement:element(),visibilityState:'visible'};
const navigator={geolocation:{watchPosition(){return 1},clearWatch(){},getCurrentPosition(){}},wakeLock:{request(){return Promise.reject()}},vibrate(){},userAgent:'gps-scenario-test'};
const ctx={console,document,navigator,localStorage:{setItem:(k,v)=>storage.set(k,String(v)),getItem:k=>storage.get(k)??null,removeItem:k=>storage.delete(k)},location:{href:'file:///test'},history:{back(){}},performance:{now:()=>0},setTimeout:()=>0,clearTimeout(){},setInterval:()=>0,clearInterval(){},requestAnimationFrame:()=>0,cancelAnimationFrame(){},Date,Math,JSON,Number,String,Boolean,Array,Object,RegExp,Promise,Blob:class{},URL:{createObjectURL(){return''},revokeObjectURL(){}},AudioContext:class{},webkitAudioContext:class{},SpeechSynthesisUtterance:class{},speechSynthesis:{getVoices(){return[]},cancel(){},speak(){}},DeviceMotionEvent:undefined,alert(){},confirm(){return true}};
ctx.window=ctx;ctx.globalThis=ctx;vm.createContext(ctx);new vm.Script(code).runInContext(ctx,{timeout:5000});
const e=ctx.__gpsTest, results=[];
function run(name,fn){try{fn();results.push({name,ok:true})}catch(error){results.push({name,ok:false,error:error.message})}}
function assert(v,m){if(!v)throw new Error(m)}
function reset(routeIndex=0){
  const seg=e.TRACK.segs[routeIndex],p=seg[0];
  e.state.ctx.peregon=e.TRACK.labels[routeIndex];e.state.ctx.towards='tuda';
  e.state.calib={km:Math.floor(p[2]/1000),pk:Math.floor((p[2]%1000)/100)+1,m:Math.floor(p[2]%100),_trackM:p[2]};
  Object.assign(e.rt,{tracking:true,last:null,lastGoodFix:null,odo:0,speed:80,posM:p[2],pendingCandM:null,pendingCount:0,correctionTargetOdo:null,fixQuality:null,gpsState:'waiting',trackingSince:Date.now()});
  return seg;
}
function fix(p,t,extra={}){return {coords:{latitude:p[0],longitude:p[1],accuracy:8,speed:22.22,heading:null,fixAgeMs:0,satellitesUsed:10,averageCn0:35,gnssTelemetry:true,...extra},timestamp:t}}

for(let i=0;i<e.TRACK.segs.length;i++)run(`Чистый сигнал · ${e.TRACK.labels[i]}`,()=>{
  const seg=reset(i),base=Date.now();let prev=-Infinity;
  for(let k=0;k<Math.min(seg.length,8);k++){e.onPos(fix(seg[k],base+k*1000));assert(Number.isFinite(e.rt.posM),'позиция нечисловая');assert(e.rt.posM>=prev-150,'позиция пошла назад');prev=e.rt.posM}
  assert(!['deadreckoning','weak'].includes(e.rt.fixQuality),'чистый сигнал признан плохим');
});
run('Устаревший фикс 8 секунд отбрасывается',()=>{const seg=reset();e.onPos(fix(seg[0],Date.now(),{fixAgeMs:8000}));assert(e.rt.fixQuality==='deadreckoning','устаревший фикс принят')});
run('РЭБ: мало спутников и низкий C/N0 не считаются точным GPS',()=>{const seg=reset();e.onPos(fix(seg[0],Date.now(),{satellitesUsed:2,averageCn0:11}));assert(e.rt.gpsState!=='precise','подавленный сигнал признан точным')});
run('Полная потеря сохраняет конечную позицию и не разгоняет скорость',()=>{reset();const before=e.rt.speed;e.rt.last={lat:1,lon:1,t:Date.now()-1000};for(let i=0;i<8;i++)e.onErr({code:2});assert(Number.isFinite(e.rt.speed)&&e.rt.speed<=before,'скорость выросла');assert(Number.isFinite(e.currentMeters()),'позиция потеряна')});
run('Плавное восстановление сходится без мгновенного скачка',()=>{reset();e.rt.speed=0;e.rt.correctionTargetOdo=1200;const first=e.rt.odo;for(let i=0;i<12;i++){e.rt.lastTick=Date.now()-600;e.tickerStep()}assert(e.rt.odo>first&&e.rt.odo<=1200,'коррекция неверна');assert(e.rt.correctionTargetOdo===null,'коррекция не завершилась')});
run('Одиночный скачок на другой маршрут не переписывает позицию',()=>{const seg=reset(0),base=Date.now();e.onPos(fix(seg[0],base));const before=e.currentMeters(),alien=e.TRACK.segs[e.TRACK.segs.length-1][0];e.onPos(fix(alien,base+1000));assert(Math.abs(e.currentMeters()-before)<2000,'одиночный скачок принят')});

const passed=results.filter(x=>x.ok).length;
for(const r of results)console.log(`${r.ok?'PASS':'FAIL'} ${r.name}${r.error?`: ${r.error}`:''}`);
console.log(`${passed}/${results.length} GPS scenarios passed`);
const report=['# GPS stress-test 1.4.87','',`Результат: **${passed}/${results.length} сценариев пройдено**.`,'','Проверено программной имитацией:','',...results.map(r=>`- ${r.ok?'✅':'❌'} ${r.name}${r.error?` — ${r.error}`:''}`),'','Условия: точный сигнал, все встроенные маршруты, устаревший фикс, признаки РЭБ, полная потеря, одиночный скачок и плавное восстановление.','', '> Это программная имитация, а не замена полевой проверке. ПИКЕТ остаётся вспомогательным инструментом.',''].join('\n');
if(process.argv.includes('--report'))fs.writeFileSync(new URL('GPS_TEST_RESULTS.md',root),report,'utf8');
if(passed!==results.length)process.exitCode=1;
