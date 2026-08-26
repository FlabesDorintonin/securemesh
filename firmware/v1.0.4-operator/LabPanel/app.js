(() => {
'use strict';

const SERVICE = '7b7f0001-6b6f-4d65-7368-534543555245';
const INFO = '7b7f0002-6b6f-4d65-7368-534543555245';
const COMMAND = '7b7f0003-6b6f-4d65-7368-534543555245';
const RESPONSE = '7b7f0004-6b6f-4d65-7368-534543555245';
const EVENT = '7b7f0005-6b6f-4d65-7368-534543555245';
const FRAG_MAGIC = 0x4653;
const FRAG_VERSION = 1;
const APP_MAGIC = 0x4D53;
const APP_VERSION = 2;
const MAX_NODES = 3, SAFE_FRAGMENT_DATA = 8;
const MAX_APP_PACKET = 384, MAX_FRAGMENTS = 48, MAX_FRAGMENT_DATA = 180;
const MAX_FIELD_PAYLOAD = 70;
const COMMANDER_VERSION = '1.0.4', EXPECTED_FIRMWARE_VERSION = '1.0.4';
const WIRE = Object.freeze({INFO:23,STATUS:42,HEALTH:17,SELF_DIAG:43,FIELD:67,DIAG_HEADER:89,RADAR_HEADER:12});
const RECORD = Object.freeze({NEIGHBOR:29,ROUTE:9,KNOWN:4,MANIFEST:5,DIAG_ROUTE:56,LAB:15,RADAR:30});
const LAB_BLOCK = 1, LAB_METRIC = 2, MANUAL = 0xFFFFFFFF;
const OP = {GET_INFO:1,GET_STATUS:2,GET_NEIGHBORS:3,GET_ROUTES:4,SEND:5,START_FIELD:8,STOP_FIELD:9,GET_FIELD:10,CLEAR_STATS:12,GET_KNOWN:15,GET_MANIFEST:16,SET_MANIFEST:17,DISCOVER:18,GET_DIAG:19,INJECT_FAIL:20,CLEAR_ROUTES:21,SET_LAB:22,GET_LAB:23,GET_RADAR:28,CLEAR_RADAR:29,GET_HEALTH:30,GET_SELF_DIAG:31};
const STATUS = ['Готово','Команда недоступна','Неверные параметры','Нет доступа','Функция недоступна','Устройство занято','Путь не найден','Передача перегружена','Радиосвязь недоступна','Защищённая связь недоступна','Проверка уже идёт','Проверка не запущена','Нет ответа','Внутренняя ошибка'];
const ROUTE_SOURCE = ['НЕТ','НАПРЯМУЮ','ЧЕРЕЗ СЕТЬ','ЗАПАСНОЙ ПУТЬ','ЗАДАН ВРУЧНУЮ'];
const EVENT_NAME = {19:'Ищем путь связи',20:'Повторный поиск пути',21:'Путь связи готов',22:'Запасной путь готов',23:'Запасной путь недоступен',24:'Переход на запасной путь',25:'Путь связи потерян',26:'Список узлов изменён',27:'Обнаружен новый узел',28:'Позиция обновлена',29:'SOS',30:'SOS подтверждён',31:'Команда получена',32:'Состояние узла изменилось'};

const $ = id => document.getElementById(id);
const PREFS_KEY = 'securemesh-console-v104-prefs';
const EVENTS_KEY = 'securemesh-console-v104-events';
const LEGACY_PREFS_KEYS = ['securemesh-console-v103-prefs','securemesh-console-v100-prefs','securemesh-console-v094-prefs'];
const LEGACY_EVENTS_KEYS = ['securemesh-console-v103-events','securemesh-console-v100-events','securemesh-console-v094-events'];
function readJson(key,fallback){try{const v=JSON.parse(localStorage.getItem(key)||'null');return v??fallback}catch(_){return fallback}}
function readMigrated(primary,legacyKeys,fallback){const current=readJson(primary,null);if(current!==null)return current;for(const key of legacyKeys){const value=readJson(key,null);if(value!==null)return value}return fallback}
const persistedPrefs=readMigrated(PREFS_KEY,LEGACY_PREFS_KEYS,{names:{},pinned:{},trustedBle:{}});
const persistedEvents=readMigrated(EVENTS_KEY,LEGACY_EVENTS_KEYS,[]);
const state = {nodes:[],events:Array.isArray(persistedEvents)?persistedEvents.slice(0,200):[],nextReq:1,nextTransport:1,polling:false,selectedNode:null,fieldTimer:null,installPrompt:null,uiPrefs:persistedPrefs,linkQuality:new Map(),radarTimer:null,radarBusy:false,healthAlertAt:new Map(),radarHistory:new Map(),fieldRecord:{active:false,startedAt:0,finishedAt:0,sourceId:0,targetId:0,samples:[]},autoTest:{running:false,abort:false,step:-1,steps:[],report:null},model:{count:5,links:{},primary:null,backup:null}};

function toast(text,type=''){const el=document.createElement('div');el.className='toast '+type;el.textContent=text;$('toastHost').appendChild(el);setTimeout(()=>el.remove(),4200)}
function hex(v){return (Number(v)>>>0).toString(16).toUpperCase().padStart(8,'0')}
function fmtPct(v){return Number.isFinite(v)?`${(v*100).toFixed(1)}%`:'—'}
function fmtMs(v){if(v==null)return '—';if(v<1000)return `${v} ms`;return `${(v/1000).toFixed(2)} s`}
function bytesToHex(arr){return [...arr].map(x=>x.toString(16).padStart(2,'0')).join(' ')}
function u16(a,o){return a[o]|(a[o+1]<<8)}
function i16(a,o){let v=u16(a,o);return v&0x8000?v-0x10000:v}
function u32(a,o){return (a[o]|(a[o+1]<<8)|(a[o+2]<<16)|(a[o+3]<<24))>>>0}
function put16(a,o,v){a[o]=v&255;a[o+1]=(v>>>8)&255}
function put32(a,o,v){v>>>=0;a[o]=v&255;a[o+1]=(v>>>8)&255;a[o+2]=(v>>>16)&255;a[o+3]=(v>>>24)&255}
function concat(...parts){const n=parts.reduce((s,p)=>s+p.length,0),out=new Uint8Array(n);let o=0;for(const p of parts){out.set(p,o);o+=p.length}return out}
function metric(label,value,small=''){return `<div class="metric"><span>${label}</span><strong>${value}</strong>${small?`<small>${small}</small>`:''}</div>`}
function nowTime(){return new Date().toLocaleTimeString('ru-RU',{hour12:false})}

function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function savePrefs(){try{localStorage.setItem(PREFS_KEY,JSON.stringify(state.uiPrefs))}catch(_){}}
function persistEvents(){try{localStorage.setItem(EVENTS_KEY,JSON.stringify(state.events.slice(0,200)))}catch(_){}}
function nodePref(id){const k=String(id>>>0);return{name:state.uiPrefs.names?.[k]||'',pinned:!!state.uiPrefs.pinned?.[k]}}
function applyNodePrefs(n){if(!n?.id)return;const p=nodePref(n.id);if(p.name)n.label=p.name;n.pinned=p.pinned}
function setNodeName(n,name){if(!n?.id)return;const clean=String(name||'').trim().slice(0,24),k=String(n.id>>>0);state.uiPrefs.names=state.uiPrefs.names||{};if(clean)state.uiPrefs.names[k]=clean;else delete state.uiPrefs.names[k];n.label=clean||n.baseLabel||n.label;savePrefs();renderAll()}
function togglePinned(n){if(!n?.id)return;const k=String(n.id>>>0);state.uiPrefs.pinned=state.uiPrefs.pinned||{};state.uiPrefs.pinned[k]=!state.uiPrefs.pinned[k];n.pinned=!!state.uiPrefs.pinned[k];savePrefs();renderAll()}
function clamp(v,a,b){return Math.max(a,Math.min(b,v))}
function scale(v,lo,hi){return clamp((v-lo)/(hi-lo),0,1)*100}
function smoothedPdr(value,observations,prior=72,priorWeight=4){const n=clamp(Number(observations)||0,0,40);return ((clamp(Number(value)||0,0,100)*n)+(prior*priorWeight))/(n+priorWeight)}
function observationScore(n){
  if(!n||!n.fresh)return 0;
  const rssi=scale(n.rssi,-125,-67),snr=scale(n.snr,-15,10);
  const hello=smoothedPdr(n.helloPdr,n.rx,72,4),ack=n.attempts>=2?smoothedPdr(n.ackPdr,n.attempts,72,4):null;
  let sum=rssi*.31+snr*.20+hello*.27,weight=.78;
  if(ack!=null){sum+=ack*.22;weight+=.22}
  let score=sum/weight;
  const agePenalty=n.age<=3500?1:n.age<=9000?1-(n.age-3500)/11000:n.age<=15000?.5:.25;
  score*=agePenalty;
  const evidence=clamp(((n.rx||0)+(n.attempts||0))/18,0,1);
  score=score*(.90+.10*evidence);
  return clamp(score,0,100);
}
function pairObservation(a,b){
  const ab=a?.neighbors?.find(x=>x.id===b?.id&&x.fresh),ba=b?.neighbors?.find(x=>x.id===a?.id&&x.fresh);
  const vals=[ab,ba].filter(Boolean).map(observationScore);if(!vals.length)return null;
  // Two-sided measurements are stronger evidence; use harmonic-like penalty when one side is weak.
  const raw=vals.length===2?(2*vals[0]*vals[1]/Math.max(1,vals[0]+vals[1])):vals[0];
  const fingerprint=[ab,ba].filter(Boolean).map(x=>`${x.rx}:${x.attempts}:${x.acks}`).join('|');
  return{raw,ab,ba,fingerprint};
}
const QUALITY_ORDER=['lost','poor','unstable','good','excellent'];
function qualityBand(score){if(score>=84)return'excellent';if(score>=66)return'good';if(score>=45)return'unstable';if(score>=24)return'poor';return'lost'}
function qualityMeta(band){return({
  excellent:{label:'ОТЛИЧНО',hint:'стабильный запас',bars:4},
  good:{label:'ХОРОШО',hint:'нормальная связь',bars:3},
  unstable:{label:'НЕСТАБИЛЬНО',hint:'возможны повторы',bars:2},
  poor:{label:'ПЛОХО',hint:'связь на грани',bars:1},
  lost:{label:'НЕТ СВЯЗИ',hint:'давно нет ответа',bars:0}
})[band]||{label:'—',hint:'нет данных',bars:0}}
function analyseTrend(history){
  if(!history||history.length<4)return{state:'stable',delta:0,confidence:0,volatility:0};
  const h=history.slice(-10),n=h.length,first=h[0],last=h[n-1],delta=last-first;
  let sx=0,sy=0,sxx=0,sxy=0;for(let i=0;i<n;i++){sx+=i;sy+=h[i];sxx+=i*i;sxy+=i*h[i]}
  const den=n*sxx-sx*sx,slope=den?(n*sxy-sx*sy)/den:0;
  let volatility=0;for(let i=1;i<n;i++)volatility+=Math.abs(h[i]-h[i-1]);volatility/=Math.max(1,n-1);
  const direction=Math.abs(slope)<.65&&Math.abs(delta)<5?'stable':slope>0?'rising':'falling';
  const directionalMoves=h.slice(1).filter((v,i)=>direction==='rising'?v>=h[i]:direction==='falling'?v<=h[i]:Math.abs(v-h[i])<4).length;
  const confidence=clamp((n/8)*.45+(directionalMoves/Math.max(1,n-1))*.45+(1-clamp(volatility/12,0,1))*.10,0,1);
  return{state:direction,delta,slope,confidence,volatility};
}
function pairQuality(a,b){
  const key=[a?.id||0,b?.id||0].sort((x,y)=>x-y).join(':');const obs=pairObservation(a,b);
  let q=state.linkQuality.get(key);
  if(!obs){if(q){q.band='lost';q.lost=true;q.trendInfo={state:'stable',delta:0,confidence:0,volatility:0};state.linkQuality.set(key,q);return q}return{score:0,band:'lost',trend:0,history:[],lost:true,trendInfo:{state:'stable',delta:0,confidence:0,volatility:0}}}
  if(!q)q={score:obs.raw,band:qualityBand(obs.raw),trend:0,history:[],fingerprint:'',lastAlertAt:0,lost:false,trendInfo:{state:'stable',delta:0,confidence:0,volatility:0}};
  if(q.lost){q.score=obs.raw;q.band=qualityBand(obs.raw);q.history=[obs.raw];q.trend=0;q.trendInfo=analyseTrend(q.history);q.fingerprint=obs.fingerprint;q.lost=false;state.linkQuality.set(key,q);return q}
  if(q.fingerprint!==obs.fingerprint){
    const previous=q.score;q.score=previous*.72+obs.raw*.28;q.history.push(q.score);q.history=q.history.slice(-10);q.trendInfo=analyseTrend(q.history);q.trend=q.trendInfo.delta;q.fingerprint=obs.fingerprint;
    const candidate=qualityBand(q.score),oldIndex=QUALITY_ORDER.indexOf(q.band),newIndex=QUALITY_ORDER.indexOf(candidate);
    if(newIndex>oldIndex){const thresholds={poor:24,unstable:45,good:66,excellent:84};if(q.score>=(thresholds[candidate]||0)+4)q.band=candidate}
    else if(newIndex<oldIndex){const lower={excellent:84,good:66,unstable:45,poor:24};if(q.score<(lower[q.band]||0)-5)q.band=candidate}
    if(a&&b&&q.trendInfo.state==='falling'&&q.trendInfo.confidence>=.72&&q.trendInfo.delta<=-10&&Date.now()-(q.lastAlertAt||0)>30000){q.lastAlertAt=Date.now();addEvent(null,`Связь ${a.label} ↔ ${b.label} устойчиво ухудшается`)}
  }
  state.linkQuality.set(key,q);return q;
}
function nodeQuality(n){
  const peers=connectedNodes().filter(x=>x!==n),qs=peers.map(p=>pairQuality(n,p)).filter(q=>q.band!=='lost');
  if(!qs.length)return{score:0,band:'lost',trend:0};
  qs.sort((a,b)=>b.score-a.score);return qs[0];
}
function qualityBadge(q){const m=qualityMeta(q?.band);return`<span class="quality-chip q-${q?.band||'lost'}"><i>${m.bars}</i>${m.label}<b>${trendArrow(q)}</b></span>`}
function trendText(q){const t=q?.trendInfo;if(!t||t.confidence<.45)return'тренд набирает данные';if(t.state==='rising')return t.delta>=12?'быстро усиливается':'усиливается';if(t.state==='falling')return t.delta<=-12?'быстро ослабевает':'ослабевает';return t.volatility>7?'колеблется':'стабильно'}
function trendArrow(q){const t=q?.trendInfo;return t?.state==='rising'?'↑':t?.state==='falling'?'↓':'→'}
function setBleTrusted(hash,value){state.uiPrefs.trustedBle=state.uiPrefs.trustedBle||{};if(value)state.uiPrefs.trustedBle[String(hash>>>0)]=true;else delete state.uiPrefs.trustedBle[String(hash>>>0)];savePrefs();renderRadar()}
function isBleTrusted(hash){return!!state.uiPrefs.trustedBle?.[String(hash>>>0)]}
function openNodeEditor(n){
  if(!n?.id)return;
  $('nodeEditTitle').textContent=`Настройка · ${hex(n.id)}`;$('nodeNameInput').value=n.label;$('nodePinInput').checked=!!n.pinned;
  $('nodeEditModal').classList.add('open');$('nodeEditModal').dataset.nodeId=String(n.id);setTimeout(()=>$('nodeNameInput').focus(),50)
}
function closeNodeEditor(){$('nodeEditModal').classList.remove('open');delete $('nodeEditModal').dataset.nodeId}
function saveNodeEditor(){const n=nodeById(Number($('nodeEditModal').dataset.nodeId));if(!n)return closeNodeEditor();setNodeName(n,$('nodeNameInput').value);const want=$('nodePinInput').checked;if(!!n.pinned!==want)togglePinned(n);closeNodeEditor()}


class Reassembler {
  constructor(onPacket){this.onPacket=onPacket;this.active=null}
  push(data){
    const a=new Uint8Array(data.buffer,data.byteOffset,data.byteLength);
    if(a.length<12||u16(a,0)!==FRAG_MAGIC||a[2]!==FRAG_VERSION)return;
    const tid=u16(a,3),idx=a[5],cnt=a[6],total=u16(a,7),off=u16(a,9),len=a[11];
    if(!tid||!cnt||cnt>MAX_FRAGMENTS||idx>=cnt||!total||total>MAX_APP_PACKET||!len||len>MAX_FRAGMENT_DATA||a.length!==12+len||off+len>total)return;
    if(!this.active||this.active.tid!==tid){if(idx!==0||off!==0)return;this.active={tid,cnt,total,next:0,off:0,buf:new Uint8Array(total)}}
    const x=this.active;if(idx!==x.next||off!==x.off||cnt!==x.cnt||total!==x.total){this.active=null;return}
    x.buf.set(a.slice(12),off);x.next++;x.off+=len;
    if(x.next===x.cnt&&x.off===x.total){const p=x.buf;this.active=null;this.onPacket(p)}
  }
}

class MeshNode {
  constructor(device,label){this.device=device;this.baseLabel=label;this.label=label;this.pinned=false;this.connected=false;this.id=0;this.info=null;this.status=null;this.neighbors=[];this.routes=[];this.manifest=null;this.known=[];this.diag=null;this.lab=[];this.field=null;this.radar=null;this.pending=new Map();this.busy=false;this.lastSeen=0;this.extendedAt=0;this.commandChain=Promise.resolve();this.disconnectBound=false;this.disconnectHandler=null;this.respHandler=null;this.eventHandler=null;this.reResp=new Reassembler(p=>this.onPacket(p));this.reEvent=new Reassembler(p=>this.onPacket(p))}
  async connect(){
    try{
      if(this.respChar&&this.respHandler)this.respChar.removeEventListener('characteristicvaluechanged',this.respHandler);
      if(this.eventChar&&this.eventHandler)this.eventChar.removeEventListener('characteristicvaluechanged',this.eventHandler);
      this.reResp.active=null;this.reEvent.active=null;
      this.gatt=await this.device.gatt.connect();const svc=await this.gatt.getPrimaryService(SERVICE);
      this.infoChar=await svc.getCharacteristic(INFO);this.cmdChar=await svc.getCharacteristic(COMMAND);this.respChar=await svc.getCharacteristic(RESPONSE);this.eventChar=await svc.getCharacteristic(EVENT);
      await this.respChar.startNotifications();await this.eventChar.startNotifications();
      this.respHandler=e=>this.reResp.push(e.target.value);this.eventHandler=e=>this.reEvent.push(e.target.value);
      this.respChar.addEventListener('characteristicvaluechanged',this.respHandler);
      this.eventChar.addEventListener('characteristicvaluechanged',this.eventHandler);
      if(!this.disconnectBound){this.disconnectHandler=()=>{this.connected=false;this.busy=false;this.reResp.active=null;this.reEvent.active=null;this.rejectPending(new Error('Связь с телефоном потеряна'));handleNodeDisconnect(this);addEvent(this,'Связь с телефоном потеряна');renderAll()};this.device.addEventListener('gattserverdisconnected',this.disconnectHandler);this.disconnectBound=true}
      try{await this.infoChar.readValue()}catch(_){/* protected read can trigger pairing */}
      this.connected=true;this.lastSeen=Date.now();
      let ok=false,err=null;
      for(let i=0;i<12&&!ok;i++){
        try{const r=await this.command(OP.GET_INFO,new Uint8Array(),3500);if(r.status===0){const info=decodeInfo(r.payload);if(!info)throw new Error('Узел передал повреждённые данные');this.info=info;this.id=info.nodeId;applyNodePrefs(this);ok=true;break}}catch(e){err=e}
        await new Promise(r=>setTimeout(r,900));
      }
      if(!ok)throw err||new Error('Не удалось установить защищённое соединение. Подтверди код на устройстве и телефоне.');
      if(this.info.protocol!==APP_VERSION)throw new Error('Версия устройства несовместима с этим приложением');
      if(this.info.fw!==EXPECTED_FIRMWARE_VERSION)addEvent(this,`Версия устройства ${this.info.fw}; рекомендуется ${EXPECTED_FIRMWARE_VERSION}`);
      addEvent(this,`Подключён ${this.label} · ${hex(this.id)}`);await this.refresh(true);
    }catch(e){
      this.connected=false;this.busy=false;this.reResp.active=null;this.reEvent.active=null;this.rejectPending(e instanceof Error?e:new Error(String(e)));
      if(this.respChar&&this.respHandler)this.respChar.removeEventListener('characteristicvaluechanged',this.respHandler);
      if(this.eventChar&&this.eventHandler)this.eventChar.removeEventListener('characteristicvaluechanged',this.eventHandler);
      this.respHandler=null;this.eventHandler=null;
      try{if(this.device.gatt?.connected)this.device.gatt.disconnect()}catch(_){}
      throw e;
    }
  }
  onPacket(a){
    if(a.length<10||u16(a,0)!==APP_MAGIC||a[2]!==APP_VERSION)return;
    const type=a[3],req=u16(a,4),opcode=a[6],status=a[7],len=u16(a,8);if(10+len!==a.length)return;
    const payload=a.slice(10);
    if(type===2){const p=this.pending.get(req);if(p){clearTimeout(p.timer);this.pending.delete(req);if(p.opcode!==opcode){p.reject(new Error('Получен неожиданный ответ от устройства'));return}p.resolve({opcode,status,payload})}}
    else if(type===3)this.handleEvent(opcode,payload);
  }
  handleEvent(type,payload){
    let text=EVENT_NAME[type]||'Служебное событие';
    if(type>=19&&type<=25&&payload.length>=17){const d=u32(payload,1),nh=u32(payload,5);if(d)text+=` · к ${nodeLabelById(d)}`;if(type===21&&nh)text+=` · через ${nodeLabelById(nh)}`}
    else if(type===27&&payload.length>=4)text+=` · ${nodeLabelById(u32(payload,0))}`;
    else if(type===28&&payload.length>=35)text+=` · ${nodeLabelById(u32(payload,0))}`;
    else if(type===29&&payload.length>=29){const origin=u32(payload,0),sosType=payload[5],sosId=u32(payload,8);text=`SOS · ${nodeLabelById(origin)}`}
    else if(type===30&&payload.length>=12){text=`SOS подтверждён · ${nodeLabelById(u32(payload,0))} · кем ${nodeLabelById(u32(payload,8))}`}
    else if(type===31&&payload.length>=24){const origin=u32(payload,0),kind=payload[5],labels=['ВЕРНИСЬ','ДАЙ СТАТУС','ОСТАВАЙСЯ','ИДИ К ТОЧКЕ'];text=`Команда · ${nodeLabelById(origin)} · ${labels[kind]||'НЕИЗВЕСТНАЯ КОМАНДА'}`}
    else if(type===32&&payload.length>=4){const score=payload[0],level=payload[1],flags=u16(payload,2),label=level===3?'ОТЛИЧНО':level===2?'ХОРОШО':level===1?'ТРЕБУЕТ ВНИМАНИЯ':'КРИТИЧЕСКИ';text=`Проверка устройства: ${label} · ${score}/100`;if(flags&(HEALTH_FLAGS.RADIO|HEALTH_FLAGS.CRYPTO))text+=' · важная часть устройства недоступна'}
    addEvent(this,text);setTimeout(()=>this.refresh().catch(()=>{}),250);
  }
  async writeAppPacket(packet){
    let tid=state.nextTransport++&0xFFFF;if(!tid)tid=state.nextTransport++&0xFFFF;
    if(!packet.length||packet.length>MAX_APP_PACKET)throw new Error('Слишком большой объём данных');
    const cnt=Math.ceil(packet.length/SAFE_FRAGMENT_DATA);if(cnt>MAX_FRAGMENTS)throw new Error('Слишком большой объём данных');
    for(let i=0,off=0;i<cnt;i++){
      const n=Math.min(SAFE_FRAGMENT_DATA,packet.length-off),f=new Uint8Array(12+n);put16(f,0,FRAG_MAGIC);f[2]=FRAG_VERSION;put16(f,3,tid);f[5]=i;f[6]=cnt;put16(f,7,packet.length);put16(f,9,off);f[11]=n;f.set(packet.slice(off,off+n),12);
      if(this.cmdChar.writeValueWithResponse)await this.cmdChar.writeValueWithResponse(f);else await this.cmdChar.writeValue(f);off+=n;
    }
  }
  rejectPending(error){for(const [req,p] of this.pending){clearTimeout(p.timer);p.reject(error)}this.pending.clear()}
  async command(op,payload=new Uint8Array(),timeout=7000){
    const run=()=>this.commandNow(op,payload,timeout);
    const result=this.commandChain.then(run,run);
    this.commandChain=result.catch(()=>{});
    return result;
  }
  async commandNow(op,payload=new Uint8Array(),timeout=7000){
    if(!this.connected)throw new Error('Узел отключён');let req=state.nextReq++&0xFFFF;if(!req)req=state.nextReq++&0xFFFF;
    const p=new Uint8Array(10+payload.length);put16(p,0,APP_MAGIC);p[2]=APP_VERSION;p[3]=1;put16(p,4,req);p[6]=op;p[7]=0;put16(p,8,payload.length);p.set(payload,10);
    const promise=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.pending.delete(req);reject(new Error('Устройство не ответило вовремя'))},timeout);this.pending.set(req,{resolve,reject,timer,opcode:op})});
    try{await this.writeAppPacket(p)}catch(e){const x=this.pending.get(req);if(x){clearTimeout(x.timer);this.pending.delete(req)}throw e}
    return promise;
  }
  async refresh(full=false){
    if(this.busy||!this.connected)return;this.busy=true;
    try{
      const fast=[[OP.GET_STATUS,'status',decodeStatus],[OP.GET_NEIGHBORS,'neighbors',decodeNeighbors],[OP.GET_ROUTES,'routes',decodeRoutes],[OP.GET_HEALTH,'health',decodeHealth]];
      const needExtended=full||!this.extendedAt||Date.now()-this.extendedAt>=15000;
      const extended=[[OP.GET_MANIFEST,'manifest',decodeManifest],[OP.GET_KNOWN,'known',decodeKnown],[OP.GET_DIAG,'diag',decodeDiag],[OP.GET_LAB,'lab',decodeLabPolicies],[OP.GET_SELF_DIAG,'selfDiag',decodeSelfDiag]];
      let successes=0;
      for(const [op,key,fn] of (needExtended?fast.concat(extended):fast)){
        try{const r=await this.command(op,new Uint8Array(),4500);if(r.status===0){const decoded=fn(r.payload);if(decoded!==null&&decoded!==undefined){this[key]=decoded;successes++}}}catch(_){}
      }
      if(needExtended)this.extendedAt=Date.now();
      if(successes)this.lastSeen=Date.now();
    } finally {this.busy=false;renderAll()}
  }
}

