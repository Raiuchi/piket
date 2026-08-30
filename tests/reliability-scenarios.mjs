import fs from 'node:fs';
import vm from 'node:vm';

const source=fs.readFileSync(new URL('../app/src/main/assets/assets/piket-core.js',import.meta.url),'utf8');
const box={}; vm.createContext(box); vm.runInContext(source,box);
const r=box.PIKET_RELIABILITY;
let passed=0;
function check(name,ok){if(!ok)throw new Error(`FAIL ${name}`);console.log(`PASS ${name}`);passed++;}

check('точный свежий фикс',r.confidence({fixQuality:'good',fixAgeMs:900,accuracy:12}).key==='exact');
check('позиция по счёту',r.confidence({fixQuality:'deadreckoning'}).key==='calculated');
check('восстановление после РЭБ',r.confidence({recovering:true}).key==='recovering');
check('ручная сверка имеет высший приоритет',r.confidence({manualReview:true,recovering:true}).key==='review');
check('средняя скорость 120 км за час',Math.abs(r.requiredAverageKmh(120000,3600)-120)<0.001);
check('просроченное время не рассчитывается',r.requiredAverageKmh(1000,0)===null);
check('ошибка направления туда',r.directionMismatch(1000,700,'tuda',120)===true);
check('правильное обратное направление',r.directionMismatch(1000,700,'obratno',120)===false);
check('малая контрольная поправка применяется сразу',r.checkpointCorrection(40,1).confirmed===true);
check('крупная поправка ждёт подтверждений',r.checkpointCorrection(600,3).confirmed===false&&r.checkpointCorrection(600,4).confirmed===true);
check('срабатывание в пределах 250 м принято',r.triggerAudit(2000,1810).ok===true);
check('раннее или позднее срабатывание обнаружено',r.triggerAudit(2000,1600).ok===false);
console.log(`${passed}/12 reliability scenarios passed`);
