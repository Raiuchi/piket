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
console.log(`${passed} speed-order scenarios passed`);