function decodeInfo(a){if(!a||a.length!==WIRE.INFO)return null;return{protocol:a[0],mesh:a[1],message:a[2],fw:`${a[3]}.${a[4]}.${a[5]}`,nodeId:u32(a,6),role:a[10],caps:u32(a,11),networkId:u16(a,15),bleState:a[17],security:a[18],permissions:u32(a,19)}}
function decodeStatus(a){if(!a||a.length!==WIRE.STATUS)return null;let o=0;const x={nodeId:u32(a,o)};o+=4;x.uptime=u32(a,o);o+=4;x.radio=!!a[o++];x.crypto=!!a[o++];x.ble=a[o++];x.fresh=a[o++];x.staticRoutes=a[o++];x.txQueue=a[o++];x.rxValid=u32(a,o);o+=4;x.txFrames=u32(a,o);o+=4;x.ackSuccess=u32(a,o);o+=4;x.ackTimeout=u32(a,o);o+=4;x.authFail=u32(a,o);o+=4;x.freeHeap=u32(a,o);o+=4;x.largestHeap=u32(a,o);return x}
function decodeNeighbors(a){if(!a||a.length<1)return null;const count=a[0];if(a.length!==1+count*RECORD.NEIGHBOR)return null;const out=[];let o=1;for(let i=0;i<count;i++){out.push({id:u32(a,o),age:u32(a,o+4),rssi:i16(a,o+8)/10,snr:i16(a,o+10)/10,helloPdr:u16(a,o+12)/10,ackPdr:u16(a,o+14)/10,rx:u32(a,o+16),attempts:u32(a,o+20),acks:u32(a,o+24),fresh:!!a[o+28]});o+=RECORD.NEIGHBOR}return out}
function decodeRoutes(a){if(!a||a.length<1)return null;const count=a[0];if(a.length!==1+count*RECORD.ROUTE)return null;const out=[];let o=1;for(let i=0;i<count;i++){out.push({destination:u32(a,o),nextHop:u32(a,o+4),source:a[o+8]});o+=RECORD.ROUTE}return out}
function decodeKnown(a){if(!a||a.length<1)return null;const count=a[0];if(a.length!==1+count*RECORD.KNOWN)return null;const out=[];for(let i=0,o=1;i<count;i++,o+=RECORD.KNOWN)out.push(u32(a,o));return out}
function decodeManifest(a){if(!a||a.length<10)return null;const count=a[9];if(a.length!==10+count*RECORD.MANIFEST)return null;const x={valid:!!a[0],epoch:u32(a,1),digest:u32(a,5),count,entries:[]};let o=10;for(let i=0;i<count;i++){x.entries.push({slot:a[o],id:u32(a,o+1)});o+=RECORD.MANIFEST}return x}
function decodeDiag(a){if(!a||a.length<WIRE.DIAG_HEADER||a[0]!==2)return null;const count=a[WIRE.DIAG_HEADER-1];if(a.length!==WIRE.DIAG_HEADER+count*RECORD.DIAG_ROUTE)return null;let o=0;const x={version:a[o++],manifestValid:!!a[o++],epoch:u32(a,o)};o+=4;x.digest=u32(a,o);o+=4;x.routeSeq=u32(a,o);o+=4;for(const k of ['acceptedPrimary','acceptedBackup','acceptedAlternate','rejectedOld','rejectedLoop','rejectedInfeasible','rejectedWorse','rejectedSamePath','promotionsG2','promotionsAlternate','expirations','routeErrors','controlBudgetDrops','controlBudgetTokensUs','deferredQueued','deferredDrops']){x[k]=u32(a,o);o+=4}x.activeDeferred=a[o++];x.labRxDrops=u32(a,o);o+=4;x.labTxDrops=u32(a,o);o+=4;x.activeLab=a[o++];x.routeCount=a[o++];x.routes=[];for(let i=0;i<x.routeCount;i++){const r={destination:u32(a,o),primary:u32(a,o+4),backup:u32(a,o+8),alternate:u32(a,o+12),boot:u32(a,o+16),seq:u32(a,o+20),rank:u32(a,o+24),fd:u32(a,o+28),primaryMask:u32(a,o+32),backupMask:u32(a,o+36),primaryTag:u32(a,o+40),backupTag:u32(a,o+44),eca:u32(a,o+48)/65536,reliability:u16(a,o+52)/32767,flags:a[o+54],lease:a[o+55]};x.routes.push(r);o+=RECORD.DIAG_ROUTE}return x}
function decodeLabPolicies(a){if(!a||a.length<1)return null;const count=a[0];if(a.length!==1+count*RECORD.LAB)return null;const out=[];let o=1;for(let i=0;i<count;i++){out.push({peer:u32(a,o),flags:a[o+4],remaining:u32(a,o+5),reliability:u16(a,o+9)/32767,eca:u32(a,o+11)/65536});o+=RECORD.LAB}return out}
function decodeRadar(a){
  if(!a||a.length<WIRE.RADAR_HEADER||a[0]!==1)return null;const count=a[3];if(a.length!==WIRE.RADAR_HEADER+count*RECORD.RADAR)return null;let o=0;
  const x={version:a[o++],enabled:!!a[o++],scanning:!!a[o++],count:a[o++],cycle:u32(a,o),total:u32(a,o+4),items:[]};o+=8;
  for(let i=0;i<x.count;i++){
    const hash=u32(a,o),age=u32(a,o+4),presence=u32(a,o+8),rssi=(a[o+12]&0x80)?a[o+12]-256:a[o+12],peak=(a[o+13]&0x80)?a[o+13]-256:a[o+13],trend=(a[o+14]&0x80)?a[o+14]-256:a[o+14],hits=a[o+15],flags=a[o+16],nameLen=Math.min(a[o+17],12);
    let name='';for(let j=0;j<nameLen;j++){const c=a[o+18+j];if(c>=32&&c<127)name+=String.fromCharCode(c)}
    x.items.push({hash,age,presence,rssi,peak,trend,hits,flags,name});o+=RECORD.RADAR
  }
  return x;
}
function decodeHealth(a){if(!a||a.length!==WIRE.HEALTH||a[0]!==1)return null;let o=1;return{version:1,score:a[o++],level:a[o++],flags:u16(a,o),radio:a[o+2],mesh:a[o+3],routing:a[o+4],memory:a[o+5],queue:a[o+6],gps:a[o+7],ble:a[o+8],fresh:a[o+9],routes:a[o+10],g2:a[o+11],queueUsed:a[o+12],queueCap:a[o+13]}}
function decodeSelfDiag(a){if(!a||a.length!==WIRE.SELF_DIAG||a[0]!==1)return null;let o=1;const x={version:1,score:a[o++],level:a[o++],flags:u16(a,o)};o+=2;x.radio=!!a[o++];x.crypto=!!a[o++];x.ble=!!a[o++];x.gps=a[o++];x.oled=!!a[o++];x.fresh=a[o++];x.routes=a[o++];x.g2=a[o++];x.queueUsed=a[o++];x.queueCap=a[o++];x.freeHeap=u32(a,o);o+=4;x.largestHeap=u32(a,o);o+=4;x.ackSuccess=u32(a,o);o+=4;x.ackTimeout=u32(a,o);o+=4;x.txErrors=u32(a,o);o+=4;x.radioRecoveries=u32(a,o);o+=4;x.authFails=u32(a,o);return x}
function decodeField(a){if(!a||a.length!==WIRE.FIELD)return null;let o=0;const x={state:a[o++],mode:a[o++],testId:u32(a,o)};o+=4;x.target=u32(a,o);o+=4;x.elapsed=u32(a,o);o+=4;x.requested=u16(a,o);o+=2;for(const k of ['sent','firstHopAcked','firstHopFailed','firstHopRetries','replies','timeouts','sequence','lastNextHop']){x[k]=u32(a,o);o+=4}x.routeSource=a[o++];x.avgRtt=u32(a,o);o+=4;x.minRtt=u32(a,o);o+=4;x.maxRtt=u32(a,o);o+=4;x.pdr=u16(a,o)/1000;o+=2;x.rssi=i16(a,o)/10;o+=2;x.snr=i16(a,o)/10;return x}

