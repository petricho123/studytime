'use strict';
const P=Planner,$=s=>document.querySelector(s),KEY='three-day-planner-v1';
let state=null,index=0,selectedDay=0,routineEdit=false,dayEdit=[false,false,false],opened=[true,true,true],archive=null,editorAction=null,lastToday=P.dateKey();
function toast(t){$('#toast').textContent=t;}
function save(){try{localStorage.setItem(KEY,JSON.stringify(state));return true;}catch(e){toast('저장하지 못했습니다. 앱을 닫기 전에 저장 공간을 확인해주세요.');return false;}}
function node(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function button(text,fn,cls){const b=node('button',text,cls);b.type='button';b.onclick=fn;return b;}
function pretty(date){const [y,m,d]=date.split('-');return Number(m)+'.'+Number(d)+' '+['일','월','화','수','목','금','토'][new Date(Number(y),Number(m)-1,Number(d)).getDay()];}
function edit(title,value,action){editorAction=action;$('#editor-title').textContent=title;$('#editor-input').value=value;$('#editor').showModal();$('#editor-input').focus();}
function source(){return archive?{start:archive.start,cycles:archive.cycles}:state;}
function current(){return source().cycles[index];}
function render(){
 if(!state)return;
 const src=source();if(!archive)P.ensureCycle(state,index);const c=current();
 const first=P.addDate(src.start,index*3),dates=[0,1,2].map(n=>P.addDate(first,n));
 $('#cycle-title').textContent=(archive?'보관 기록 · ':'')+(index+1)+'번째 작심삼일';
 $('#cycle-date').textContent=pretty(dates[0])+' — '+pretty(dates[2]);
 const keys=Object.keys(src.cycles).map(Number).sort((a,b)=>a-b);
 $('#previous').disabled=archive?keys.indexOf(index)<=0:index===0;
 $('#next').disabled=archive?keys.indexOf(index)===keys.length-1:false;
 $('#reset-button').disabled=!!archive;
 const tabs=$('#day-tabs');tabs.replaceChildren();dates.forEach((date,di)=>{const b=button((di+1)+'일차',()=>{selectedDay=di;render();});b.setAttribute('role','tab');b.setAttribute('aria-selected',String(di===selectedDay));b.append(node('small',pretty(date)+(date===P.dateKey()?' · 오늘':'')));tabs.append(b);});
 const main=$('#planner');main.replaceChildren();
 const panel=node('section',undefined,'panel'),heading=node('div',undefined,'routine-heading');
 const fold=button('',()=>{c.folded=!c.folded;if(!archive)save();render();});fold.setAttribute('aria-expanded',String(!c.folded));fold.append(node('strong','매일 루틴 '+(c.folded?'⌄':'⌃')),node('span',(selectedDay+1)+'일차 · '+c.routines.filter(r=>r.checks[selectedDay]).length+'/'+c.routines.length+' 완료','mini'));heading.append(fold);
 if(!archive)heading.append(button(routineEdit?'완료':'편집',()=>{routineEdit=!routineEdit;if(routineEdit)c.folded=false;render();},'edit'));
 panel.append(heading);
 if(!c.folded){
  if(routineEdit){c.routines.forEach(r=>{const row=node('div',undefined,'routine-row');row.append(node('span',r.name),button('수정',()=>edit('루틴 수정',r.name,value=>r.name=value)),button('삭제',()=>{c.routines=c.routines.filter(x=>x.id!==r.id);save();render();},'danger'));panel.append(row);});}
  else c.routines.forEach(r=>{const row=node('div',undefined,'task'),label=node('label'),box=node('input');box.type='checkbox';box.checked=!!r.checks[selectedDay];box.disabled=!!archive;box.setAttribute('aria-label',r.name+' '+pretty(dates[selectedDay]));box.onchange=()=>{r.checks[selectedDay]=box.checked;save();render();};label.append(box,node('span',r.name));row.append(label);panel.append(row);});
  if(!c.routines.length)panel.append(node('div','3일 동안 반복할 일을 추가해보세요.','empty'));
  if(!archive)panel.append(button('＋ 루틴 추가',()=>edit('루틴 추가','',value=>c.routines.push({id:P.id(),name:value,checks:[false,false,false]})),'add'));
 }
 main.append(panel);
 const di=selectedDay,date=dates[di],card=node('details',undefined,'panel');card.open=opened[di];card.ontoggle=()=>opened[di]=card.open;
 const summary=node('summary'),title=node('span',(di+1)+'일차 계획 · '+pretty(date));if(date===P.dateKey())title.append(node('span','오늘','badge'));
 summary.append(title,node('span',c.days[di].filter(t=>t.done).length+'/'+c.days[di].length+' 완료','mini'));card.append(summary);
 c.days[di].forEach(t=>{const row=node('div',undefined,'task'),label=node('label'),box=node('input');box.type='checkbox';box.checked=t.done;box.disabled=!!archive;box.onchange=()=>{t.done=box.checked;save();render();};label.append(box,node('span',t.name));row.append(label);if(dayEdit[di]&&!archive)row.append(button('수정',()=>edit('계획 수정',t.name,value=>t.name=value)),button('삭제',()=>{c.days[di]=c.days[di].filter(x=>x.id!==t.id);save();render();},'danger'));card.append(row);});
 if(!c.days[di].length)card.append(node('div','아직 작성한 계획이 없어요.','empty'));
 if(!archive){const tools=node('div',undefined,'day-tools');tools.append(button(dayEdit[di]?'완료':'편집',()=>{dayEdit[di]=!dayEdit[di];render();},'edit'));card.append(button('＋ 계획 추가',()=>edit((di+1)+'일차 계획 추가','',value=>c.days[di].push({id:P.id(),name:value,done:false})),'add'),tools);}
 main.append(card);
 $('#context').textContent=archive?'리셋 이전 기록 · 읽기 전용':date===P.dateKey()?'오늘 · '+(di+1)+'일차':P.serial(date)>P.serial(P.dateKey())?'미래 계획을 미리 작성해보세요.':'지난 작심의 기록';
}
function goToday(){if(!state)return;archive=null;index=P.cycleIndex(state,P.dateKey());P.ensureCycle(state,index);selectedDay=Math.max(0,Math.min(2,P.serial(P.dateKey())-P.serial(P.addDate(state.start,index*3))));routineEdit=false;dayEdit=[false,false,false];save();render();}
function moveCycle(delta){if(!state)return;if(archive){const keys=Object.keys(archive.cycles).map(Number).sort((a,b)=>a-b);index=keys[keys.indexOf(index)+delta];}else index+=delta;selectedDay=0;routineEdit=false;dayEdit=[false,false,false];render();if(!archive)save();}
$('#previous').onclick=()=>moveCycle(-1);$('#next').onclick=()=>moveCycle(1);
$('#menu-button').onclick=()=>{const m=$('#menu');m.hidden=!m.hidden;$('#menu-button').setAttribute('aria-expanded',String(!m.hidden));};
$('#today-button').onclick=()=>{$('#menu').hidden=true;goToday();if(window.StudyUI)StudyUI.show('planner');};
$('#reset-button').onclick=()=>{$('#menu').hidden=true;if(state)$('#confirm').showModal();};
$('#cancel-reset').onclick=()=>$('#confirm').close();
$('#confirm-reset').onclick=()=>{state=P.reset(state,P.dateKey());$('#confirm').close();goToday();toast('오늘부터 새로운 작심삼일을 시작합니다. 공부시간 기록은 유지됩니다.');};
$('#editor-form').onsubmit=e=>{e.preventDefault();const value=$('#editor-input').value.trim();if(!value){$('#editor-input').setCustomValidity('내용을 입력해주세요.');$('#editor-input').reportValidity();return;}editorAction(value);save();$('#editor').close();render();};
$('#editor-input').oninput=()=>$('#editor-input').setCustomValidity('');$('#cancel-editor').onclick=()=>$('#editor').close();
window.closeEditor=()=>{if(window.StudyUI&&StudyUI.closeDeskClock())return true;const dialog=document.querySelector('dialog[open]');if(dialog){if(dialog.id!=='start')dialog.close();return true;}if(!$('#menu').hidden){$('#menu').hidden=true;return true;}if(window.StudyUI&&StudyUI.page()!=='planner'){StudyUI.show('planner');return true;}return false;};
$('#history-button').onclick=()=>{if(!state)return;const list=$('#history-list');list.replaceChildren();if(!state.archives.length)list.append(node('p','보관된 기록이 없습니다.'));[...state.archives].reverse().forEach(a=>list.append(button(a.start+' 시작 · '+a.resetAt+' 리셋',()=>{archive=JSON.parse(JSON.stringify(a));const keys=Object.keys(a.cycles).map(Number).sort((a,b)=>a-b);if(!keys.length)return;index=keys[0];selectedDay=0;routineEdit=false;dayEdit=[false,false,false];$('#history').close();if(window.StudyUI)StudyUI.show('planner');render();},'history-entry')));$('#menu').hidden=true;$('#history').showModal();};
$('#close-history').onclick=()=>$('#history').close();$('#start-date').value=P.dateKey();$('#start').addEventListener('cancel',e=>e.preventDefault());
$('#start-form').onsubmit=e=>{e.preventDefault();if(!$('#start-date').value)return;state=P.create($('#start-date').value);P.ensureCycle(state,0);if(save()){$('#start').close();goToday();}};
window.refreshToday=()=>{const today=P.dateKey();if(state&&today!==lastToday){lastToday=today;goToday();}};
setInterval(window.refreshToday,30000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)window.refreshToday();});
function validatePlanner(value){const parsed=P.parse(JSON.stringify(value));const validateCycles=cycles=>Object.entries(cycles).forEach(([key,c])=>{if(!/^\d+$/.test(key)||!Array.isArray(c.routines)||!Array.isArray(c.days)||c.days.length!==3)throw Error('잘못된 플래너 기록입니다.');c.routines.forEach(r=>{if(typeof r.id!=='string'||typeof r.name!=='string'||!Array.isArray(r.checks)||r.checks.length!==3||!r.checks.every(x=>typeof x==='boolean'))throw Error('잘못된 루틴 기록입니다.');});c.days.forEach(day=>{if(!Array.isArray(day))throw Error('잘못된 하루 계획입니다.');day.forEach(t=>{if(typeof t.id!=='string'||typeof t.name!=='string'||typeof t.done!=='boolean')throw Error('잘못된 계획입니다.');});});});validateCycles(parsed.cycles);parsed.archives.forEach(a=>{if(typeof a.start!=='string'||!a.cycles)throw Error('잘못된 보관 기록입니다.');validateCycles(a.cycles);});return parsed;}
window.PlannerUI={raw:()=>state,validate:validatePlanner,restore(value){try{const next=validatePlanner(value);localStorage.setItem(KEY,JSON.stringify(next));state=next;$('#start').close();lastToday=P.dateKey();goToday();return true;}catch(e){toast('플래너 복원 실패: '+e.message);return false;}},editRoutines(){if(!state)return;goToday();routineEdit=true;current().folded=false;render();},toast};
try{const raw=localStorage.getItem(KEY);if(raw){state=P.parse(raw);goToday();}else $('#start').showModal();}catch(e){toast('저장 데이터를 읽을 수 없습니다. 기존 데이터 보호를 위해 초기화하지 않았습니다.');$('#planner').append(node('p','앱을 다시 실행해주세요. 데이터를 지우기 전에 백업이나 지원을 요청해 주세요.'));}
