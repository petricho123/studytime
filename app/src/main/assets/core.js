(function(root){
'use strict';
const DAY=86400000;
function dateKey(d=new Date()){return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');}
function serial(key){const [y,m,d]=key.split('-').map(Number);return Date.UTC(y,m-1,d)/DAY;}
function addDate(key,n){const d=new Date((serial(key)+n)*DAY);return d.toISOString().slice(0,10);}
function id(){return Date.now().toString(36)+Math.random().toString(36).slice(2);}
function create(start){return {version:1,start,cycles:{},archives:[]};}
function cycleIndex(state,today){return Math.max(0,Math.floor((serial(today)-serial(state.start))/3));}
function ensureCycle(state,index){
 if(!Number.isInteger(index)||index<0)throw Error('잘못된 회차');
 if(!state.cycles[index]){
  const prior=Object.keys(state.cycles).map(Number).filter(n=>n<index).sort((a,b)=>b-a)[0];
  state.cycles[index]={routines:prior===undefined?[]:state.cycles[prior].routines.map(r=>({id:id(),name:r.name,checks:[false,false,false]})),days:[[],[],[]],folded:false};
 }
 return state.cycles[index];
}
function reset(state,today){
 const next=create(today);
 next.archives=[...state.archives,{id:id(),start:state.start,cycles:state.cycles,resetAt:today}];
 ensureCycle(next,0);return next;
}
function parse(raw){
 const s=JSON.parse(raw);
 if(!s||s.version!==1||!/^\d{4}-\d{2}-\d{2}$/.test(s.start)||!s.cycles||!Array.isArray(s.archives))throw Error('저장 데이터 형식 오류');
 Object.values(s.cycles).forEach(c=>{if(!Array.isArray(c.routines)||!Array.isArray(c.days)||c.days.length!==3||!c.days.every(Array.isArray))throw Error('회차 데이터 오류');});
 return s;
}
const api={dateKey,serial,addDate,id,create,cycleIndex,ensureCycle,reset,parse};
if(typeof module!=='undefined')module.exports=api;else root.Planner=api;
})(typeof window==='undefined'?globalThis:window);