function addEvent(node,text){state.events.unshift({time:nowTime(),ts:Date.now(),node:node?node.label:'LAB',text});state.events=state.events.slice(0,200);persistEvents();renderEvents();renderEventPreview()}
function nodeById(id){return state.nodes.find(n=>n.id===id)}
function connectedNodes(){return state.nodes.filter(n=>n.connected&&n.id)}
function nodeLabelById(id){const n=nodeById(id);return n?n.label:hex(id)}
function handleNodeDisconnect(node){
  if(state.fieldRecord.active&&state.fieldRecord.sourceId===node.id){state.fieldRecord.active=false;state.fieldRecord.finishedAt=Date.now();if(state.fieldTimer){clearInterval(state.fieldTimer);state.fieldTimer=null}renderFieldRecorder()}
  if(state.selectedNode===node)state.selectedNode=connectedNodes()[0]||node;
}
async function reconnectNode(node){
  if(!node||node.connected)return;
  try{toast(`Переподключение ${node.label}…`);await node.connect();state.selectedNode=node;toast(`${node.label} снова подключён`,'success');renderAll()}catch(e){toast(e.message||String(e),'error');renderAll()}
}

function forgetNode(node){
  if(!node||node.connected)return toast('Сначала отключи узел от BLE','error');
  if(state.fieldRecord.active&&state.fieldRecord.sourceId===node.id){state.fieldRecord.active=false;state.fieldRecord.finishedAt=Date.now();if(state.fieldTimer){clearInterval(state.fieldTimer);state.fieldTimer=null}}
  try{if(node.respChar&&node.respHandler)node.respChar.removeEventListener('characteristicvaluechanged',node.respHandler)}catch(_){}
  try{if(node.eventChar&&node.eventHandler)node.eventChar.removeEventListener('characteristicvaluechanged',node.eventHandler)}catch(_){}
  try{if(node.disconnectBound&&node.disconnectHandler)node.device.removeEventListener('gattserverdisconnected',node.disconnectHandler)}catch(_){}
  node.disconnectBound=false;node.disconnectHandler=null;node.respHandler=null;node.eventHandler=null;node.reResp.active=null;node.reEvent.active=null;node.rejectPending(new Error('Узел удалён из текущей сессии'));
  state.nodes=state.nodes.filter(n=>n!==node);
  if(node.id){const suffix=String(node.id>>>0);for(const key of [...state.linkQuality.keys()])if(key.split(':').includes(suffix))state.linkQuality.delete(key);state.healthAlertAt.delete(suffix)}
  if(state.selectedNode===node)state.selectedNode=connectedNodes()[0]||state.nodes[0]||null;
  addEvent(null,`Узел ${node.label} удалён из текущей сессии`);renderAll();
}

