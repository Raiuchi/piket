import fs from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const html = fs.readFileSync(new URL('app/src/main/assets/index.html', root), 'utf8');
let code = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n');
const close = code.lastIndexOf('})();');
code = code.slice(0, close) + 'window.__gpsTest={TRACK,CHAINAGE,state,rt,onPos,onErr,tickerStep,metersOf,currentMeters,currentTrackMeters,officialToTrackM,restrictionTrackRange,nextRestriction,stableAlongTrackCandidate,routeOfficialOffset,baseOfficialTrackM,officialTrackM,learnRouteOffset,splinePoint,snapToTrack};' + code.slice(close);

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
  const official=e.officialTrackM(p[2],e.TRACK.labels[routeIndex]);
  e.state.calib={km:Math.floor(official/1000),pk:Math.floor((official%1000)/100)+1,m:Math.floor(official%100),_trackM:p[2]};
  Object.assign(e.rt,{tracking:true,last:null,lastGoodFix:null,odo:0,speed:80,posM:official,pendingCandM:null,pendingCount:0,correctionTargetOdo:null,fixQuality:null,gpsState:'waiting',trackingSince:Date.now(),gpsResiduals:[],gpsResidualAt:0,signalLostAt:0,lastLossDecayAt:0});
  return seg;
}
function fix(p,t,extra={}){return {coords:{latitude:p[0],longitude:p[1],accuracy:8,speed:22.22,heading:null,fixAgeMs:0,satellitesUsed:10,averageCn0:35,constellationDiversity:3,gnssTelemetry:true,...extra},timestamp:t}}
function pointAtM(seg,m){
  if(m<=seg[0][2])return seg[0]; if(m>=seg.at(-1)[2])return seg.at(-1);
  let lo=0,hi=seg.length-1;while(lo+1<hi){const mid=(lo+hi)>>1;if(seg[mid][2]<=m)lo=mid;else hi=mid}
  const a=seg[lo],b=seg[lo+1],f=(m-a[2])/(b[2]-a[2]),p=e.splinePoint(seg,lo,f);return [p[0],p[1],m];
}

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
  for(const direction of ['tuda','obratno'])run(`Автокоррекция после глушения · ${label} · ${direction}`,()=>{
    const seg=reset(i,direction),start=seg[0][2],end=seg.at(-1)[2],span=end-start,sign=direction==='obratno'?-1:1;
    const realBase=direction==='tuda'?start+span*0.78:end-span*0.78;
    for(const requestedDrift of [300,1000,2000,3000,5000,8000]){
      const drift=Math.min(requestedDrift,span*0.7),calibTrack=e.state.calib._trackM;
      const deadReckoned=realBase-sign*drift;
      Object.assign(e.rt,{last:null,lastGoodFix:Date.now()-180000,odo:sign*(deadReckoned-calibTrack),posM:e.officialTrackM(deadReckoned,label),speed:80,pendingCandM:null,pendingCount:0,correctionTargetOdo:null,fixQuality:'deadreckoning',gpsState:'deadreckoning',trackingSince:Date.now()-180000});
      const base=Date.now();let finalReal=realBase;
      // После возврата сигнала продолжаем движение ещё 800 м. Это важно на станциях
      // и параллельных/сближающихся путях: одной координаты физически недостаточно,
      // последовательность движения должна вывести snapping из неоднозначной зоны.
      for(let n=0;n<41;n++){finalReal=realBase+sign*n*20;e.onPos(fix(pointAtM(seg,finalReal),base+n*1000));}
      const finalOfficial=e.officialTrackM(finalReal,label);
      assert(e.rt.correctionTargetOdo!=null||Math.abs(e.currentMeters()-finalOfficial)<100,'надёжный GPS не запустил коррекцию при дрейфе '+requestedDrift+' м');
      const targetBefore=e.rt.correctionTargetOdo==null?e.currentMeters():e.officialTrackM(calibTrack+sign*e.rt.correctionTargetOdo,label);
      e.rt.speed=0;
      for(let n=0;n<80&&e.rt.correctionTargetOdo!=null;n++){e.rt.lastTick=Date.now()-600;e.tickerStep()}
      assert(Math.abs(e.currentMeters()-finalOfficial)<=50,'позиция не сошлась в целевой коридор 50 м после дрейфа '+requestedDrift+' м: ошибка '+Math.round(e.currentMeters()-finalOfficial)+' м, цель до схождения '+Math.round(targetBefore-finalOfficial)+' м, перегон '+e.state.ctx.peregon+', target '+e.rt.correctionTargetOdo);
      assert(e.rt.gpsState==='precise'||e.rt.fixQuality==='good','GPS не вернулся в точное состояние после дрейфа '+requestedDrift+' м');
    }
  });
}
for(let si=0;si<e.TRACK.segs.length;si++){
  const pts=e.TRACK.segs[si],ids=e.CHAINAGE[si],label=e.TRACK.labels[si];
  for(let i=0;i<pts.length-1;i++){
    const physical=pts[i+1][2]-pts[i][2],official=ids[i+1]-ids[i];
    if(official>0&&Math.abs(official-physical)<=3000)continue;
    for(const direction of ['tuda','obratno'])run(`Смена км под глушением и GPS-восстановление · ${label} · ${direction}`,()=>{
      const sign=direction==='tuda'?1:-1,boundary=pts[i+1][2];
      const start=direction==='tuda'?Math.max(pts[0][2],boundary-1500):boundary;
      const actual=direction==='tuda'?boundary:Math.max(pts[0][2],boundary-700);
      reset(si,direction);
      const startOfficial=e.baseOfficialTrackM(start,label);
      e.state.calib={km:Math.floor(startOfficial/1000),pk:Math.floor((startOfficial%1000)/100)+1,m:Math.floor(startOfficial%100),_trackM:start};
      // Пересекаем смену километража только по dead-reckoning: официальный экран
      // обязан переключиться, хотя спутников в этот момент вообще нет.
      e.rt.odo=sign*(actual-start);e.rt.posM=e.currentMeters();e.rt.fixQuality='deadreckoning';e.rt.gpsState='deadreckoning';
      assert(Math.abs(e.currentMeters()-e.baseOfficialTrackM(actual,label))<2,'dead-reckoning не переключил официальную шкалу');
      // Добавляем 2 км ошибки, затем возвращаем качественный GPS непосредственно у
      // перехода. Коррекция должна работать по физической оси и не принять смену км
      // за спуфинг или невозможный прыжок.
      const dead=actual-sign*2000;
      Object.assign(e.rt,{last:null,lastGoodFix:Date.now()-180000,odo:sign*(dead-start),posM:e.baseOfficialTrackM(dead,label),speed:80,pendingCandM:null,pendingCount:0,correctionTargetOdo:null,fixQuality:'deadreckoning',gpsState:'deadreckoning',trackingSince:Date.now()-180000});
      const base=Date.now();
      for(let n=0;n<8;n++)e.onPos(fix(pointAtM(pts,actual),base+n*1000));
      e.rt.speed=0;for(let n=0;n<100&&e.rt.correctionTargetOdo!=null;n++){e.rt.lastTick=Date.now()-600;e.tickerStep()}
      assert(Math.abs(e.currentMeters()-e.baseOfficialTrackM(actual,label))<30,'после РЭБ позиция не сошлась у смены км');
      assert(e.rt.gpsState==='precise'||e.rt.fixQuality==='good','GPS не восстановился у смены км');
    });
  }
}
for(let si=0;si<e.TRACK.segs.length;si++){
  const pts=e.TRACK.segs[si],ids=e.CHAINAGE[si],label=e.TRACK.labels[si];
  for(let i=0;i<pts.length-1;i++){
    const physical=pts[i+1][2]-pts[i][2],official=ids[i+1]-ids[i];
    if(official>0&&Math.abs(official-physical)<=3000)continue;
    for(const direction of ['tuda','obratno'])run(`Ограничение корректно найдено через смену км · ${label} · ${direction}`,()=>{
      const forward=direction==='tuda',targetIndex=forward?i+1:i,targetTrack=pts[targetIndex][2],targetOfficial=ids[targetIndex];
      const positionTrack=targetTrack+(forward?-500:500);
      reset(si,direction);
      const start=e.state.calib._trackM,sign=forward?1:-1;
      e.rt.odo=sign*(positionTrack-start);e.rt.posM=e.currentMeters();
      const km=Math.floor(targetOfficial/1000),pk=Math.floor((targetOfficial%1000)/100)+1;
      e.state.restrictions=[{id:`change-${si}-${i}-${direction}`,peregon:label,dir:direction,km,pk,spd:60,reason:'test'}];
      const found=e.nextRestriction();
      assert(found!=null,'ограничение потерялось за сменой километража');
      assert(Math.abs(found._ahead-500)<5,'физическая дистанция искажена: '+found._ahead+' м вместо 500 м');
      assert(!found._zone,'ограничение преждевременно признано текущей зоной');
    });
  }
}
run('Устаревший фикс 8 секунд отбрасывается',()=>{const seg=reset();e.onPos(fix(seg[0],Date.now(),{fixAgeMs:8000}));assert(e.rt.fixQuality==='deadreckoning','устаревший фикс принят')});
run('РЭБ: мало спутников и низкий C/N0 не считаются точным GPS',()=>{const seg=reset();e.onPos(fix(seg[0],Date.now(),{satellitesUsed:2,averageCn0:11}));assert(e.rt.gpsState!=='precise','подавленный сигнал признан точным')});
run('Полная потеря сохраняет конечную позицию и не разгоняет скорость',()=>{reset();const before=e.rt.speed;e.rt.last={lat:1,lon:1,t:Date.now()-1000};for(let i=0;i<8;i++)e.onErr({code:2});assert(Number.isFinite(e.rt.speed)&&e.rt.speed<=before,'скорость выросла');assert(Number.isFinite(e.currentMeters()),'позиция потеряна')});
run('Плавное восстановление сходится без мгновенного скачка',()=>{reset();e.rt.speed=0;e.rt.correctionTargetOdo=1200;const first=e.rt.odo;for(let i=0;i<12;i++){e.rt.lastTick=Date.now()-600;e.tickerStep()}assert(e.rt.odo>first&&e.rt.odo<=1200,'коррекция неверна');assert(e.rt.correctionTargetOdo===null,'коррекция не завершилась')});
run('Частые callback потери GPS не останавливают счёт искусственно',()=>{reset();e.rt.last={lat:1,lon:1,t:Date.now()};e.rt.speed=100;e.rt.lastLossDecayAt=Date.now()-1000;for(let i=0;i<100;i++)e.onErr({code:2});assert(e.rt.speed>98,'скорость зависит от частоты callback: '+e.rt.speed)});
run('Медианный фильтр подавляет одиночный шум без временного запаздывания',()=>{reset();const now=Date.now(),expected=100000;for(const residual of [18,22,160,20,19])e.stableAlongTrackCandidate(expected+residual,expected,now,8,true);const filtered=e.stableAlongTrackCandidate(expected+21,expected,now,8,true);assert(Math.abs(filtered-(expected+20))<=2,'медиана не подавила выброс: '+(filtered-expected))});
run('Невозможный разгон 0→100 км/ч за 2 секунды отбрасывается',()=>{const seg=reset();const base=Date.now();e.rt.speed=0;e.onPos(fix(seg[0],base,{speed:0}));e.onPos(fix(seg[0],base+2000,{speed:100/3.6}));assert(e.rt.speed<20,'невозможный разгон принят: '+e.rt.speed)});
run('Скорость после РЭБ восстанавливается только двумя согласованными Doppler-замерами',()=>{const seg=reset();const base=Date.now();e.rt.speed=0;e.rt.signalLostAt=base-30000;e.rt.last={lat:seg[0][0],lon:seg[0][1],t:base-1000};e.onPos(fix(seg[0],base,{speed:100/3.6}));assert(e.rt.speed<20,'первый замер после РЭБ принят без подтверждения');e.onPos(fix(seg[0],base+1000,{speed:101/3.6}));assert(e.rt.speed>80&&e.rt.speed<120,'согласованная скорость не восстановлена: '+e.rt.speed)});
run('Подтверждённая крупная GPS-поправка применяется сразу для ограничений',()=>{const seg=reset(),label=e.state.ctx.peregon,start=seg[0][2],actual=Math.min(seg.at(-1)[2],start+4000),base=Date.now();e.rt.odo=1000;e.rt.posM=e.currentMeters();e.rt.lastGoodFix=base-180000;e.rt.speed=80;for(let n=0;n<6;n++)e.onPos(fix(pointAtM(seg,actual),base+n*1000));assert(e.rt.correctionTargetOdo==null,'крупная подтверждённая поправка оставлена плавной');assert(Math.abs(e.currentMeters()-e.officialTrackM(actual,label))<50,'позиция не исправлена сразу')});
run('Официальная ось полностью совпадает с listPoints.txt во всех опорных точках',()=>{
  for(let si=0;si<e.TRACK.segs.length;si++){
    const pts=e.TRACK.segs[si],ids=e.CHAINAGE[si],label=e.TRACK.labels[si];
    assert(pts.length===ids.length,'разное число физических и официальных точек: '+label);
    for(let i=0;i<pts.length;i++)assert(Math.abs(e.baseOfficialTrackM(pts[i][2],label)-ids[i])<1,'не совпал id '+ids[i]+' на '+label);
  }
});
run('Все смены километража переключаются ступенью, а не растягиваются по пути',()=>{
  let changes=0;
  for(let si=0;si<e.TRACK.segs.length;si++){
    const pts=e.TRACK.segs[si],ids=e.CHAINAGE[si],label=e.TRACK.labels[si];
    for(let i=0;i<pts.length-1;i++){
      const physical=pts[i+1][2]-pts[i][2],official=ids[i+1]-ids[i];
      if(official>0&&Math.abs(official-physical)<=3000)continue;
      changes++;
      const before=pts[i+1][2]-Math.min(10,Math.max(2,physical/10));
      const beforeOfficial=e.baseOfficialTrackM(before,label);
      assert(Math.abs(beforeOfficial-ids[i])<physical+20,'до смены уже включилась новая шкала: '+label);
      assert(e.baseOfficialTrackM(pts[i+1][2],label)===ids[i+1],'в точке смены не включился id '+ids[i+1]+': '+label);
    }
  }
  assert(changes===5,'ожидалось 5 подтверждённых смен километража, найдено '+changes);
});
run('Ручная сверка изучает отдельную официальную поправку перегона',()=>{const value=e.learnRouteOffset('СПбФин - Выборг',51000,50000);assert(value===1000,'поправка рассчитана неверно');assert(e.routeOfficialOffset('СПбФин - Выборг')===1000,'поправка не сохранена');assert(e.routeOfficialOffset('СпбГл - Москва')===0,'поправка протекла на другой маршрут')});
run('Автокалибровка применяет изученную поправку без изменения GPS-геометрии',()=>{const i=e.TRACK.labels.indexOf('СПбФин - Выборг'),raw=e.TRACK.segs[i][20][2];assert(e.officialTrackM(raw,'СПбФин - Выборг')===raw+1000,'официальная ось не скорректирована');assert(e.TRACK.segs[i][20][2]===raw,'исходная геометрия была изменена')});

