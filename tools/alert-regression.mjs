import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const start=html.indexOf('  function evalAlerts('),end=html.indexOf('\n  function fireAlert(',start);
assert.ok(start>0&&end>start);
for(const direction of ['tuda','obratno']) {
  const calls=[];
  const r={id:'r',km:5,pk:1,kmE:5,pkE:2,spd:60,lead:3000};
  const box={rt:{tracking:true,posM:direction==='tuda'?2700:7400,speed:80},
    state:{calib:{},ctx:{peregon:'route',towards:direction},settings:{lead:2000},restrictions:[r]},
    alertState:{},currentTrackMeters:()=>null,isActive:()=>true,inCtx:()=>true,
    dirDown:()=>direction==='obratno',metersOf:(km,pk=1)=>km*1000+(pk-1)*100,
    fireAlert:(...args)=>calls.push(args),$:()=>({className:''})};
  vm.createContext(box);vm.runInContext(html.slice(start,end),box);
  box.evalAlerts();assert.equal(calls.length,0,'must not announce at old 3km setting');
  box.rt.posM=direction==='tuda'?3000:7100;box.evalAlerts();
  assert.equal(calls.length,1);assert.equal(calls[0][0],'warn');assert.equal(calls[0][2],2000);
  box.rt.posM=direction==='tuda'?5000:5100;box.evalAlerts();
  assert.equal(calls.length,2);assert.equal(calls[1][0],'danger');
  for(let i=0;i<100;i++)box.evalAlerts();
  box.rt.posM=direction==='tuda'?5200:4900;box.evalAlerts();
  box.rt.posM=direction==='tuda'?5000:5100;box.evalAlerts();
  assert.equal(calls.length,2,'no overspeed, acknowledgement or jitter repeat');
}
assert.ok(!html.includes('Ограничение не подтверждено'));
assert.ok(!html.includes('fireOverspeedAlert'));
assert.ok(!html.includes('id="alAck"'));
console.log('Alert regression passed: 2km setting, reverse zone entry, no repeat/overspeed/acknowledgement');