async function connectNode(){
  if(connectedNodes().length>=MAX_NODES)return toast('Все 3 RF-слота уже подключены','error');
  if(!navigator.bluetooth)return toast('Web Bluetooth недоступен. Используй Chrome/Edge с BLE.','error');
  let created=null;
  try{
    const device=await navigator.bluetooth.requestDevice({filters:[{services:[SERVICE]}],optionalServices:[SERVICE]});
    const existing=state.nodes.find(n=>n.device.id===device.id);if(existing){if(!existing.connected)await reconnectNode(existing);else toast(`${existing.label} уже подключён`);return}
    if(state.nodes.length>=MAX_NODES)return toast('Удали отключённый узел из сессии или переподключи его','error');
    const label=`Узел ${String.fromCharCode(65+state.nodes.length)}`;created=new MeshNode(device,label);state.nodes.push(created);renderAll();toast(`Подключение ${label}: подтверди BLE-код на устройстве`);
    await created.connect();state.selectedNode=created;toast(`${created.label} подключён: ${hex(created.id)}`,'success');renderAll();
  }catch(e){if(created&&!created.id){try{created.device.gatt?.disconnect()}catch(_){}state.nodes=state.nodes.filter(n=>n!==created);if(state.selectedNode===created)state.selectedNode=connectedNodes()[0]||null}toast(e.message||String(e),'error');renderAll()}
}

function setOptions(id,nodes,keep=true){const el=$(id);if(!el)return;const old=keep?el.value:'';el.innerHTML=nodes.map(n=>`<option value="${n.id}">${esc(n.label)} · ${hex(n.id)}</option>`).join('');if(old&&[...el.options].some(o=>o.value===old))el.value=old}

