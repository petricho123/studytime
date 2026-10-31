const {test}=require('node:test');
const assert=require('node:assert/strict');
const P=require('../app/src/main/assets/core.js');
test('3일 회차 경계와 월·연도 경계',()=>{
 const s=P.create('2026-10-30');assert.equal(P.cycleIndex(s,'2026-11-01'),0);assert.equal(P.cycleIndex(s,'2026-11-02'),1);
 assert.equal(P.addDate('2026-12-31',2),'2027-01-02');assert.equal(P.addDate('2028-02-28',1),'2028-02-29');
});
test('루틴 복사는 체크 초기화 및 깊은 독립성 보장',()=>{
 const s=P.create('2026-10-27'),a=P.ensureCycle(s,0);a.routines.push({id:'a',name:'운동',checks:[true,true,false]});
 const b=P.ensureCycle(s,1);assert.deepEqual(b.routines[0].checks,[false,false,false]);b.routines[0].name='독서';assert.equal(a.routines[0].name,'운동');
 a.routines[0].name='영어';assert.equal(P.ensureCycle(s,1).routines[0].name,'독서');assert.deepEqual(b.days,[[],[],[]]);
});
test('며칠 미접속해도 현재 회차 계산 및 이전 루틴 복사',()=>{
 const s=P.create('2026-10-27');P.ensureCycle(s,0).routines.push({id:'x',name:'운동',checks:[true,false,false]});
 const index=P.cycleIndex(s,'2026-11-20');assert.equal(index,8);assert.equal(P.ensureCycle(s,index).routines[0].name,'운동');
});
test('리셋은 오늘로 재시작하며 과거와 미래 기록 보관',()=>{
 const s=P.create('2026-10-27');P.ensureCycle(s,0).days[0].push({id:'t',name:'공부',done:true});P.ensureCycle(s,1).days[0].push({id:'f',name:'미래 계획',done:false});
 const next=P.reset(s,'2026-10-29');assert.equal(next.start,'2026-10-29');assert.deepEqual(next.cycles[0].routines,[]);assert.deepEqual(next.cycles[0].days,[[],[],[]]);
 assert.equal(next.archives[0].cycles[0].days[0][0].name,'공부');assert.equal(next.archives[0].cycles[1].days[0][0].name,'미래 계획');
 assert.equal(P.reset(next,'2026-10-30').archives.length,2);
});
test('저장 후 읽기 및 잘못된 형식 거부',()=>{const s=P.create('2026-10-27');P.ensureCycle(s,0);assert.deepEqual(P.parse(JSON.stringify(s)),s);assert.throws(()=>P.parse('{"version":99}'));});
