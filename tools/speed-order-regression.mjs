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
function speedLogic(direction,mode,routeId=direction==='tuda'?'sap-spb-msk':'sap-msk-spb',peregon='СпбГл - Москва',physicalDirection=direction) {
  const context={
    SPEEDROUTES:orders.routes,
    TIMING_STATIONS:{'СпбГл - Москва':[
      ['Санкт-Петербург-Главный',200],['Крюково',611200],['Сходня',619700],
      ['Химки',631200],['Ховрино',633900],['Москва-Товарная',646600],
      ['Москва-Пассажирская',649500]
    ],'Д. Долг - Павлово':[['Дача Долгорукова',0],['Заневский Пост-2',4800],['Манушкино',19900],['Павлово-на-Неве',29200]],
      'Павлово - Горы II путь':[['Павлово-на-Неве',29200],['Горы',42000]],
      'Горы - Павлово I путь':[['Горы',42000],['Павлово-на-Неве',29200]],
      'Горы - Петрозаводск':[['Горы',42000],['Волховстрой-1',121200],['Волховстрой-2',124400],['Свирь',287300],['Петрозаводск-Пассажирский',402400]],
      'Броневая - Луга':[['Броневая',3600],['Сиверская',68100],['Луга-1',137800]],
      'Чудово - Новгород':[['Чудово-Московское',0],['Разъезд 64 км',63900],['Великий Новгород',70000]],
      'Волховстрой - Чудово':[['Волховстрой-2',0],['Волховстрой-1',2000],['Пороги',7000],['Чудово-Кировское',101000]],
      'СПбФин - Выборг':[['Санкт-Петербург-Финляндский',0],['Выборг-Пассажирский',128900]],
      'Выборг - Каменногорск':[['Выборг-Пассажирский',0],['Каменногорск',42000]]},
    state:{ctx:{peregon,towards:physicalDirection},settings:{orderPathTuda:mode,orderPathObratno:mode}},
    rt:{},journeyTowards:()=>direction,
    metersOf:(km,pk=1,m=0)=>km*1000+(pk-1)*100+m,
    selectedScheduleTrain:()=>routeId.startsWith('sap-')?(direction==='tuda'?'751':'772'):(direction==='tuda'?'723':'724')
  };
  vm.createContext(context);vm.runInContext(html.slice(speedLogicStart,speedLogicEnd),context);
  const effectiveRouteId=context.speedRouteIdForTrip();
  return position=>{const order=context.activeSpeedOrder(effectiveRouteId,position);return{order,speed:context.orderSpeedValue(order),next:context.nextSpeedOrder(effectiveRouteId,order),routeId:effectiveRouteId};};
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
orderAt=speedLogic('obratno','alternate');
[649500,646600,639900,632600,621900,616900,612400,610300,560500].forEach(orderAt);
check('вариант II пути из Москвы после Крюково продолжает штатный II путь',orderAt(560500).speed===120);
orderAt=speedLogic('tuda','alternate','last-spb-msk');
[610500,614600,622300,632600,646000,649500].forEach(orderAt);
orderAt=speedLogic('tuda','alternate','last-spb-msk');
check('I путь от Крюково использует отдельные строки приказа до самой Москвы',orderAt(619700).speed===120&&orderAt(631200).speed===120&&orderAt(649500).speed===50);
check('дублирующий III путь убран, а полный I путь доступен для Ласточки',!html.includes('label:"III путь от Крюково"')&&html.includes('label:"I путь · Крюково → Москва"'));
orderAt=speedLogic('obratno','alternate','last-msk-spb');
[649500,646600,639900,632600,621900,616900,612400,610300,560500].forEach(orderAt);
check('Ласточка по неправильному II пути после Крюково продолжает штатный II путь',orderAt(560500).speed===160);
orderAt=speedLogic('tuda','wrong');
check('неправильный главный в Москву берёт встречный приказ и сохраняет рост километража',orderAt(249500).routeId==='sap-msk-spb'&&orderAt(249500).speed===200);
orderAt=speedLogic('obratno','wrong');
check('неправильный главный в Петербург берёт встречный приказ и сохраняет убывание километража',orderAt(249500).routeId==='sap-spb-msk'&&orderAt(249500).speed===200);
check('неправильный главный отделён от бокового пути',html.includes('value:"wrong",label:"Неправильный главный"')&&html.includes('value:"side",label:"Боковой'));
orderAt=speedLogic('obratno','auto','last-kamenn-spbfin','СПбФин - Выборг');
orderAt(123400);
const finFourthEntry=orderAt(17650),finFourthRun=orderAt(17000),finFourthExit=orderAt(16250),finSecond=orderAt(15950);
check('карта полётов ведёт обратный Финляндский ход через 4 путь и обратно на II главный',
  finFourthEntry.speed===80&&finFourthRun.speed===100&&finFourthExit.speed===50&&finSecond.speed===120&&
  finFourthRun.order.pathLabel==='4 путь'&&finSecond.order.pathLabel==='II главный');
orderAt=speedLogic('tuda','auto','last-spbfin-kamenn','Выборг - Каменногорск');
check('после Выборга приказ использует только строки Каменногорского участка',orderAt(5000).order.index>=32);
orderAt=speedLogic('obratno','auto','last-kamenn-spbfin','Выборг - Каменногорск');
check('до Выборга обратный приказ использует только строки Каменногорского участка',orderAt(40000).order.index<=18);
orderAt=speedLogic('tuda','auto','last-spbfin-kamenn','СПбФин - Выборг');
check('Финляндский — Выборг штатно идёт по I главному',orderAt(80000).order.pathLabel==='I главный');
orderAt=speedLogic('tuda','auto','last-spbfin-kamenn','Выборг - Каменногорск');
check('Выборг — Каменногорск штатно идёт по II главному',orderAt(20000).order.pathLabel==='II главный');
orderAt=speedLogic('obratno','auto','last-kamenn-spbfin','Выборг - Каменногорск');
check('Каменногорск — Выборг штатно идёт по I главному',orderAt(20000).order.pathLabel==='I главный');
orderAt=speedLogic('obratno','auto','last-kamenn-spbfin','СПбФин - Выборг');
check('Выборг — Финляндский после 4 пути возвращается на II главный',orderAt(100000).order.pathLabel==='II главный');
orderAt=speedLogic('tuda','auto','last-luga','Броневая - Луга');
const lugaOutboundMain=orderAt(50000),lugaOutboundArrival=orderAt(137900);
check(`Балтийский ход следует по I главному и заходит в Луге через IIс на IП (${lugaOutboundMain.order.pathLabel}, ${lugaOutboundArrival.order.index}, ${lugaOutboundArrival.speed})`,lugaOutboundMain.order.pathLabel==='I главный'&&lugaOutboundArrival.speed===40);
orderAt=speedLogic('obratno','auto','last-luga','Броневая - Луга');
check('обратный Балтийский ход выходит из IП через IIс и идёт по II главному',orderAt(136900).speed===25&&orderAt(120000).order.pathLabel==='II главный');
orderAt=speedLogic('tuda','auto','last-dd-ptz','Горы - Петрозаводск');
const ptzGory=orderAt(42800),ptzVolkhov=orderAt(124400),ptzArrival=orderAt(402400);
check(`маршрут на Петрозаводск использует IV главный в Горах, 3 путь Волховстроя-2 и 11 путь Петрозаводска (${ptzGory.order.index}/${ptzGory.order.pathLabel}, ${ptzVolkhov.order.index}/${ptzVolkhov.speed}, ${ptzArrival.order.index}/${ptzArrival.speed})`,
  ptzGory.order.pathLabel==='IV главный, Горы'&&ptzVolkhov.speed===40&&ptzArrival.speed===40);
orderAt=speedLogic('obratno','auto','last-ptz-dd','Горы - Петрозаводск');
check('обратный Петрозаводский ход начинает с 11 пути и не выбирает альтернативный II мостовой путь',
  orderAt(402400).speed===40&&orderAt(124400).speed===40&&speedLogic('obratno','auto','last-ptz-dd','Горы - Петрозаводск')(124400).order.index!==77);
orderAt=speedLogic('tuda','auto','last-dd-ptz','Д. Долг - Павлово');
check('Дача Долгорукова — Павлово использует только II главный своего участка',orderAt(10000).order.pathLabel==='II главный'&&orderAt(10000).order.index<=16);
orderAt=speedLogic('obratno','auto','last-ptz-dd','Д. Долг - Павлово');
check('Павлово — Дача Долгорукова использует только I главный своего участка',orderAt(10000).order.pathLabel==='I главный'&&orderAt(10000).order.index>=91);
orderAt=speedLogic('tuda','auto','last-dd-ptz','Павлово - Горы II путь');
const pavlovoGory=orderAt(33000);
check(`Павлово — Горы отделено как II главный путь (${pavlovoGory.order.index}/${pavlovoGory.order.pathLabel})`,pavlovoGory.order.pathLabel==='II главный');
orderAt=speedLogic('obratno','auto','last-ptz-dd','Горы - Павлово I путь');
const goryPavlovo=orderAt(33000);
check(`Горы — Павлово отделено как I главный путь (${goryPavlovo.order.index}/${goryPavlovo.order.pathLabel})`,goryPavlovo.order.pathLabel==='I главный');
orderAt=speedLogic('tuda','auto','last-vnov-volh2','Чудово - Новгород','obratno');
const novDeparture=orderAt(70000),novChudovo=orderAt(3000);
check(`ось Новгород — Чудово переведена из карты полётов в координаты приложения (${novDeparture.order.index} → ${novChudovo.order.index})`,novDeparture.order.index<=8&&novChudovo.order.index>=27&&novChudovo.order.index<=29);
orderAt=speedLogic('obratno','auto','last-vnov-volh2','Чудово - Новгород','tuda');
check('обратная ось Чудово — Новгород идёт последовательно вверх без строк Волховского участка',orderAt(3000).order.index>=27&&orderAt(70000).order.index<=8);
orderAt=speedLogic('tuda','auto','last-vnov-volh2','Волховстрой - Чудово','obratno');
const chudovoPorogi=orderAt(7000),chudovoVolkhov=orderAt(1000);
check(`Чудово — Волхов идёт через 5 путь Порогов и III путь Новооктябрьского парка (${chudovoPorogi.order.index}/${chudovoPorogi.speed}, ${chudovoVolkhov.order.index}/${chudovoVolkhov.order.pathLabel})`,
  chudovoPorogi.speed===40&&chudovoPorogi.order.pathLabel==='5 путь, Пороги'&&chudovoVolkhov.order.pathLabel==='III путь, Новооктябрьский парк');
orderAt=speedLogic('obratno','auto','last-vnov-volh2','Волховстрой - Чудово','tuda');
const volkhovStart=orderAt(1000),volkhovPorogi=orderAt(7000),volkhovChudovo=orderAt(100000);
check(`обратный Волхов — Чудово использует тот же штатный профиль в обратной последовательности (${volkhovStart.order.index}/${volkhovStart.order.pathLabel}, ${volkhovPorogi.order.index}/${volkhovPorogi.speed}, ${volkhovChudovo.order.index}/${volkhovChudovo.order.pathLabel})`,volkhovStart.order.pathLabel==='III главный, Волховстрой-2'&&volkhovPorogi.speed===40&&volkhovChudovo.order.pathLabel==='I главный');
const primaryGaugeLabels=[...html.matchAll(/class="g-labelPrimary"[^>]*>(\d+)<\/text>/g)].map(match=>+match[1]);
check('основные цифры спидометра имеют заданную премиальную иерархию',JSON.stringify(primaryGaugeLabels)===JSON.stringify([0,60,100,120,140,160,180,200,220,230,250]));
check('остальные цифры остаются нейтральными и компактными',html.includes('class="g-labelSecondary"')&&html.includes('.g-labelSecondary{fill:#9EABB6'));
check('переход из сводки прокручивает к текущему перегону времени хода',html.includes('#scheduleBox .schedule-leg.live')&&html.includes('data-schedule-leg='));
check('легенда приказа прямо объясняет жёлтые 40 и красные 15/25 км/ч',html.includes('🟡 40 км/ч — обычный боковой путь')&&html.includes('🔴 15/25 км/ч — особо малая скорость'));
check('карточки Сапсана и Ласточки получили спокойную анимацию с отключением по настройке системы',html.includes('@keyframes routeCardSheen')&&html.includes('@keyframes sapsanBreath')&&html.includes('prefers-reduced-motion:reduce'));
console.log(`${passed} speed-order scenarios passed`);