const HEALTH_FLAGS={RADIO:1<<0,CRYPTO:1<<1,NO_PEER:1<<2,QUEUE:1<<3,HEAP:1<<4,MANIFEST:1<<5,GPS:1<<6,BLE:1<<7,RECOVERY:1<<8,ACK:1<<9};
function healthMeta(h){
  if(!h)return{label:'НЕТ ДАННЫХ',cls:'unknown',hint:'телеметрия ещё не получена'};
  if(h.level===3)return{label:'ОТЛИЧНО',cls:'good',hint:'узел работает штатно'};
  if(h.level===2)return{label:'ХОРОШО',cls:'good',hint:'критических проблем нет'};
  if(h.level===1)return{label:'ТРЕБУЕТ ВНИМАНИЯ',cls:'warn',hint:'есть деградация'};
  return{label:'КРИТИЧЕСКИ',cls:'bad',hint:'есть отказ важного компонента'}
}
function healthProblems(n){const h=n?.health;if(!h)return['нет данных проверки'];const f=h.flags,out=[];if(f&HEALTH_FLAGS.RADIO)out.push('радиосвязь недоступна');if(f&HEALTH_FLAGS.CRYPTO)out.push('защита связи не готова');if(f&HEALTH_FLAGS.QUEUE)out.push('передача перегружена');if(f&HEALTH_FLAGS.HEAP)out.push('устройству не хватает памяти');if(f&HEALTH_FLAGS.ACK)out.push('растут потери сообщений');if(f&HEALTH_FLAGS.NO_PEER)out.push('другие узлы давно не отвечали');if(f&HEALTH_FLAGS.MANIFEST)out.push('список узлов не обновлён');if(f&HEALTH_FLAGS.GPS)out.push('нет свежей позиции GPS');if(f&HEALTH_FLAGS.BLE)out.push('связь с телефоном недоступна');return out}
function networkReadiness(){
  const ns=connectedNodes();if(!ns.length)return{score:0,label:'НЕ ПОДКЛЮЧЕНО',cls:'unknown',issues:['нет подключённых узлов']};
  const nodeScores=ns.map(n=>n.health?.score??0),nodeAvg=nodeScores.reduce((a,b)=>a+b,0)/ns.length;
  const pairs=[];for(let i=0;i<ns.length;i++)for(let j=i+1;j<ns.length;j++){const q=pairQuality(ns[i],ns[j]);if(q.band!=='lost')pairs.push(q.score)}
  const linkAvg=pairs.length?pairs.reduce((a,b)=>a+b,0)/pairs.length:(ns.length===1?70:25);
  let common=null,manifest=true;for(const n of ns){if(!n.manifest?.valid){manifest=false;break}const k=`${n.manifest.epoch}:${n.manifest.digest}`;if(common===null)common=k;else if(common!==k)manifest=false}
  const g2Routes=ns.reduce((sum,n)=>sum+(n.health?.g2||0),0),primaryRoutes=ns.reduce((sum,n)=>sum+(n.health?.routes||0),0),redundancy=primaryRoutes?clamp(g2Routes/primaryRoutes,0,1)*100:(ns.length<3?60:25);
  let score=nodeAvg*.48+linkAvg*.28+(manifest?100:35)*.14+redundancy*.10;
  if(ns.length===1)score=Math.min(score,45);else if(ns.length===2)score=Math.min(score,78);
  if(ns.some(n=>!n.status?.radio||!n.status?.crypto))score=Math.min(score,28);
  score=Math.round(clamp(score,0,100));const label=score>=86?'ГОТОВА':score>=70?'СТАБИЛЬНА':score>=48?'ТРЕБУЕТ ПРОВЕРКИ':'КРИТИЧЕСКИ';const cls=score>=70?'good':score>=48?'warn':'bad';
  const issues=[];if(ns.length<MAX_NODES)issues.push(`подключено ${ns.length}/${MAX_NODES} узлов`);for(const n of ns){const p=healthProblems(n).filter(x=>!['нет свежей позиции GPS'].includes(x));if(p.length)issues.push(`${n.label}: ${p[0]}`)}if(!manifest)issues.push('список узлов на устройствах различается');if(ns.length>=3&&primaryRoutes&&!g2Routes)issues.push('нет подтверждённого запасного пути');return{score,label,cls,issues,nodeAvg:Math.round(nodeAvg),linkAvg:Math.round(linkAvg),redundancy:Math.round(redundancy)}
}
function linkRisk(q){const t=q?.trendInfo;if(!q||q.band==='lost')return{level:'critical',label:'СВЯЗЬ ПОТЕРЯНА'};if(t?.state==='falling'&&t.confidence>=.75&&(t.delta<=-10||q.score<48))return{level:'high',label:'РИСК ПОТЕРИ СВЯЗИ'};if(q.score<50||t?.state==='falling'&&t.confidence>=.55)return{level:'watch',label:'НАБЛЮДАТЬ'};return{level:'normal',label:'СТАБИЛЬНО'}}
function maybeEmitHealthAlerts(){const now=Date.now();for(const n of connectedNodes()){const h=n.health;if(!h||h.level>1)continue;const k=String(n.id),last=state.healthAlertAt.get(k)||0;if(now-last<45000)continue;const p=healthProblems(n);if(p.length){state.healthAlertAt.set(k,now);addEvent(n,`Проверка устройства: ${p[0]}`)}}}
function renderNodeList(){
  const box=$('nodeList');box.innerHTML='';
  const ordered=[...state.nodes].sort((a,b)=>(Number(!!b.pinned)-Number(!!a.pinned))||(Number(!!b.connected)-Number(!!a.connected))||a.label.localeCompare(b.label,'ru'));
  for(const n of ordered){
    const q=nodeQuality(n),m=qualityMeta(q.band),el=document.createElement('div');el.className='node-card '+(state.selectedNode===n?'selected ':'')+(n.pinned?'pinned':'');
    const stale=n.connected&&n.lastSeen&&Date.now()-n.lastSeen>20000;
    el.innerHTML=`<div class="node-card-top"><div><strong>${esc(n.label)}</strong><span class="mono node-id">${n.id?('ID '+hex(n.id).slice(-4)):'ПОДКЛЮЧЕНИЕ'}</span></div><div class="node-card-actions">${n.pinned?'<span class="pin-mark" title="Закреплено">◆</span>':''}${!n.connected?'<button class="icon-btn reconnect-node" title="Переподключить" aria-label="Переподключить узел">↻</button><button class="icon-btn forget-node" title="Убрать из текущей сессии" aria-label="Убрать узел из текущей сессии">×</button>':''}<button class="icon-btn edit-node" title="Имя и закрепление" aria-label="Настроить узел">⋯</button><span class="node-state ${n.connected&&!stale?'on':stale?'stale':''}"></span></div></div><div class="node-card-quality">${qualityBadge(q)}<small>${stale?'данные устарели':`${m.hint} · ${trendText(q)}`}</small></div><small class="node-card-meta">${n.status?`${n.status.fresh} узл. рядом · данные обновлены`:(n.connected?'ожидание данных':'телефон не подключён')}</small>`;
    el.tabIndex=0;el.setAttribute('role','button');el.setAttribute('aria-label',`${n.label}, ${n.connected?'подключён':'отключён'}`);
    el.onclick=()=>{state.selectedNode=n;renderAll()};el.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();state.selectedNode=n;renderAll()}};el.querySelector('.edit-node').onclick=e=>{e.stopPropagation();openNodeEditor(n)};const rb=el.querySelector('.reconnect-node');if(rb)rb.onclick=e=>{e.stopPropagation();reconnectNode(n)};const fb=el.querySelector('.forget-node');if(fb)fb.onclick=e=>{e.stopPropagation();forgetNode(n)};box.appendChild(el)
  }
  $('nodeCountBadge').textContent=`${connectedNodes().length} / 3 УЗЛА`
}
function renderOverview(){
  const ns=connectedNodes(),readiness=networkReadiness();
  const pairs=[];for(let i=0;i<ns.length;i++)for(let j=i+1;j<ns.length;j++){const q=pairQuality(ns[i],ns[j]);if(q.band!=='lost')pairs.push(q)}
  const stable=pairs.filter(q=>['good','excellent'].includes(q.band)).length,degrading=pairs.filter(q=>linkRisk(q).level==='high').length;
  $('overviewMetrics').innerHTML=metric('Готовность сети',`${readiness.score}/100`,readiness.label)+metric('Узлы в норме',`${ns.filter(n=>(n.health?.level??0)>=2).length}/${ns.length||0}`,'по проверке устройств')+metric('Стабильные связи',`${stable}/${pairs.length||0}`)+metric('Риск',degrading?`${degrading} связь`:'НЕТ',degrading?'есть устойчивое ухудшение':'резких падений нет');
  const banner=$('operationalHealth');if(banner){banner.className=`operational-health ${readiness.cls}`;banner.style.setProperty('--score-angle',`${readiness.score*3.6}deg`);banner.innerHTML=`<div><span>ГОТОВНОСТЬ СЕТИ</span><strong>${readiness.label}</strong><small>${readiness.issues[0]?esc(readiness.issues[0]):'Сеть готова к штатной работе'}</small></div><b><em>${readiness.score}</em><small>/100</small></b>`}
  const sync=$('lastSyncBadge');if(sync){const last=Math.max(0,...ns.map(n=>n.lastSeen||0));sync.textContent=last?`ДАННЫЕ ${Math.max(0,Math.round((Date.now()-last)/1000))}с`:'НЕТ ДАННЫХ';sync.className=`pill ${last&&Date.now()-last<12000?'good':last?'warn':''}`}
  let common=null,manifestOk=ns.length>0;for(const n of ns){if(!n.manifest?.valid){manifestOk=false;continue}const key=`${n.manifest.epoch}:${n.manifest.digest}`;if(common===null)common=key;else if(common!==key)manifestOk=false}
  const g2Total=ns.reduce((x,n)=>x+(n.health?.g2||0),0),reserveReady=ns.length>=3&&manifestOk&&g2Total>0,hc=$('manifestHealth');hc.className='health-card '+(reserveReady?'good':ns.length?'bad':'unknown');
  if(reserveReady)hc.innerHTML=`<span>Запасной путь</span><strong>ГОТОВ</strong><small>сеть имеет независимый резерв</small>`;
  else if(ns.length<3)hc.innerHTML=`<span>Запасной путь</span><strong>НУЖНО 3 УЗЛА</strong><small>подключено ${ns.length}/3</small>`;
  else if(!manifestOk)hc.innerHTML='<span>Запасной путь</span><strong>ОБНОВИ СПИСОК УЗЛОВ</strong><small>обнови список на всех устройствах</small>';
  else hc.innerHTML='<span>Запасной путь</span><strong>ЕЩЁ НЕ ГОТОВ</strong><small>основной путь есть, независимый резерв пока не найден</small>';
  let html='<table class="data-table"><thead><tr><th>Узел</th><th>Состояние</th><th>Связь</th><th>Запасной путь</th><th>Обновление</th></tr></thead><tbody>';for(const n of ns){const q=nodeQuality(n),hm=healthMeta(n.health),problem=healthProblems(n)[0];html+=`<tr><td><b>${esc(n.label)}</b><small class="table-sub">ID ${hex(n.id).slice(-4)}</small></td><td><span class="health-pill ${hm.cls}">${hm.label}</span><small class="table-sub">${esc(problem||hm.hint)}</small></td><td>${qualityBadge(q)}</td><td>${n.health?.g2?'ЕСТЬ':'—'}</td><td>${n.lastSeen?`${Math.max(0,Math.round((Date.now()-n.lastSeen)/1000))} с назад`:'—'}</td></tr>`}html+='</tbody></table>';$('overviewNodes').innerHTML=html;
  const diag=$('selfDiagCards');if(diag)diag.innerHTML=ns.length?ns.map(n=>{const d=n.selfDiag,h=healthMeta(n.health);if(!d)return`<div class="diag-node"><strong>${esc(n.label)}</strong><span>ожидание данных</span></div>`;const comp=(ok,label)=>`<i class="${ok?'ok':'fail'}">${ok?'✓':'!'} ${label}</i>`;return`<div class="diag-node"><div><strong>${esc(n.label)}</strong><span class="health-pill ${h.cls}">${n.health?.score??0}/100</span></div><section>${comp(d.radio,'Радиосвязь')}${comp(d.crypto,'Защита')}${comp(d.ble,'Телефон')}${comp(d.oled,'Экран')}${comp(d.gps===2,'Навигация')}</section><small>${d.queueUsed>=d.queueCap?'Передача перегружена':d.txErrors?'Есть ошибки передачи':'Работает штатно'}${d.radioRecoveries?` · автоматических восстановлений: ${d.radioRecoveries}`:''}</small></div>`}).join(''):'<div class="status-box">Подключи узлы для проверки исправности.</div>';
  renderEventPreview();maybeEmitHealthAlerts();
}
function getPairPolicy(a,b){const pa=a.lab?.find(x=>x.peer===b.id),pb=b.lab?.find(x=>x.peer===a.id),p=pa||pb;if(!p)return 'live';if(p.flags&LAB_BLOCK)return 'block';if(p.flags&LAB_METRIC)return 'weak';return 'live'}
function isNeighbor(a,b){return !!(a.neighbors?.find(x=>x.id===b.id&&x.fresh)||b.neighbors?.find(x=>x.id===a.id&&x.fresh))}
function renderTopology(){
  const ns=connectedNodes(),svg=$('topologySvg'),w=760,h=480,cx=w/2,cy=h/2,r=Math.min(180,70+ns.length*22);let out='';const pos=new Map();ns.forEach((n,i)=>{const ang=-Math.PI/2+i*2*Math.PI/Math.max(1,ns.length);pos.set(n.id,[cx+r*Math.cos(ang),cy+r*Math.sin(ang)])});
  for(let i=0;i<ns.length;i++)for(let j=i+1;j<ns.length;j++){
    const a=ns[i],b=ns[j],[x1,y1]=pos.get(a.id),[x2,y2]=pos.get(b.id),pol=getPairPolicy(a,b),live=isNeighbor(a,b),q=pairQuality(a,b);
    const cls=pol==='block'?'block':pol==='weak'?'weak':live?`quality-${q.band}`:'lost';
    out+=`<line class="topo-link ${cls}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/><text class="topo-quality-label q-${q.band}" x="${(x1+x2)/2}" y="${(y1+y2)/2-8}" text-anchor="middle">${qualityMeta(q.band).label}</text>`
  }
  for(const n of ns){const [x,y]=pos.get(n.id),q=nodeQuality(n);out+=`<g class="topo-node connected q-${q.band}" transform="translate(${x},${y})"><circle r="42"/><text y="-5">${esc(n.label).slice(0,12)}</text><text class="sub" y="13">${qualityMeta(q.band).label}</text></g>`}
  if(!ns.length)out='<text x="380" y="240" text-anchor="middle" fill="#718096">Подключи узлы к телефону</text>';svg.innerHTML=out;
}
function renderRouting(){
  const ns=connectedNodes();setOptions('routingSource',ns);const src=nodeById(Number($('routingSource').value))||ns[0];
  if(!src?.diag){$('routingSummary').innerHTML=metric('Пути связи','—');$('routingTable').innerHTML='<div class="status-box">Данные о путях ещё не получены.</div>';return}
  const d=src.diag,protectedRoutes=d.routes.filter(r=>(r.flags&2)&&r.backup&&(r.primaryMask&r.backupMask)===0),activeBackup=d.routes.filter(r=>r.flags&4),routeHealth=src.health?.routing??0;
  $('routingSummary').innerHTML=metric('Пути связи',d.routes.length,d.routes.length?'готовы к передаче':'пути не найдены')+metric('С запасным путём',`${protectedRoutes.length}/${d.routes.length||0}`,protectedRoutes.length?'есть независимый резерв':'резерв пока не найден')+metric('Сейчас на резерве',activeBackup.length,activeBackup.length?'сеть уже перешла на запасной путь':'основные пути работают')+metric('Устойчивость',`${routeHealth}/100`,routeHealth>=85?'ОТЛИЧНО':routeHealth>=65?'ХОРОШО':'ТРЕБУЕТ ПРОВЕРКИ');
  let html='<table class="data-table"><thead><tr><th>Куда</th><th>Состояние</th><th>Основной путь</th><th>Запасной путь</th><th>Надёжность</th></tr></thead><tbody>';
  for(const r of d.routes){const g2=!!(r.flags&2),disjoint=(r.primaryMask&r.backupMask)===0,backupActive=!!(r.flags&4),protectedOk=g2&&disjoint;const status=backupActive?'РАБОТАЕТ ЗАПАСНОЙ':protectedOk?'ЕСТЬ РЕЗЕРВ':'БЕЗ РЕЗЕРВА';const cls=backupActive?'warn':protectedOk?'good':'unknown';html+=`<tr><td><b>${esc(nodeLabelById(r.destination))}</b><small class="table-sub mono">${hex(r.destination)}</small></td><td><span class="health-pill ${cls}">${status}</span></td><td>${esc(nodeLabelById(r.primary))}</td><td>${r.backup?esc(nodeLabelById(r.backup)):'—'}</td><td>${Math.round(r.reliability*100)}%</td></tr>`}
  html+='</tbody></table>';$('routingTable').innerHTML=html
}
function fieldReliability(f){if(!f||!f.sent)return{label:'—',detail:'нет измерений'};const p=f.pdr;if(p>=.98)return{label:'ОТЛИЧНАЯ',detail:'потерь почти нет'};if(p>=.92)return{label:'ХОРОШАЯ',detail:'редкие потери'};if(p>=.80)return{label:'НЕСТАБИЛЬНАЯ',detail:'есть заметные потери'};return{label:'ПЛОХАЯ',detail:'много сообщений не дошло'}}
function fieldLatency(ms){if(!ms)return{label:'—',detail:'пока нет ответа'};if(ms<350)return{label:'БЫСТРЫЙ',detail:'отклик почти мгновенный'};if(ms<900)return{label:'НОРМАЛЬНЫЙ',detail:'комфортная задержка'};if(ms<2000)return{label:'МЕДЛЕННЫЙ',detail:'задержка заметна'};return{label:'ОЧЕНЬ МЕДЛЕННЫЙ',detail:'связь перегружена или слабая'}}
function humanRoute(f){if(!f)return'—';if(f.routeSource===1)return'Прямая связь';if(f.routeSource===3)return f.lastNextHop?`Запасной путь через ${nodeLabelById(f.lastNextHop)}`:'Запасной путь';if(f.lastNextHop)return`Через ${nodeLabelById(f.lastNextHop)}`;if(f.routeSource===2)return'Через сеть';return'Путь уточняется'}
function fieldLinkQuality(src,f){if(!src||!f)return null;const hop=f.lastNextHop?nodeById(f.lastNextHop):nodeById(f.target);return hop?pairQuality(src,hop):null}
function renderField(){const ns=connectedNodes();setOptions('fieldSource',ns);setOptions('fieldDestination',ns);const src=nodeById(Number($('fieldSource').value));const f=src?.field;if(!f){$('fieldMetrics').innerHTML=metric('Надёжность','—')+metric('Ответ','—')+metric('Доставка','—')+metric('Путь','—');$('fieldStatusText').textContent='Проверка не запущена. Выбери два узла и нажми «Начать проверку».';renderFieldRecorder();return}
  const q=fieldLinkQuality(src,f),rel=fieldReliability(f),lat=fieldLatency(f.avgRtt),delivered=`${f.replies} из ${f.sent}`,risk=linkRisk(q);
  $('fieldMetrics').innerHTML=metric('Надёжность',rel.label,rel.detail)+metric('Ответ',lat.label,lat.detail)+metric('Доставлено',delivered,f.sent?`${Math.round(f.pdr*100)}% успешно`:'ожидание')+metric('Путь',esc(humanRoute(f)));
  const stateText=f.state===1?'Проверка идёт':f.state===2?'Проверка завершена':f.state===3?'Проверка остановлена':'Проверка требует внимания';
  const progress=f.requested?Math.min(100,Math.round(f.sent/f.requested*100)):0,trend=trendText(q),quality=qualityMeta(q?.band||'lost').label;
  $('fieldStatusText').innerHTML=`<strong>${stateText}.</strong> ${f.state===1?`Выполнено ${progress}%. `:''}Первый участок связи: <b>${quality}</b>, ${trend}. ${risk.level==='high'?'<em class="risk-high">Есть устойчивый риск потери связи.</em> ':''}${f.state===2?`${f.replies} из ${f.sent} контрольных сообщений дошли до цели и вернулись с ответом.`:'Вывод строится по серии измерений, а не по одному сообщению.'}`;
  renderFieldRecorder()
}
function captureFieldSample(src){const f=src?.field;if(!f||!state.fieldRecord.active||state.fieldRecord.sourceId!==src.id)return;const q=fieldLinkQuality(src,f),rel=fieldReliability(f),lat=fieldLatency(f.avgRtt),r=networkReadiness(),risk=linkRisk(q);const sample={time:new Date().toISOString(),source:src.label,target:nodeLabelById(f.target),quality:qualityMeta(q?.band||'lost').label,trend:trendText(q),reliability:rel.label,delivered:f.replies,sent:f.sent,success:f.sent?Math.round(f.pdr*100):0,response:lat.label,route:humanRoute(f),network:`${r.label} ${r.score}/100`,warning:risk.level==='high'?'устойчивое ухудшение связи':''};const last=state.fieldRecord.samples.at(-1);if(!last||last.sent!==sample.sent||last.quality!==sample.quality||last.trend!==sample.trend)state.fieldRecord.samples.push(sample);state.fieldRecord.samples=state.fieldRecord.samples.slice(-1200);renderFieldRecorder()}
function renderFieldRecorder(){const el=$('fieldRecorderSummary');if(!el)return;const r=state.fieldRecord,s=r.samples,first=s[0],last=s.at(-1);el.innerHTML=s.length?`<div class="recorder-status ${r.active?'active':''}"><i></i><div><strong>${r.active?'ЗАПИСЬ ИДЁТ':'ЗАПИСЬ ЗАВЕРШЕНА'}</strong><small>${s.length} контрольных точек · ${first?esc(first.source):'—'} → ${first?esc(first.target):'—'}</small></div></div><div class="recorder-human"><span>Последнее состояние</span><strong>${last?.quality||'—'} · ${last?.trend||'—'}</strong><small>${last?.delivered??0} из ${last?.sent??0} доставлено · ${last?.network||'—'}</small></div>`:'<div class="status-box">Журнал начнёт записываться автоматически вместе с проверкой связи.</div>'}
function csvCell(v){return`"${String(v??'').replaceAll('"','""')}"`}
function exportFieldCsv(){const rows=state.fieldRecord.samples;if(!rows.length)return toast('Сначала проведи проверку связи','error');const cols=[['Время','time'],['Откуда','source'],['Куда','target'],['Качество связи','quality'],['Тренд сигнала','trend'],['Надёжность','reliability'],['Доставлено','delivered'],['Отправлено','sent'],['Успех, %','success'],['Ответ','response'],['Маршрут','route'],['Состояние сети','network'],['Предупреждение','warning']];const csv='\ufeff'+cols.map(x=>csvCell(x[0])).join(';')+'\n'+rows.map(r=>cols.map(x=>csvCell(r[x[1]])).join(';')).join('\n');const blob=new Blob([csv],{type:'text/csv;charset=utf-8'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`SecureMesh_Field_${new Date().toISOString().replace(/[:.]/g,'-')}.csv`;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),500);toast('Полевой журнал сохранён','success')}
function clearFieldRecord(){state.fieldRecord={active:false,startedAt:0,finishedAt:0,sourceId:0,targetId:0,samples:[]};renderFieldRecorder();toast('Полевой журнал очищен')}

