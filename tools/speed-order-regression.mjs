import fs from 'node:fs';
import vm from 'node:vm';

const orders=JSON.parse(fs.readFileSync('app/src/main/assets/data/speed-reference.json','utf8'));
const coreSource=fs.readFileSync('app/src/main/assets/assets/piket-core.js','utf8');
const scheduleSource=fs.readFileSync('app/src/main/assets/assets/piket-schedules.js','utf8');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const box={window:{}};vm.createContext(box);vm.runInContext(coreSource,box);vm.runInContext(scheduleSource,box);
const reliability=box.PIKET_RELIABILITY;
const trains=box.window.PIKET_SCHEDULES.trains;
const byId=Object.fromEntries(orders.routes.map(route=>[route.id,route]));
const max=id=>Math.max(...byId[id].groups.flatMap(group=>group.rows.map(row=>row.glp).filter(Number.isFinite)));
let passed=0;
function check(name,ok){if(!ok)throw new Error(`FAIL ${name}`);console.log(`PASS ${name}`);passed++;}

check('Балтийский — Луга берёт максимум 140 из приказа',max('last-luga')===reliability.browserSpeedCeiling('Броневая - Луга'));
check('Сапсан берёт максимум 250 из приказов обоих направлений',max('sap-spb-msk')===250&&max('sap-msk-spb')===250&&reliability.browserSpeedCeiling('СпбГл - Москва','751')===250);
check('Ласточка Москва — Петербург берёт максимум 160',max('last-spb-msk')===160&&max('last-msk-spb')===160&&reliability.browserSpeedCeiling('СпбГл - Москва','723')===160);
check('Дача — Петрозаводск берёт максимум 120 обоих направлений',max('last-ptz-dd')===120&&max('last-dd-ptz')===120&&reliability.browserSpeedCeiling('Горы - Петрозаводск')===120);
check('Чудово — Петрозаводск берёт максимум 120',max('last-vnov-volh2')===120&&reliability.browserSpeedCeiling('Чудово - Новгород')===120);
check('Финляндский — Каменногорск берёт максимум 160 обоих направлений',max('last-spbfin-kamenn')===160&&max('last-kamenn-spbfin')===160&&reliability.browserSpeedCeiling('Выборг - Каменногорск')===160);
for(const number of ['801','802','841','842']) check(`поезд ${number} присутствует и ограничен 160`,trains.some(t=>t.number===number)&&reliability.browserSpeedCeiling('СпбГл - Москва',number)===160);
check('без номера Москва использует 160 до подтверждения и 250 после',reliability.browserTrustedSpeedCeiling('СпбГл - Москва')===160&&reliability.browserSpeedCeiling('СпбГл - Москва')===250);
const firstHigh=reliability.confirmAutomaticHighSpeed(null,0,200,200,true);
const secondHigh=reliability.confirmAutomaticHighSpeed(firstHigh.candidate,firstHigh.count,205,205,true);
check('автоматическая высокая скорость требует два координатных подтверждения',!firstHigh.confirmed&&secondHigh.confirmed&&!reliability.confirmAutomaticHighSpeed(null,0,250,250,false).confirmed&&html.includes('confirmAutomaticHighSpeed'));
check('недостижимый график ссылается на приказы по скоростям',html.includes('Следовать с максимально реализуемой скоростью согласно приказам по скоростям'));
check('недостижимый график никогда не печатает Infinity',!html.includes("расчёт '+Math.round(req)+' км/ч"));
for(const speed of [70,90,110,130,150,170,190,210])
  check(`на шкале подписана скорость ${speed}`,html.includes(`>${speed}</text>`));

const speedLogicStart=html.indexOf('  function speedPositionsFromName(');
const speedLogicEnd=html.indexOf('\n  function renderSpeedRef(',speedLogicStart);
function speedLogic(direction,mode,routeId=direction==='tuda'?'sap-spb-msk':'sap-msk-spb') {
  const context={
    SPEEDROUTES:orders.routes,
    TIMING_STATIONS:{'СпбГл - Москва':[
      ['Санкт-Петербург-Главный',200],['Крюково',611200],['Сходня',619700],
      ['Химки',631200],['Ховрино',633900],['Москва-Товарная',646600],
      ['Москва-Пассажирская',649500]
    ]},
    state:{ctx:{peregon:'СпбГл - Москва'},settings:{orderPathTuda:mode,orderPathObratno:mode}},
    rt:{},journeyTowards:()=>direction,
    metersOf:(km,pk=1,m=0)=>km*1000+(pk-1)*100+m,
    selectedScheduleTrain:()=>routeId.startsWith('sap-')?(direction==='tuda'?'751':'772'):(direction==='tuda'?'723':'724')
  };
  vm.createContext(context);vm.runInContext(html.slice(speedLogicStart,speedLogicEnd),context);
  return position=>{const order=context.activeSpeedOrder(routeId,position);return{order,speed:context.orderSpeedValue(order),next:context.nextSpeedOrder(routeId,order)};};
}
let orderAt=speedLogic('tuda','auto');
check('Петербург — Москва начинает с 25 км/ч на перронном пути',orderAt(200).speed===25);
check('после Крюково в Москву автоматически выбран III путь',orderAt(614300).speed===160);
check('прибытие в Москву переключается на 25 км/ч перронного пути',orderAt(649500).speed===25);
orderAt=speedLogic('obratno','auto');
check('Москва — Петербург начинает с 25 км/ч на перронном пути',orderAt(649500).speed===25);
check('из Москвы до Крюково автоматически выбран IV путь',orderAt(635400).speed===140);
check('после Крюково в Петербург автоматически выбран II путь',orderAt(610300).speed===140);
check('прибытие в Петербург переключается на 25 км/ч перронного пути',orderAt(200).speed===25);
orderAt=speedLogic('tuda','alternate');
[610500,611800,614300,631200].forEach(orderAt);
check('вариант I пути через Крюково возвращается к III и не застревает',orderAt(631200).speed===160);
orderAt=speedLogic('obratno','alternate');
[649500,646600,639900,632600,621900,616900,612400,610300,560500].forEach(orderAt);
check('вариант II пути из Москвы после Крюково продолжает штатный II путь',orderAt(560500).speed===120);
orderAt=speedLogic('tuda','alternate','last-spb-msk');
[610500,614600,622300,632600,646000,649500].forEach(orderAt);
check('Ласточка по неправильному I пути использует отдельные строки приказа до Москвы',orderAt(649500).speed===50);
orderAt=speedLogic('obratno','alternate','last-msk-spb');
[649500,646600,639900,632600,621900,616900,612400,610300,560500].forEach(orderAt);
check('Ласточка по неправильному II пути после Крюково продолжает штатный II путь',orderAt(560500).speed===160);
console.log(`${passed} speed-order scenarios passed`);
