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
function reset(routeIndex=0,direction='tuda'){
  const seg=e.TRACK.segs[routeIndex],p=direction==='tuda'?seg[0]:seg[seg.length-1];
  e.state.ctx.peregon=e.TRACK.labels[routeIndex];e.state.ctx.towards=direction;
  e.state.calib={km:Math.floor(p[2]/1000),pk:Math.floor((p[2]%1000)/100)+1,m:Math.floor(p[2]%100),_trackM:p[2]};
  Object.assign(e.rt,{tracking:true,last:null,lastGoodFix:null,odo:0,speed:80,posM:p[2],pendingCandM:null,pendingCount:0,correctionTargetOdo:null,fixQuality:null,gpsState:'waiting',trackingSince:Date.now()});
  return seg;
}
function fix(p,t,extra={}){return {coords:{latitude:p[0],longitude:p[1],accuracy:8,speed:22.22,heading:null,fixAgeMs:0,satellitesUsed:10,averageCn0:35,constellationDiversity:3,gnssTelemetry:true,...extra},timestamp:t}}

for(let i=0;i<e.TRACK.segs.length;i++){
  const label=e.TRACK.labels[i];
  for(const direction of ['tuda','obratno'])run(`Полный маршрут, чистый сигнал · ${label} · ${direction}`,()=>{
    const source=reset(i,direction),seg=direction==='tuda'?source:[...source].reverse(),base=Date.now();
    for(let k=0;k<seg.length;k++){
      e.onPos(fix(seg[k],base+k*1000));
      assert(Number.isFinite(e.rt.posM),'позиция нечисловая');
      assert(Number.isFinite(e.rt.speed)&&e.rt.speed>=0&&e.rt.speed<=255,'скорость вне диапазона');
    }
    assert(!['deadreckoning','weak'].includes(e.rt.fixQuality),'чистый сигнал признан плохим');
  });
  run(`РЭБ и восстановление · ${label}`,()=>{
    const seg=reset(i),base=Date.now(),before=e.currentMeters();
    e.onPos(fix(seg[0],base));
    e.onPos(fix(seg[Math.min(1,seg.length-1)],base+1000,{accuracy:95,satellitesUsed:2,averageCn0:10,constellationDiversity:1,speedAccuracyMps:12}));
    for(let n=0;n<6;n++)e.onErr({code:2});
    assert(Number.isFinite(e.currentMeters()),'позиция потеряна при подавлении');
    assert(Number.isFinite(e.rt.speed)&&e.rt.speed<=255,'скорость повреждена при подавлении');
    const recovery=seg[Math.min(2,seg.length-1)];
    e.onPos(fix(recovery,base+9000));e.onPos(fix(recovery,base+10000));
    assert(e.rt.gpsState==='precise','точный сигнал не восстановлен');
    assert(Math.abs(e.currentMeters()-before)<5000,'восстановление вызвало неконтролируемый скачок');
  });
  run(`Подмена, старый фикс и плохой Doppler · ${label}`,()=>{
    const seg=reset(i),base=Date.now(),before=e.currentMeters();
    e.onPos(fix(seg[0],base,{mockLocation:true}));
    e.onPos(fix(seg[0],base+1000,{fixAgeMs:9000}));
    e.onPos(fix(seg[Math.min(1,seg.length-1)],base+2000,{speed:70,speedAccuracyMps:20,accuracy:90,satellitesUsed:2,averageCn0:9,constellationDiversity:1}));
    assert(Math.abs(e.currentMeters()-before)<2000,'сомнительные данные сдвинули позицию');
    assert(e.rt.speed<=255,'сомнительный Doppler разогнал скорость');
  });
  run(`Одиночный скачок на чужой маршрут · ${label}`,()=>{
    const seg=reset(i),base=Date.now();e.onPos(fix(seg[0],base));const before=e.currentMeters();
    const alien=e.TRACK.segs[(i+Math.max(1,Math.floor(e.TRACK.segs.length/2)))%e.TRACK.segs.length][0];
    e.onPos(fix(alien,base+1000));assert(Math.abs(e.currentMeters()-before)<2000,'одиночный скачок принят');
  });
}
run('Устаревший фикс 8 секунд отбрасывается',()=>{const seg=reset();e.onPos(fix(seg[0],Date.now(),{fixAgeMs:8000}));assert(e.rt.fixQuality==='deadreckoning','устаревший фикс принят')});
run('РЭБ: мало спутников и низкий C/N0 не считаются точным GPS',()=>{const seg=reset();e.onPos(fix(seg[0],Date.now(),{satellitesUsed:2,averageCn0:11}));assert(e.rt.gpsState!=='precise','подавленный сигнал признан точным')});
run('Полная потеря сохраняет конечную позицию и не разгоняет скорость',()=>{reset();const before=e.rt.speed;e.rt.last={lat:1,lon:1,t:Date.now()-1000};for(let i=0;i<8;i++)e.onErr({code:2});assert(Number.isFinite(e.rt.speed)&&e.rt.speed<=before,'скорость выросла');assert(Number.isFinite(e.currentMeters()),'позиция потеряна')});
run('Плавное восстановление сходится без мгновенного скачка',()=>{reset();e.rt.speed=0;e.rt.correctionTargetOdo=1200;const first=e.rt.odo;for(let i=0;i<12;i++){e.rt.lastTick=Date.now()-600;e.tickerStep()}assert(e.rt.odo>first&&e.rt.odo<=1200,'коррекция неверна');assert(e.rt.correctionTargetOdo===null,'коррекция не завершилась')});

const passed=results.filter(x=>x.ok).length;
for(const r of results)console.log(`${r.ok?'PASS':'FAIL'} ${r.name}${r.error?`: ${r.error}`:''}`);
console.log(`${passed}/${results.length} GPS scenarios passed`);
const report=['# GPS stress-test 1.4.89','',`Результат: **${passed}/${results.length} сценариев пройдено**.`,'',`Покрытие: **${e.TRACK.segs.length}/${e.TRACK.segs.length} маршрутов**, оба направления, все опорные точки геометрии.`,'','Проверено программной имитацией:','',...results.map(r=>`- ${r.ok?'✅':'❌'} ${r.name}${r.error?` — ${r.error}`:''}`),'','Условия: чистый сигнал по всей геометрии, оба направления, низкий C/N0, потеря спутников и разнообразия созвездий, плохая точность, плохой Doppler, устаревшие и mock-фиксы, полная потеря, скачок на чужой маршрут и восстановление.','', '> Это программная имитация, а не замена полевой проверке. ПИКЕТ остаётся вспомогательным инструментом.',''].join('\n');
if(process.argv.includes('--report'))fs.writeFileSync(new URL('GPS_TEST_RESULTS.md',root),report,'utf8');
if(passed!==results.length)process.exitCode=1;