function renderEvents(){const html=state.events.map(e=>`<div class="timeline-item"><span class="event-time">${esc(e.time)}</span><span class="event-node">${esc(e.node)}</span><span class="event-text">${esc(e.text)}</span></div>`).join('')||'<div class="status-box">Журнал пуст.</div>';$('eventTimeline').innerHTML=html}
function renderEventPreview(){$('eventPreview').innerHTML=state.events.slice(0,6).map(e=>`<div class="timeline-item"><span class="event-time">${esc(e.time)}</span><span class="event-node">${esc(e.node)}</span><span class="event-text">${esc(e.text)}</span></div>`).join('')||'<div class="status-box">Событий пока нет.</div>'}
function renderSelects(){const ns=connectedNodes();for(const id of ['linkNodeA','linkNodeB','faultSource','faultDestination'])setOptions(id,ns);if(ns.length>1){if($('linkNodeB').value===$('linkNodeA').value)$('linkNodeB').value=String(ns[1].id);if($('faultDestination').value===$('faultSource').value)$('faultDestination').value=String(ns[1].id);setOptions('fieldSource',ns);setOptions('fieldDestination',ns);if($('fieldDestination').value===$('fieldSource').value)$('fieldDestination').value=String(ns[1].id);}}
function renderAll(){renderNodeList();renderOverview();renderSelects();renderEvents();const active=document.querySelector('.panel-tab.active')?.id?.replace('tab-','')||'overview';if(active==='topology')renderTopology();else if(active==='routing')renderRouting();else if(active==='field')renderField();else if(active==='radar')renderRadar()}

async function refreshAll(){const ns=connectedNodes();const r=await Promise.allSettled(ns.map(n=>n.refresh(true)));const failed=r.filter(x=>x.status==='rejected').length;toast(failed?`Обновлено с ошибками: ${failed}`:'Данные обновлены',failed?'error':'success')}
async function provisionManifest(){const ns=connectedNodes();if(ns.length<2)return toast('Нужно минимум 2 подключённых узла','error');const epoch=(Math.floor(Date.now()/1000)>>>0)||1,p=new Uint8Array(5+ns.length*4);put32(p,0,epoch);p[4]=ns.length;ns.forEach((n,i)=>put32(p,5+i*4,n.id));for(const n of ns){const r=await n.command(OP.SET_MANIFEST,p);if(r.status!==0)throw new Error(`${n.label}: ${STATUS[r.status]||r.status}`)}addEvent(null,`Список узлов обновлён на ${ns.length} устройствах`);await refreshAll()}
function labPayload(peer,flags,duration,rel=24575,eca=65536){const p=new Uint8Array(15);put32(p,0,peer);p[4]=flags;put32(p,5,duration);put16(p,9,rel);put32(p,11,eca);return p}
async function setPairPolicy(a,b,kind,duration=MANUAL){if(!a||!b||a===b)return toast('Выбери два разных узла','error');let flags=0,rel=24575,eca=65536;if(kind==='block')flags=LAB_BLOCK;if(kind==='weak'){flags=LAB_METRIC;rel=Math.round(.72*32767);eca=Math.round(2.8*65536)}if(kind==='veryWeak'){flags=LAB_METRIC;rel=Math.round(.48*32767);eca=Math.round(3.8*65536)}const dur=kind==='clear'?0:duration;for(const [x,y] of [[a,b],[b,a]]){const r=await x.command(OP.SET_LAB,labPayload(y.id,flags,dur,rel,eca));if(r.status!==0)throw new Error(`${x.label}: ${STATUS[r.status]}`)}addEvent(null,`${a.label} ↔ ${b.label}: ${{clear:'связь восстановлена',block:'связь оборвана',weak:'связь ослаблена',veryWeak:'связь сильно ослаблена'}[kind]||'условия связи изменены'}`);await Promise.all([a.refresh(),b.refresh()])}
async function clearAllLab(){const ns=connectedNodes();for(const n of ns){await n.refresh();for(const p of [...(n.lab||[])]){try{await n.command(OP.SET_LAB,labPayload(p.peer,0,0))}catch(_){}}}addEvent(null,'Искусственные ограничения связи сняты');await refreshAll()}
async function clearRoutes(){for(const n of connectedNodes())await n.command(OP.CLEAR_ROUTES);addEvent(null,'Найденные пути сброшены');await refreshAll()}
async function forceDiscovery(){const src=nodeById(Number($('faultSource').value)),dst=nodeById(Number($('faultDestination').value));if(!src||!dst||src===dst)return toast('Выбери начальный и конечный узел','error');const p=new Uint8Array(5);put32(p,0,dst.id);p[4]=1;const r=await src.command(OP.DISCOVER,p,20000);addEvent(src,`Перестроение пути к ${dst.label}: ${STATUS[r.status]||r.status}`);setTimeout(()=>src.refresh(),1200)}
async function presetTriangle(){await clearAllLab();await clearRoutes();toast('Все связи между тремя узлами доступны','success')}
async function presetChain(){const ns=connectedNodes();if(ns.length<3)return toast('Нужно 3 узла','error');await clearAllLab();await setPairPolicy(ns[0],ns[2],'block',MANUAL);await clearRoutes();toast(`${ns[0].label}—${ns[1].label}—${ns[2].label}: прямой ${ns[0].label}↔${ns[2].label} заблокирован`,'success')}
async function presetG2(){const ns=connectedNodes();if(ns.length<3)return toast('Нужно 3 узла','error');await clearAllLab();await setPairPolicy(ns[0],ns[2],'weak',MANUAL);await clearRoutes();$('faultSource').value=String(ns[0].id);$('faultDestination').value=String(ns[2].id);toast('Основной и запасной путь подготовлены. Теперь перестрой путь.','success')}
async function failPrimary(){const src=nodeById(Number($('faultSource').value)),dst=nodeById(Number($('faultDestination').value));if(!src||!dst)return toast('Выбери начальный и конечный узел','error');await src.refresh();const r=src.diag?.routes.find(x=>x.destination===dst.id);if(!r?.primary)return toast('Основной путь к этой цели ещё не найден','error');const hop=nodeById(r.primary);if(!hop)return toast('Первый узел основного пути сейчас недоступен','error');await setPairPolicy(src,hop,'block',30000);addEvent(null,`Основной путь ${src.label}↔${hop.label} временно оборван`)}

function applyFieldPreset(){const p=$('fieldPreset')?.value;if(p==='quick'){$('fieldCount').value='16';$('fieldInterval').value='450';$('fieldPayload').value='24'}else if(p==='deep'){$('fieldCount').value='120';$('fieldInterval').value='900';$('fieldPayload').value='32'}else{$('fieldCount').value='50';$('fieldInterval').value='800';$('fieldPayload').value='24'}}
async function startField(){const src=nodeById(Number($('fieldSource').value)),dst=nodeById(Number($('fieldDestination').value));if(!src||!dst||src===dst)return toast('Выбери два разных узла','error');const count=Math.max(1,Math.min(500,Number($('fieldCount').value)||50)),interval=Math.max(250,Number($('fieldInterval').value)||800),size=Math.max(1,Math.min(MAX_FIELD_PAYLOAD,Number($('fieldPayload').value)||24)),mode=Number($('fieldMode').value)||0,p=new Uint8Array(12);put32(p,0,dst.id);put16(p,4,count);put32(p,6,interval);p[10]=size;p[11]=mode;const r=await src.command(OP.START_FIELD,p);if(r.status!==0)return toast(`Проверка связи: ${STATUS[r.status]}`,'error');const decoded=decodeField(r.payload);if(!decoded)throw new Error('Устройство вернуло повреждённые данные проверки');src.field=decoded;state.fieldRecord={active:true,startedAt:Date.now(),finishedAt:0,sourceId:src.id,targetId:dst.id,samples:[]};captureFieldSample(src);addEvent(src,`Проверка связи → ${dst.label}`);beginFieldPolling(src);renderField()}
async function stopField(){const src=nodeById(state.fieldRecord.sourceId)||nodeById(Number($('fieldSource').value));if(!src)return;const r=await src.command(OP.STOP_FIELD);toast(`Остановка проверки: ${STATUS[r.status]||r.status}`);state.fieldRecord.active=false;state.fieldRecord.finishedAt=Date.now();clearInterval(state.fieldTimer);state.fieldTimer=null;try{const f=await src.command(OP.GET_FIELD,new Uint8Array(),5000);if(f.status===0){const d=decodeField(f.payload);if(d)src.field=d}}catch(_){}renderField()}
function beginFieldPolling(src){if(state.fieldTimer)clearInterval(state.fieldTimer);state.fieldTimer=setInterval(async()=>{if(!src.connected)return;try{const r=await src.command(OP.GET_FIELD,new Uint8Array(),5000);if(r.status===0){const decoded=decodeField(r.payload);if(!decoded)throw new Error('Повреждены данные текущей проверки');src.field=decoded;captureFieldSample(src);renderField();if(src.field&&src.field.state!==1){state.fieldRecord.active=false;state.fieldRecord.finishedAt=Date.now();clearInterval(state.fieldTimer);state.fieldTimer=null;addEvent(src,`Проверка завершена · ${src.field.replies} из ${src.field.sent} доставлено`)}}}catch(_){}},1400)}