const passed=results.filter(x=>x.ok).length;
for(const r of results)console.log(`${r.ok?'PASS':'FAIL'} ${r.name}${r.error?`: ${r.error}`:''}`);
console.log(`${passed}/${results.length} GPS scenarios passed`);
const report=['# GPS stress-test 1.4.91','',`Результат: **${passed}/${results.length} сценариев пройдено**.`,'',`Покрытие: **${e.TRACK.segs.length}/${e.TRACK.segs.length} маршрутов**, оба направления, все опорные точки геометрии.`,'','Проверено программной имитацией:','',...results.map(r=>`- ${r.ok?'✅':'❌'} ${r.name}${r.error?` — ${r.error}`:''}`),'','Условия: чистый сигнал по всей геометрии, оба направления, индивидуальная привязка официального километража к GPS-оси, низкий C/N0, потеря спутников и разнообразия созвездий, плохая точность, плохой Doppler, устаревшие и mock-фиксы, полная потеря, скачок на чужой маршрут и восстановление.','', '> Это программная имитация, а не замена полевой проверке. ПИКЕТ остаётся вспомогательным инструментом.',''].join('\n');
if(process.argv.includes('--report'))fs.writeFileSync(new URL('GPS_TEST_RESULTS.md',root),report,'utf8');
if(passed!==results.length)process.exitCode=1;
