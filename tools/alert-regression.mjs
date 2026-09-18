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
const gaugeStart=html.indexOf('  function gaugeDangerClass('),gaugeEnd=html.indexOf('\n  function updateGaugeDanger(',gaugeStart);
assert.ok(gaugeStart>0&&gaugeEnd>gaugeStart);
const gaugeBox={rt:{tracking:true,speed:120}};
vm.createContext(gaugeBox);vm.runInContext(html.slice(gaugeStart,gaugeEnd),gaugeBox);
const limit={spd:100};
assert.equal(gaugeBox.gaugeDangerClass(limit,false,2500,3000),'gd-danger','approach far above limit must be red');
gaugeBox.rt.speed=110;
assert.equal(gaugeBox.gaugeDangerClass(limit,false,2500,3000),'gd-warn','last 10 km/h above limit must be amber');
gaugeBox.rt.speed=100.4;
assert.equal(gaugeBox.gaugeDangerClass(limit,false,2500,3000),'gd-ready','displayed limit speed must be green');
gaugeBox.rt.speed=90;
assert.equal(gaugeBox.gaugeDangerClass(limit,false,3100,3000),'gd-ok','green must not activate before selected warning distance');
assert.equal(gaugeBox.gaugeDangerClass(limit,true,0,3000),'gd-ready','in-zone compliant speed must be green');
gaugeBox.rt.speed=101;
assert.equal(gaugeBox.gaugeDangerClass(limit,true,0,3000),'gd-danger','in-zone overspeed must be red');
assert.ok(html.includes('.gaugeWrap.gd-ready .g-fg'));
console.log('Alert regression passed: lead distance, reverse entry, no repeat/voice overspeed, red-amber-green speed readiness');
