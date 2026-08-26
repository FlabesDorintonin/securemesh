const fs=require('fs');
const src=fs.readFileSync('LabPanel/app.js','utf8');
const m=src.match(/function analyseTrend\(history\)\{[\s\S]*?\n\}/);
if(!m) throw new Error('analyseTrend not found');
function clamp(v,a,b){return Math.max(a,Math.min(b,v))}
eval(m[0]);
const cases=[
  {name:'rising',data:[52,55,59,63,67,72],want:'rising'},
  {name:'falling',data:[82,78,72,66,60,54],want:'falling'},
  {name:'stable-noisy',data:[68,70,67,69,68,70,69,68],want:'stable'}
];
for(const c of cases){const got=analyseTrend(c.data);if(got.state!==c.want)throw new Error(`${c.name}: ${got.state} != ${c.want}`)}
console.log('SIGNAL INTELLIGENCE CHECK: PASS');