const AUTO_TEST_LABELS = [
  'Проверка трёх узлов',
  'Список узлов',
  'Подготовка проверки',
  'Основной + запасной путь',
  'Проверка доставки',
  'Обрыв основного пути',
  'Переход на запасной путь',
  'Отчёт'
];
function sleep(ms){return new Promise(r=>setTimeout(r,ms))}
function setAutoStep(index,status='active',detail=''){
  state.autoTest.step=index;
  if(!state.autoTest.steps.length)state.autoTest.steps=AUTO_TEST_LABELS.map(label=>({label,status:'idle',detail:''}));
  if(index>=0&&index<state.autoTest.steps.length){state.autoTest.steps[index].status=status;state.autoTest.steps[index].detail=detail}
  renderAutoTest();
}
function completeAutoStep(index,detail=''){if(index>=0)setAutoStep(index,'done',detail)}
function failAutoStep(index,detail=''){if(index>=0)setAutoStep(index,'fail',detail)}
function renderAutoTest(){
  const box=$('autoTestProgress'),steps=$('autoTestSteps');if(!box||!steps)return;
  if(!state.autoTest.steps.length)state.autoTest.steps=AUTO_TEST_LABELS.map(label=>({label,status:'idle',detail:''}));
  const done=state.autoTest.steps.filter(x=>x.status==='done').length;
  const pct=Math.round(done/state.autoTest.steps.length*100);
  const current=state.autoTest.running?(state.autoTest.steps.find(x=>x.status==='active')?.label||'Выполняется'):(state.autoTest.report?.pass?'Последняя проверка: УСПЕШНО':state.autoTest.report?'Последняя проверка: ЕСТЬ ОШИБКА':'Сценарий не запущен');
  box.querySelector('.test-progress-head span').textContent=current;
  box.querySelector('.test-progress-head strong').textContent=`${pct}%`;
  box.querySelector('.progress-track i').style.width=`${pct}%`;
  steps.innerHTML=state.autoTest.steps.map(x=>`<div class="test-step ${x.status}">${esc(x.label)}${x.detail?` · ${esc(x.detail)}`:''}</div>`).join('');
  if($('autoTestBtn'))$('autoTestBtn').disabled=state.autoTest.running;
  if($('abortAutoTestBtn'))$('abortAutoTestBtn').disabled=!state.autoTest.running;
}
async function readDiagFast(node){const r=await node.command(OP.GET_DIAG,new Uint8Array(),6500);if(r.status!==0)throw new Error(`${node.label}: не удалось получить состояние путей — ${STATUS[r.status]||r.status}`);const d=decodeDiag(r.payload);if(!d)throw new Error(`${node.label}: получены повреждённые данные`);node.diag=d;renderAll();return node.diag}
async function waitForCheck(fn,timeoutMs=30000,intervalMs=700){const start=Date.now();while(Date.now()-start<timeoutMs){if(state.autoTest.abort)throw new Error('Проверка остановлена пользователем');const v=await fn();if(v)return v;await sleep(intervalMs)}throw new Error(`Нет нужного результата за ${Math.round(timeoutMs/1000)} с`)}
function routeFor(node,dst){return node.diag?.routes?.find(r=>r.destination===dst.id)||null}
function manifestHealthy(nodes){if(nodes.length!==3)return false;let key=null;for(const n of nodes){if(!n.manifest?.valid)return false;const k=`${n.manifest.epoch}:${n.manifest.digest}`;if(key===null)key=k;else if(k!==key)return false}return true}
async function runAutoTest(){
  if(state.autoTest.running)return;
  state.autoTest={running:true,abort:false,step:0,steps:AUTO_TEST_LABELS.map(label=>({label,status:'idle',detail:''})),report:null};renderAutoTest();
  const startedAt=Date.now();let A,B,C,baselinePromotions=0,failAt=0,promotedAt=0,routeBefore=null,routeAfter=null;
  try{
    setAutoStep(0);const ns=connectedNodes().slice(0,3);if(ns.length!==3)throw new Error('Подключи три узла к телефону');[A,B,C]=ns;if(!ns.every(n=>n.status?.radio&&n.status?.crypto))throw new Error('Один из узлов не готов к защищённой радиосвязи');completeAutoStep(0,'все три узла готовы');

    setAutoStep(1);await provisionManifest();if(!manifestHealthy([A,B,C]))throw new Error('Список узлов на устройствах не совпадает');completeAutoStep(1,'список узлов совпадает');

    setAutoStep(2);await clearAllLab();await clearRoutes();await setPairPolicy(A,C,'weak',MANUAL);completeAutoStep(2,'прямая связь A↔C ослаблена');

    setAutoStep(3);const p=new Uint8Array(5);put32(p,0,C.id);p[4]=1;const dr=await A.command(OP.DISCOVER,p,22000);if(dr.status!==0&&dr.status!==5)throw new Error(`Не удалось построить путь: ${STATUS[dr.status]||dr.status}`);
    routeBefore=await waitForCheck(async()=>{const d=await readDiagFast(A);const r=routeFor(A,C);if(r&&r.primary===B.id&&r.backup===C.id&&(r.flags&2)&&((r.primaryMask&r.backupMask)===0))return {...r};return null},36000,850);baselinePromotions=A.diag.promotionsG2;completeAutoStep(3,`${A.label}→${B.label}→${C.label} + запасной прямой путь`);

    setAutoStep(4);$('fieldSource').value=String(A.id);$('fieldDestination').value=String(C.id);$('fieldCount').value='36';$('fieldInterval').value='700';$('fieldPayload').value='24';$('fieldMode').value='0';await startField();await sleep(3600);const fr=await A.command(OP.GET_FIELD,new Uint8Array(),6000);if(fr.status!==0)throw new Error('Нет данных проверки связи');A.field=decodeField(fr.payload);if(!A.field||A.field.sent<2)throw new Error('Проверка связи не начала передачу');completeAutoStep(4,`${A.field.sent} отправлено`);

    setAutoStep(5);failAt=Date.now();await setPairPolicy(A,B,'block',30000);completeAutoStep(5,'основной участок A↔B оборван на 30 с');

    setAutoStep(6);routeAfter=await waitForCheck(async()=>{const d=await readDiagFast(A);const r=routeFor(A,C);if(r&&r.primary===C.id&&d.promotionsG2>baselinePromotions)return {...r};return null},18000,500);promotedAt=Date.now();completeAutoStep(6,`переход за ${fmtMs(Math.max(0,promotedAt-failAt))}`);

    setAutoStep(7);await sleep(2600);try{const r=await A.command(OP.GET_FIELD,new Uint8Array(),6000);if(r.status===0)A.field=decodeField(r.payload)}catch(_){}await setPairPolicy(A,B,'clear',0);const report=buildLabReport({pass:true,startedAt,finishedAt:Date.now(),source:A,destination:C,relay:B,routeBefore,routeAfter,failAt,promotedAt});state.autoTest.report=report;completeAutoStep(7,'УСПЕШНО');addEvent(null,`Автоматическая проверка завершена · переход на резерв ${fmtMs(report.metrics.promotionMs)} · доставка ${report.metrics.fieldPdr||'—'}`);toast('Автоматическая проверка VANGUARD завершена успешно','success');
  }catch(e){failAutoStep(state.autoTest.step,e.message||String(e));state.autoTest.report=buildLabReport({pass:false,startedAt,finishedAt:Date.now(),source:A,destination:C,relay:B,routeBefore,routeAfter,failAt,promotedAt,error:e.message||String(e)});addEvent(null,`Автоматическая проверка: ошибка · ${e.message||e}`);toast(e.message||String(e),'error')
  }finally{state.autoTest.running=false;renderAutoTest()}
}
async function abortAutoTest(){state.autoTest.abort=true;const ns=connectedNodes();for(const n of ns){try{await n.command(OP.STOP_FIELD)}catch(_){}}toast('Остановка автоматической проверки запрошена')}
function serialiseNode(n){return n?{label:n.label,nodeId:hex(n.id),info:n.info,status:n.status,known:(n.known||[]).map(hex),manifest:n.manifest,neighbors:n.neighbors,routes:n.routes,diag:n.diag,lab:n.lab,field:n.field,radar:n.radar}:null}
function buildLabReport(x={}){const promotionMs=x.failAt&&x.promotedAt?x.promotedAt-x.failAt:null;return{schema:'securemesh-lab-report/1',build:`Commander-UI-${COMMANDER_VERSION} / Firmware-${EXPECTED_FIRMWARE_VERSION}`,pass:!!x.pass,error:x.error||null,startedAt:x.startedAt?new Date(x.startedAt).toISOString():null,finishedAt:x.finishedAt?new Date(x.finishedAt).toISOString():new Date().toISOString(),topology:'3-radio A-B-C with A-C soft-weak exact-G2 candidate',metrics:{promotionMs,fieldPdr:x.source?.field?`${(x.source.field.pdr*100).toFixed(1)}%`:null,fieldReplies:x.source?.field?.replies??null,fieldTimeouts:x.source?.field?.timeouts??null,rttAvgMs:x.source?.field?.avgRtt??null,controlBudgetDrops:x.source?.diag?.controlBudgetDrops??null,g2Promotions:x.source?.diag?.promotionsG2??null},routeBefore:x.routeBefore||null,routeAfter:x.routeAfter||null,nodes:connectedNodes().map(serialiseNode),events:state.events.slice().reverse()}}
function exportReport(){const report=state.autoTest.report||buildLabReport({pass:false,startedAt:Date.now(),error:'Manual snapshot'});const blob=new Blob([JSON.stringify(report,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`SecureMesh_LabReport_${new Date().toISOString().replace(/[:.]/g,'-')}.json`;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),500);toast('Отчёт сохранён','success')}
function setupPwa(){window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();state.installPrompt=e;if($('installBtn'))$('installBtn').hidden=false});if('serviceWorker'in navigator)navigator.serviceWorker.register('sw.js').catch(()=>{});if($('installBtn'))$('installBtn').onclick=async()=>{if(!state.installPrompt)return;state.installPrompt.prompt();await state.installPrompt.userChoice;state.installPrompt=null;$('installBtn').hidden=true}}

function initModel(){for(let i=0;i<5;i++)for(let j=i+1;j<5;j++){const k=`${i}-${j}`;if(!state.model.links[k])state.model.links[k]='normal'}}
function modelNodes(){return Array.from({length:state.model.count},(_,i)=>String.fromCharCode(65+i))}
function renderModel(){initModel();state.model.count=Number($('modelNodeCount')?.value||state.model.count||5);const names=modelNodes();const fill=id=>{const el=$(id);if(!el)return;const old=el.value;el.innerHTML=names.map((n,i)=>`<option value="${i}">${n}</option>`).join('');if([...el.options].some(o=>o.value===old))el.value=old};fill('modelSource');fill('modelDestination');if($('modelSource')&&$('modelDestination').value===$('modelSource').value&&names.length>1)$('modelDestination').value='1';const links=$('modelLinks');if(links){links.innerHTML='';for(let i=0;i<state.model.count;i++)for(let j=i+1;j<state.model.count;j++){const k=`${i}-${j}`;const row=document.createElement('div');row.className='model-link-row';row.innerHTML=`<span>${names[i]} ↔ ${names[j]}</span><select data-model-link="${k}"><option value="normal">нормальная</option><option value="weak">ослабленная</option><option value="block">оборвана</option></select>`;row.querySelector('select').value=state.model.links[k];row.querySelector('select').onchange=e=>{state.model.links[k]=e.target.value;state.model.primary=state.model.backup=null;renderModelSvg()};links.appendChild(row)}}renderModelSvg()}
function modelEdge(i,j){return state.model.links[`${Math.min(i,j)}-${Math.max(i,j)}`]||'normal'}
function enumeratePaths(s,d,n){const out=[];function dfs(cur,path,seen,cost,rel){if(cur===d){out.push({path:[...path],cost,rel});return}for(let v=0;v<n;v++){if(v===cur||seen.has(v))continue;const e=modelEdge(cur,v);if(e==='block')continue;const ec=e==='weak'?3:1,er=e==='weak'?.72:.98;seen.add(v);path.push(v);dfs(v,path,seen,cost+ec,rel*er);path.pop();seen.delete(v)}}dfs(s,[s],new Set([s]),0,1);return out.filter(x=>x.path.length<=n).sort((a,b)=>a.cost-b.cost||b.rel-a.rel)}
function solveModel(){const s=Number($('modelSource').value),d=Number($('modelDestination').value);if(s===d)return toast('Начальный и конечный узел должны отличаться','error');const paths=enumeratePaths(s,d,state.model.count);if(!paths.length){state.model.primary=state.model.backup=null;$('modelResult').textContent='Путь не найден.';return renderModelSvg()}const primary=paths[0],pInt=new Set(primary.path.slice(1,-1)),pFirst=primary.path[1];let backup=null;for(const x of paths.slice(1)){const ints=x.path.slice(1,-1);if(x.path[1]===pFirst)continue;if(ints.every(v=>!pInt.has(v))){backup=x;break}}state.model.primary=primary;state.model.backup=backup;const fmt=p=>p.path.map(i=>String.fromCharCode(65+i)).join(' → ');$('modelResult').innerHTML=`<b>Основной путь:</b> ${fmt(primary)}<br><b>Запасной путь:</b> ${backup?fmt(backup):'не найден'}`;renderModelSvg()}
function pathHasEdge(path,a,b){if(!path)return false;for(let i=0;i<path.path.length-1;i++){const x=path.path[i],y=path.path[i+1];if((x===a&&y===b)||(x===b&&y===a))return true}return false}
function renderModelSvg(){const svg=$('modelSvg');if(!svg)return;const n=state.model.count,w=760,h=480,cx=w/2,cy=h/2,r=170,pos=[];for(let i=0;i<n;i++){const a=-Math.PI/2+i*2*Math.PI/n;pos.push([cx+r*Math.cos(a),cy+r*Math.sin(a)])}let out='';for(let i=0;i<n;i++)for(let j=i+1;j<n;j++){const e=modelEdge(i,j),[x1,y1]=pos[i],[x2,y2]=pos[j];let cls=e==='block'?'block':e==='weak'?'weak':'live',style='';if(pathHasEdge(state.model.primary,i,j))style='stroke:#58e6a8;stroke-width:6';else if(pathHasEdge(state.model.backup,i,j))style='stroke:#6ca8ff;stroke-width:5;stroke-dasharray:10 6';out+=`<line class="topo-link ${cls}" style="${style}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>`}for(let i=0;i<n;i++){const [x,y]=pos[i];out+=`<g class="topo-node connected" transform="translate(${x},${y})"><circle r="38"/><text y="5">${String.fromCharCode(65+i)}</text></g>`}svg.innerHTML=out}


