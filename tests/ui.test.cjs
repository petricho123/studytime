const {test}=require('node:test');
const {JSDOM}=require('jsdom');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const base=path.join(__dirname,'../app/src/main/assets/');
function setup(saved){
 const dom=new JSDOM(fs.readFileSync(base+'index.html','utf8'),{url:'https://planner.local/',runScripts:'outside-only'}),w=dom.window,d=w.document,calls=[];
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};w.confirm=()=>true;
 if(saved)w.localStorage.setItem('three-day-planner-v1',JSON.stringify(saved));
 w.StudyBridge={snapshot:(...args)=>calls.push(['snapshot',...args]),action:a=>calls.push(['action',a]),editRecord:(...a)=>calls.push(['editRecord',...a]),deleteRecord:id=>calls.push(['deleteRecord',id]),openSettings:a=>calls.push(['openSettings',a]),exportData:a=>calls.push(['exportData',a]),chooseImport:()=>calls.push(['chooseImport']),importData:a=>calls.push(['importData',a])};
 for(const f of ['core.js','app.js','study.js'])w.eval(fs.readFileSync(base+f,'utf8'));
 const q=s=>d.querySelector(s),click=s=>q(s).click(),submit=s=>q(s).dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
 const saveEdit=value=>{q('#editor-input').value=value;submit('#editor-form');};
 function start(){q('#start-date').value=w.Planner.dateKey();submit('#start-form');}
 function snapshot(extra={}){const today=w.Planner.dateKey(),m=today.slice(0,7),days=Array(new Date(Number(m.slice(0,4)),Number(m.slice(5)),0).getDate()).fill(0);w.StudyUI.receive({today,date:today,month:m,state:'RUNNING',todayTotal:9300000,selectedTotal:9300000,monthTotal:9300000,days,todayRecords:[],selectedRecords:[],saveFailed:false,loadFailed:false,recovered:false,...extra});}
 return {dom,w,d,calls,q,click,submit,saveEdit,start,snapshot};
}
test('매일 루틴은 날짜별 독립 체크, 날짜 이동 후 저장 유지',()=>{
 const a=setup();try{a.start();a.click('.routine-heading ~ .add');a.saveEdit('영어 단어');let check=a.q('#planner .task input');check.checked=true;check.dispatchEvent(new a.w.Event('change'));
 a.click('#day-tabs button:nth-child(2)');assert.equal(a.q('#planner .task input').checked,false);
 a.click('#day-tabs button:nth-child(3)');assert.equal(a.q('#planner .task input').checked,false);
 a.click('#day-tabs button:first-child');assert.equal(a.q('#planner .task input').checked,true);
 assert.deepEqual(JSON.parse(a.w.localStorage.getItem('three-day-planner-v1')).cycles[0].routines[0].checks,[true,false,false]);
 }finally{a.w.close();}
});
test('1/2/3일차 계획 독립, 수정 삭제, 루틴 복사와 원래 회차 유지',()=>{
 const a=setup();try{a.start();a.click('.routine-heading ~ .add');a.saveEdit('운동');a.click('details .add');a.saveEdit('약리학 복습');
 a.click('#day-tabs button:nth-child(2)');assert.equal(a.q('details .task'),null);a.click('details .add');a.saveEdit('해부생리학');
 a.click('#day-tabs button:first-child');assert.equal(a.q('details .task span').textContent,'약리학 복습');a.click('details .day-tools button');a.click('details .task button');a.saveEdit('약리학 OX');assert.equal(a.q('details .task span').textContent,'약리학 OX');
 a.click('details .task .danger');assert.equal(a.q('details .task'),null);
 a.click('#next');assert.equal(a.q('#planner .task span').textContent,'운동');assert.equal(a.q('#planner .task input').checked,false);a.click('.routine-heading .edit');a.click('.routine-row button');a.saveEdit('러닝');a.click('#previous');assert.equal(a.q('#planner .task span').textContent,'운동');
 }finally{a.w.close();}
});
test('리셋 취소/보관 기록 읽기 전용/미래 회차 유지',()=>{
 const a=setup();try{a.start();a.click('.routine-heading ~ .add');a.saveEdit('복기');a.click('#next');a.click('details .add');a.saveEdit('미래 계획');a.click('#reset-button');a.click('#cancel-reset');assert.equal(a.q('details .task span').textContent,'미래 계획');
 a.click('#reset-button');a.click('#confirm-reset');const stored=JSON.parse(a.w.localStorage.getItem('three-day-planner-v1'));assert.equal(stored.archives.length,1);assert.equal(stored.archives[0].cycles[1].days[0][0].name,'미래 계획');assert.equal(a.q('#planner .task'),null);
 a.click('#history-button');a.click('.history-entry');assert.equal(a.q('#planner .task input').disabled,true);a.click('#next');assert.equal(a.q('details .task input').disabled,true);
 assert.equal(a.calls.some(c=>c[0]==='action'),false,'플래너 리셋은 타이머를 조작하지 않음');
 }finally{a.w.close();}
});
test('메뉴 이동과 체크는 타이머 정지 명령을 보내지 않음',()=>{
 const a=setup();try{a.start();a.snapshot();a.click('[data-page="study"]');assert.equal(a.q('#study-time').textContent,'02:35:00');a.click('[data-page="calendar"]');a.click('[data-page="settings"]');a.click('[data-page="planner"]');
 assert.equal(a.calls.filter(c=>c[0]==='action').length,0);assert.equal(a.q('#compact-time').textContent,'02:35:00');
 a.click('#study-bar');a.click('#study-start');assert.deepEqual(a.calls.at(-1),['action','pause']);a.snapshot({state:'MANUAL_PAUSED'});a.click('#study-start');assert.deepEqual(a.calls.at(-1),['action','start']);a.click('#study-stop');assert.deepEqual(a.calls.at(-1),['action','stop']);
 }finally{a.w.close();}
});
test('달력 이전/다음 달과 날짜 선택은 네이티브에 해당 날짜 요청',()=>{
 const a=setup();try{a.start();a.snapshot({state:'STOPPED'});a.click('[data-page="calendar"]');const today=a.w.Planner.dateKey(),[y,m]=today.slice(0,7).split('-').map(Number);a.click('#month-prev');const prev=a.w.Planner.dateKey(new a.w.Date(y,m-2,1));assert.deepEqual(a.calls.at(-1),['snapshot',prev,prev.slice(0,7)]);
 a.click('#month-next');const cur=a.w.Planner.dateKey(new a.w.Date(y,m-1,1));assert.deepEqual(a.calls.at(-1),['snapshot',cur,cur.slice(0,7)]);
 a.snapshot({date:cur,state:'STOPPED'});a.click('#calendar-grid button:nth-of-type(2)');assert.equal(a.calls.at(-1)[1],cur.slice(0,8)+'02');
 }finally{a.w.close();}
});
test('탁상시계는 기존 공부시간을 표시하고 뒤로 가면 공부 탭으로 돌아옴',()=>{
 const a=setup();try{
  a.start();
  a.w.StudyBridge.setDeskClockMode=enabled=>a.calls.push(['deskClock',enabled]);
  a.click('[data-page="study"]');a.snapshot();a.click('#open-desk-clock');
  assert.equal(a.q('#desk-clock').open,true);
  assert.match(a.q('#desk-clock-hour-minute').textContent,/^\d{2}:\d{2}$/);
  assert.match(a.q('#desk-clock-second').textContent,/^:\d{2}$/);
  assert.equal(a.q('#desk-clock-total').textContent,a.q('#study-time').textContent);
  assert.deepEqual(a.calls.filter(c=>c[0]==='deskClock'),[['deskClock',true]]);
  a.snapshot({todayTotal:9301000});
  assert.equal(a.q('#desk-clock-total').textContent,'02:35:01');
  a.click('#desk-clock-toggle');
  assert.deepEqual(a.calls.at(-1),['action','pause']);
  a.snapshot({state:'MANUAL_PAUSED'});
  a.click('#desk-clock-toggle');
  assert.deepEqual(a.calls.at(-1),['action','start']);
  assert.equal(a.w.closeEditor(),true);
  assert.equal(a.q('#desk-clock').open,false);
  assert.equal(a.w.StudyUI.page(),'study');
  assert.deepEqual(a.calls.at(-1),['deskClock',false]);
 }finally{a.w.close();}
});
test('기존 v1 localStorage를 같은 키로 복원하고 체크와 보관 기록 보존',()=>{
 const P=require(base+'core.js'),today=P.dateKey(),old=P.create(today),c=P.ensureCycle(old,0);c.routines.push({id:'r',name:'기존 루틴',checks:[true,false,true]});c.days[1].push({id:'t',name:'기존 계획',done:true});const a=setup(old);
 try{assert.equal(a.q('#planner .task span').textContent,'기존 루틴');assert.equal(a.q('#planner .task input').checked,true);a.click('#day-tabs button:nth-child(2)');assert.equal(a.q('details .task span').textContent,'기존 계획');assert.equal(a.q('details .task input').checked,true);assert.equal(a.q('#start').open,false);}finally{a.w.close();}
});
test('공부 기록 추가/편집/삭제, 실행 중 편집 차단',()=>{
 const a=setup();try{a.start();a.snapshot();a.click('[data-page="calendar"]');a.click('#add-record');assert.equal(a.q('#record-editor').open,false);a.snapshot({state:'STOPPED'});a.click('#add-record');assert.equal(a.q('#record-editor').open,true);
 a.q('#record-start').value='2020-01-01T09:00:00';a.q('#record-end').value='2020-01-01T10:00:00';a.submit('#record-form');assert.equal(a.calls.at(-1)[0],'editRecord');assert.equal(a.calls.at(-1)[1],'');assert.equal(a.calls.at(-1)[3]-a.calls.at(-1)[2],3600000);
 a.w.StudyUI.recordSaved();assert.equal(a.q('#record-editor').open,false);
 a.snapshot({state:'STOPPED',selectedRecords:[{id:'7',start:new Date('2020-01-01T09:00:00').getTime(),end:new Date('2020-01-01T10:00:00').getTime(),elapsed:3600000,label:'09:00 – 10:00',live:false}]});a.click('#date-records button');a.click('#record-delete');assert.deepEqual(a.calls.at(-1),['deleteRecord','7']);
 }finally{a.w.close();}
});
test('전체 백업/복원 확인과 손상된 플래너 데이터 거부',()=>{
 const a=setup();try{a.start();a.click('#backup');assert.equal(a.calls.at(-1)[0],'exportData');const planner=JSON.parse(a.calls.at(-1)[1]);assert.equal(planner.version,1);a.w.StudyUI.prepareImport({type:'three-day-study-backup',version:1,planner,study:{records:[]}});assert.equal(a.calls.at(-1)[0],'importData');
 const invalid={...planner,cycles:{0:{routines:[{id:'bad',name:'x',checks:[true]}],days:[[],[],[]]}}};const count=a.calls.length;a.w.StudyUI.prepareImport({type:'three-day-study-backup',version:1,planner:invalid,study:{records:[]}});assert.equal(a.calls.length,count);assert.match(a.q('#toast').textContent,/잘못된/);
 assert.equal(a.w.PlannerUI.restore(planner),true);assert.equal(a.w.PlannerUI.restore(invalid),false);
 }finally{a.w.close();}
});
test('메모 내용은 HTML로 실행하지 않고 텍스트로 표시',()=>{
 const a=setup();try{a.start();a.click('details .add');a.saveEdit('<img src=x onerror=alert(1)>');assert.equal(a.q('details .task img'),null);assert.match(a.q('details .task span').textContent,/<img/);}finally{a.w.close();}
});
