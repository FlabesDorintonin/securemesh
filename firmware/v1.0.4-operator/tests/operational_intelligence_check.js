const fs=require('fs');
const src=fs.readFileSync('LabPanel/app.js','utf8');
for(const token of ['GET_HEALTH:30','GET_SELF_DIAG:31','networkReadiness','healthProblems','linkRisk','captureFieldSample','exportFieldCsv','persistentUnknown']){
  if(!src.includes(token)) throw new Error(`missing v1.0 feature: ${token}`);
}
// Guard the core human-facing recorder contract: no raw RF engineering columns.
const start=src.indexOf('function exportFieldCsv');
const end=src.indexOf('function clearFieldRecord',start);
const block=src.slice(start,end);
for(const forbidden of ["['RSSI'","['SNR'","['PDR'","['ACK'"]){if(block.includes(forbidden))throw new Error(`raw engineering column leaked: ${forbidden}`)}
for(const required of ['Качество связи','Тренд сигнала','Надёжность','Доставлено','Маршрут','Состояние сети']){if(!block.includes(required))throw new Error(`human field missing: ${required}`)}
console.log('OPERATIONAL INTELLIGENCE CHECK: PASS');