function selectedRadarNode(){const ns=connectedNodes();setOptions('radarSource',ns);return nodeById(Number($('radarSource')?.value))||state.selectedNode||ns[0]}
function radarRangeLabel(rssi){if(rssi>=-55)return'очень близкий сигнал';if(rssi>=-70)return'рядом';if(rssi>=-82)return'средний сигнал';return'слабый сигнал'}
function radarTrendLabel(v){if(v>=9)return'сигнал быстро усиливается ↑';if(v>=4)return'сигнал усиливается ↑';if(v<=-9)return'сигнал быстро ослабевает ↓';if(v<=-4)return'сигнал ослабевает ↓';return'сигнал стабилен →'}
function renderRadar(){
  const n=selectedRadarNode(),box=$('radarDevices'),plot=$('radarPlot');if(!box||!plot)return;
  const radar=n?.radar,items=(radar?.items||[]).filter(x=>x.age<45000).sort((a,b)=>a.age-b.age);
  const unknown=items.filter(x=>!isBleTrusted(x.hash)),strongest=items.reduce((m,x)=>!m||x.rssi>m.rssi?x:m,null);
  const persistentUnknown=unknown.filter(x=>x.hits>=4&&x.presence>=5000),risingUnknown=persistentUnknown.filter(x=>x.trend>=4);const scene=!items.length?'ТИХО':risingUnknown.length?'АКТИВНОСТЬ УСИЛИВАЕТСЯ':persistentUnknown.length?'УСТОЙЧИВАЯ АКТИВНОСТЬ':'ЕДИНИЧНЫЕ СИГНАЛЫ';$('radarMetrics').innerHTML=metric('Обстановка',scene,persistentUnknown.length?`${persistentUnknown.length} подтверждённых неизвестных`:'нет устойчивых неизвестных')+metric('В эфире',items.length)+metric('Неизвестные',unknown.length)+metric('Сильнейший',strongest?radarRangeLabel(strongest.rssi):'—');
  let blips='<div class="radar-center">SM</div><i class="radar-sweep"></i>';
  for(const x of items.slice(0,16)){const angle=(x.hash%360)*Math.PI/180,normalized=clamp((-x.rssi-38)/62,0.08,.96),radius=normalized*46,px=50+Math.cos(angle)*radius,py=50+Math.sin(angle)*radius;blips+=`<button class="radar-blip ${isBleTrusted(x.hash)?'trusted':'unknown'}" style="left:${px}%;top:${py}%" title="${esc(x.name||('Устройство '+hex(x.hash).slice(-4)))}"></button>`}
  plot.innerHTML=blips;
  box.innerHTML=items.length?items.map(x=>{const trusted=isBleTrusted(x.hash),fresh=x.age<12000;return`<div class="radar-device ${trusted?'trusted':''}"><div class="radar-device-main"><span class="radar-device-icon">${trusted?'✓':'•'}</span><div><strong>${esc(x.name||`Устройство ${hex(x.hash).slice(-4)}`)}</strong><small>${radarRangeLabel(x.rssi)} · ${radarTrendLabel(x.trend)} · ${fresh?'сейчас':Math.round(x.age/1000)+' с назад'}</small></div></div><div class="radar-device-side"><span>${x.hits}×</span><button class="btn tiny trust-ble" data-hash="${x.hash}" data-trusted="${trusted?'1':'0'}">${trusted?'Знакомое':'Отметить знакомым'}</button></div></div>`}).join(''):'<div class="radar-empty">Пока рядом нет устойчиво обнаруживаемых Bluetooth-устройств. Радар ждёт повторных сигналов, чтобы не реагировать на случайное обнаружение.</div>';
  box.querySelectorAll('.trust-ble').forEach(b=>b.onclick=()=>setBleTrusted(Number(b.dataset.hash),b.dataset.trusted!=='1'));
  $('radarStateText').textContent=radar?`Устройства Bluetooth рядом · ${radar.scanning?'идёт сейчас':'пауза между проверками'} · узел ${n?.label||'—'}. Положение точек условное: система оценивает только силу сигнала, а не точное расстояние.`:'Для радара нужен подключённый узел SecureMesh.';
}
async function refreshRadar(){if(state.radarBusy)return;const n=selectedRadarNode();if(!n?.connected)return;state.radarBusy=true;try{const r=await n.command(OP.GET_RADAR,new Uint8Array(),5000);if(r.status===0){const d=decodeRadar(r.payload);if(d)n.radar=d}}catch(_){}finally{state.radarBusy=false;renderRadar()}}
async function clearRadar(){const n=selectedRadarNode();if(!n?.connected)return;try{await n.command(OP.CLEAR_RADAR,new Uint8Array(),5000);n.radar=null;await refreshRadar();toast('История радара очищена','success')}catch(e){toast(e.message||String(e),'error')}}

function bind(){
  $('connectBtn').onclick=connectNode;$('refreshAllBtn').onclick=()=>refreshAll().catch(e=>toast(e.message,'error'));$('autoTestBtn').onclick=()=>runAutoTest();$('abortAutoTestBtn').onclick=()=>abortAutoTest();$('exportReportBtn').onclick=exportReport;$('provisionManifestBtn').onclick=()=>provisionManifest().catch(e=>toast(e.message,'error'));$('refreshRoutingBtn').onclick=renderRouting;
  $('linkClearBtn').onclick=()=>pairAction('clear');$('linkWeakBtn').onclick=()=>pairAction('weak');$('linkVeryWeakBtn').onclick=()=>pairAction('veryWeak');$('linkBlockBtn').onclick=()=>pairAction('block');
  $('presetTriangle').onclick=()=>presetTriangle().catch(e=>toast(e.message,'error'));$('presetChain').onclick=()=>presetChain().catch(e=>toast(e.message,'error'));$('presetG2').onclick=()=>presetG2().catch(e=>toast(e.message,'error'));$('failPrimaryBtn').onclick=()=>failPrimary().catch(e=>toast(e.message,'error'));$('clearAllLabBtn').onclick=()=>clearAllLab().catch(e=>toast(e.message,'error'));$('forceDiscoveryBtn').onclick=()=>forceDiscovery().catch(e=>toast(e.message,'error'));$('clearRoutesBtn').onclick=()=>clearRoutes().catch(e=>toast(e.message,'error'));
  $('startFieldBtn').onclick=()=>startField().catch(e=>toast(e.message,'error'));$('stopFieldBtn').onclick=()=>stopField().catch(e=>toast(e.message,'error'));$('fieldSource').onchange=renderField;$('fieldPreset').onchange=applyFieldPreset;$('routingSource').onchange=renderRouting;$('exportFieldCsvBtn').onclick=exportFieldCsv;$('clearFieldRecordBtn').onclick=clearFieldRecord;
  $('clearEventsBtn').onclick=()=>{state.events=[];persistEvents();renderEvents();renderEventPreview()};
  $('modelNodeCount').onchange=()=>{state.model.primary=state.model.backup=null;renderModel()};$('modelSolveBtn').onclick=solveModel;$('radarRefreshBtn').onclick=()=>refreshRadar();$('radarClearBtn').onclick=()=>clearRadar();$('radarSource').onchange=()=>refreshRadar();$('nodeEditCancel').onclick=closeNodeEditor;$('nodeEditSave').onclick=saveNodeEditor;$('nodeEditModal').onclick=e=>{if(e.target===$('nodeEditModal'))closeNodeEditor()};
  document.querySelectorAll('.tab').forEach(b=>{b.onclick=()=>openTab(b.dataset.tab);b.setAttribute('role','tab')});document.querySelectorAll('[data-goto]').forEach(b=>b.onclick=()=>openTab(b.dataset.goto));document.querySelectorAll('[data-mobile-tab]').forEach(b=>b.onclick=()=>openTab(b.dataset.mobileTab));
}
async function pairAction(kind){const a=nodeById(Number($('linkNodeA').value)),b=nodeById(Number($('linkNodeB').value)),dur=Number($('linkDuration').value)>>>0;try{await setPairPolicy(a,b,kind,dur);toast(({clear:'Связь восстановлена',weak:'Связь ослаблена',veryWeak:'Связь сильно ослаблена',block:'Связь оборвана'})[kind]||'Изменение применено','success')}catch(e){toast(e.message,'error')}}
function openTab(name){document.querySelectorAll('.tab').forEach(x=>{const active=x.dataset.tab===name;x.classList.toggle('active',active);x.setAttribute('aria-selected',active?'true':'false')});document.querySelectorAll('[data-mobile-tab]').forEach(x=>x.classList.toggle('active',x.dataset.mobileTab===name));document.querySelectorAll('.panel-tab').forEach(x=>x.classList.toggle('active',x.id===`tab-${name}`));if(name==='topology')renderTopology();if(name==='routing')renderRouting();if(name==='model')renderModel();if(name==='radar'){renderRadar();refreshRadar()}window.scrollTo({top:0,behavior:'smooth'})}
function initSecureBadge(){const b=$('secureContextBadge');if(navigator.bluetooth&&window.isSecureContext){b.textContent='ТЕЛЕФОН ГОТОВ';b.className='pill good'}else{b.textContent=navigator.bluetooth?'НУЖЕН ЗАЩИЩЁННЫЙ ЗАПУСК':'BLUETOOTH НЕДОСТУПЕН';b.className='pill bad'}}

document.addEventListener('keydown',e=>{if(e.key==='Escape'&&$('nodeEditModal').classList.contains('open'))closeNodeEditor()});$('nodeNameInput').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();saveNodeEditor()}});
bind();initSecureBadge();setupPwa();renderAll();renderAutoTest();
setInterval(async()=>{if(document.hidden||state.polling)return;state.polling=true;try{await Promise.allSettled(connectedNodes().map(n=>n.refresh(false)))}finally{state.polling=false}},5000);
setInterval(()=>{if(!document.hidden&&$('tab-radar')?.classList.contains('active'))refreshRadar()},3000);
})();
