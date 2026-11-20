/* Measurements use only uninterrupted, visible face samples. */
function createSproutObservation(){
  let validMs=0,count=0,longest=null,previous=null,closedAt=null,reopenedAt=null,startedOpen=false;
  function interrupt(){previous=closedAt=reopenedAt=null;startedOpen=false}
  function sample(frame){
    if(!frame.visible){interrupt();return}
    const now=frame.time;
    if(previous!==null&&(now-previous>120||now<=previous))interrupt();
    if(previous!==null)validMs+=now-previous;
    previous=now;
    if(frame.blink){
      if(startedOpen&&closedAt===null)closedAt=now;
      return;
    }
    if(closedAt!==null){
      const length=now-closedAt;
      if(length>=45&&length<=700){
        count++;
        if(reopenedAt!==null)longest=Math.max(longest||0,closedAt-reopenedAt);
        reopenedAt=now;
      }else reopenedAt=null;
      closedAt=null;
    }
    startedOpen=true;
  }
  function result(){return {seconds:Math.floor(validMs/1000),count,rate:validMs>=10000?Math.round(count*60000/validMs):null,longest:validMs>=10000&&longest!==null?+(longest/1000).toFixed(1):null}}
  return {sample,interrupt,result};
}

window.createSproutGame=function(body,onDone,save,onRestart){
  const art=window.sproutArt;
  const tools=[
    {name:'小铲子',ask:'帮我松松土',thanks:'泥土松软多了'},
    {name:'水滴',ask:'我想喝点水',thanks:'喝到水了'},
    {name:'养分袋',ask:'给我一点养分',thanks:'收到养分了'},
    {name:'瓢虫',ask:'请瓢虫来帮忙',thanks:'叶子上的小虫走了'}
  ];
  const stages=['种子破土','长茎展叶','小蓝花开放','白铃兰垂开','花冠盛开'];
  const bloomRevealMs=1600;
  const reduceMotion=matchMedia('(prefers-reduced-motion: reduce)');
  let page=-1,member,camera=null,nonce=0,mode='demo',cameraState='off',cameraText='',lastFrame=0,ready=false,readySince=null;
  let entryMode=null;
  const recordStatus=window.createTestRecordStatus(save);
  let runTime=0;
  let garden=null,observation=createSproutObservation(),summary=null,ui=null;
  let lastTick=0,animation=0,paused=false,drag=null,selected=null,ignoreClick=null,noteUntil=0,bloomElapsed=null;
  let resizeObserver=null,fieldWidth=0,fieldHeight=0;
  const itemNodes=new Map(),coinNodes=new Map();
  function growthLevel(percent){
    if(percent>=100)return 4;
    if(percent>=70)return 3;
    if(percent>=45)return 2;
    return percent>=20?1:0;
  }
  const $=s=>body.querySelector(s);
  const btn=(id,label,type='primary',extra='')=>'<button type="button" class="sg-'+type+'" data-sg="'+id+'" '+extra+'>'+label+'</button>';
  function header(title,desc,status=''){
    return '<header class="sg-heading"><div class="sg-title-row"><h3 tabindex="-1">'+title+'</h3>'+status+'</div><p>'+desc+'</p></header>';
  }
  function scene(content,kind=''){
    return '<div class="sg-scene '+kind+'"><div class="sg-land" aria-hidden="true"></div>'+content+'</div>';
  }
  const sprout=(level,bug=false)=>'<span class="sg-plant">'+art.plant(level,bug)+'</span>';
  function photoScene(){
    return scene('<div class="sg-preview-label"><i></i>准备观察</div><video class="sg-preview" muted playsinline autoplay hidden></video><div class="sg-photo-plant">'+sprout(0)+'</div><div class="sg-corners"><i></i><i></i><i></i><i></i></div>','sg-portrait');
  }
  function prep(){
    let controls;
    if(entryMode==='demo')controls=btn('demo','开始练习');
    else if(entryMode==='camera')controls=btn('practice','开始练习','primary','disabled')+btn('again','重新检查眼睛位置','primary','hidden');
    else controls=btn('camera','开启前置相机')+btn('practice','开始练习','primary','hidden')+btn('demo','直接试玩 · 不使用相机','quiet');
    return header('陪小芽长大','<span class="sg-camera-status" role="status"></span>')+
      photoScene()+'<div class="sg-actions">'+controls+'</div>';
  }
  function updatePrep(){
    if(page!==0)return;
    const cameraButton=$('[data-sg=camera]'), practice=$('[data-sg=practice]');
    if(cameraButton){
      cameraButton.hidden=cameraState==='on';cameraButton.disabled=cameraState==='loading';
      cameraButton.textContent=cameraState==='loading'?'正在开启相机…':cameraState==='error'?'重新开启相机':'开启前置相机';
    }
    if(practice){
      practice.hidden=entryMode==='camera'?cameraState==='error':cameraState!=='on';
      practice.disabled=cameraState!=='on'||!ready;
    }
    if(entryMode==='camera')$('[data-sg=again]').hidden=cameraState!=='error';
    const status=$('.sg-camera-status');
    const msg=entryMode==='demo'?'陪小芽从破土长到开花。看小芽的气泡，把它需要的东西拖给它。':
      cameraState==='on'?(ready?'双眼已入镜，可以开始练习。':'请正对相机，让光照到脸上，直到双眼清晰入镜。'):cameraText||'陪小芽从破土长到开花。把手机放稳，让光照到脸上，双眼清晰入镜、手指能轻松拖动即可。';
    if(status.textContent!==msg)status.textContent=msg;
    const video=$('.sg-preview');
    if(cameraState==='on'&&camera?.stream&&video.srcObject!==camera.stream){video.srcObject=camera.stream;video.hidden=false;video.play().catch(()=>{});$('.sg-photo-plant').hidden=true}
  }
  function playable(practice){
    const description=practice?'把落下的水滴拖到花盆上。':'看气泡，把小芽想要的素材拖给它。';
    const stats=practice?'':'<div class="sg-garden-stats"><div class="sg-grow-stat"><div><span>生长进度 · <b data-sg-growth-label>种子破土</b></span><b data-sg-growth>0%</b></div><progress max="100" value="0" aria-label="小芽生长进度"></progress></div><div class="sg-coin-stat">'+art.coin()+'<span>金币</span><b data-sg-coins>0</b></div></div>';
    const field=scene('<div class="sg-bubble" aria-live="polite"></div><button type="button" class="sg-target" data-sg="target" aria-label="小芽和花盆"></button><div class="sg-sky"></div><div class="sg-coins"></div>','sg-playfield');
    const instructions='<span class="sg-default-note">'+description+'</span><span class="sg-game-note" role="status"></span>';
    const status=practice?'':'<span id="sg-signal" role="status"></span>';
    const controls=practice?'<div class="sg-actions">'+btn('formal',mode==='demo'?'开始养护':'开始正式测试','primary','disabled')+'</div>':
      '<div class="sg-bottom">'+btn('pause','暂停','link')+'</div>';
    return header(practice?'先给小芽浇水':'陪小芽长大',instructions,status)+stats+'<div class="sg-field-wrap" style="--plant-x:50%">'+field+'</div>'+controls;
  }
  function result(){
    const r=summary,available=mode==='camera'&&r.rate!==null,level=growthLevel(garden.growth);
    const metric=(title,num,unit)=>'<div><span>'+title+'</span><b>'+num+'</b><small>'+unit+'</small></div>';
    const tally=(number,name)=>'<div><b>'+number+'</b><span>'+name+'</span></div>';
    let description='小芽开花了。本次无相机试玩，未采集眨眼数据。';
    if(mode==='camera'){
      const dataNote=!available?'清晰画面太少，本次未能估算眨眼指标。':r.longest===null?'已统计眨眼频率。连续画面中缺少两次完整眨眼，最长间隔暂缺。':'已按双眼清晰入镜的时间统计眨眼。';
      description=dataNote+'仅凭本次记录无法判断干眼风险。';
    }
    const observationCard=mode==='camera'?'<section class="sg-results" aria-label="本次相机观察"><div class="sg-numbers">'+
      metric('有效观察时间',r.seconds,'秒')+metric('眨眼次数',r.seconds>0?r.count:'—','次')+'</div>'+
      (available?'<div class="sg-valid"><span>眨眼频率</span><strong>'+r.rate+' 次 / 分</strong></div>'+
      (r.longest===null?'':'<div class="sg-valid"><span>最长自然睁眼间隔</span><strong>'+r.longest+' 秒</strong></div>'):'')+'</section>':'';
    return header(mode==='demo'?'试玩完成':'本次眨眼观察',description)+observationCard+
      recordStatus.markup(btn('save','重试保存','quiet'))+
      scene('<div class="sg-result-flower" data-stage="'+level+'">'+sprout(level)+'</div>','sg-flower-scene')+
      '<div class="sg-tally">'+tally(Math.floor(garden.growth)+'%','生长进度')+tally(garden.deliveries+' 次','成功照料')+tally(garden.collected+' 枚','收集金币')+'</div>'+
      '<div class="sg-actions">'+btn('done',recordStatus.finishLabel)+btn('again','再体验一次','quiet')+'</div>';
  }
  function paint(focus=true){
    releaseInteraction();
    resizeObserver?.disconnect();resizeObserver=null;ui=null;itemNodes.clear();coinNodes.clear();
    body.innerHTML='<section class="sg-root" data-sg-page="'+page+'">'+[prep,()=>playable(true),()=>playable(false),result][page]()+'</section>';
    if(focus){body.scrollTop=0;$('.sg-heading h3')?.focus({preventScroll:true})}
    if(page===0){updatePrep();return}
    if(page!==1&&page!==2)return;
    ui={field:$('.sg-playfield'),sky:$('.sg-sky'),coins:$('.sg-coins'),target:$('.sg-target'),bubble:$('.sg-bubble'),note:$('.sg-game-note'),defaultNote:$('.sg-default-note'),
      growth:$('[data-sg-growth]'),label:$('[data-sg-growth-label]'),bar:$('.sg-grow-stat progress'),collected:$('[data-sg-coins]'),
      signal:$('#sg-signal'),level:-1,bug:false};
    sizeField();
    if('ResizeObserver'in window){resizeObserver=new ResizeObserver(sizeField);resizeObserver.observe(ui.field)}
    paintGarden();
  }
  function sizeField(){
    if(!ui)return;
    fieldWidth=ui.field.clientWidth;fieldHeight=ui.field.clientHeight;
  }
  function textIfChanged(el,text){if(el&&el.textContent!==text)el.textContent=text}
  function showGardenNote(message){
    textIfChanged(ui.note,message);
    if(ui.defaultNote.hidden!==Boolean(message))ui.defaultNote.hidden=Boolean(message);
  }
  function notifyGarden(message,ms=2200){
    if(!ui)return;
    noteUntil=garden.elapsed+ms;showGardenNote(message);
  }
  function paintGarden(){
    if(!ui)return;
    const practice=page===1,learned=practice&&garden.done,bloomed=!practice&&garden.done,need=garden.need;
    const level=learned?1:growthLevel(garden.growth),bug=need===3&&!garden.done;
    if(ui.level!==level||ui.bug!==bug){
      ui.field.dataset.stage=String(level);ui.target.innerHTML=sprout(level,bug);
      ui.target.setAttribute('aria-label',stages[level]+'，盆栽');ui.level=level;ui.bug=bug;
    }
    const bubbleKey=learned?'done':bloomed?'bloom':need===null?'rest':String(need);
    if(ui.bubble.dataset.need!==bubbleKey){
      ui.bubble.dataset.need=bubbleKey;ui.bubble.innerHTML=learned?'✓ <span>喝到水了！</span>':bloomed?'<span>我开花啦！</span>':need===null?'✓ <span>'+tools[garden.lastFedTool].thanks+'</span>':art.item(need)+'<span>'+tools[need].ask+'</span>';
    }
    ui.target.classList.toggle('sg-boost',garden.justFed&&!garden.done);
    updateItems();
    updateCoins();
    if(learned||garden.elapsed>=noteUntil)showGardenNote('');
    if(practice){$('[data-sg=formal]').disabled=!learned;return}
    const percent=Math.floor(garden.growth);
    textIfChanged(ui.growth,percent+'%');if(ui.bar.value!==percent)ui.bar.value=percent;
    textIfChanged(ui.label,stages[level]);
    textIfChanged(ui.collected,String(garden.collected));
    const visible=camera?.latest?.visible&&performance.now()-lastFrame<550;
    textIfChanged(ui.signal,mode==='demo'?'无相机试玩':bloomed?'观察已完成':cameraState==='error'?'相机已中断，养护可继续':visible?'正在观察双眼':'请让双眼入镜');
  }
  function makeItem(item){
    const el=document.createElement('button');
    el.type='button';el.className='sg-item';el.dataset.sg='item';el.dataset.id=item.id;el.dataset.tool=item.tool;
    el.style.left='0';el.style.top='0';
    el.setAttribute('aria-label',tools[item.tool].name+'，拖给小芽');el.setAttribute('aria-pressed','false');
    el.innerHTML=art.item(item.tool);ui.sky.append(el);itemNodes.set(item.id,el);
    return el;
  }
  function position(el,x,y){
    el.style.transform='translate3d('+x.toFixed(2)+'px,'+y.toFixed(2)+'px,0) translate(-50%,-50%)';
  }
  function updateItems(){
    for(const [id,el] of itemNodes){
      if(garden.done||!garden.items.some(item=>item.id===id)){itemNodes.delete(id);el.remove()}
    }
    if(garden.done)return;
    for(const item of garden.items){
      const el=itemNodes.get(item.id)||makeItem(item);
      if(drag?.el!==el)position(el,item.x*fieldWidth/100,item.y*fieldHeight/100);
    }
  }
  function updateCoins(){
    for(const [id,el] of coinNodes){
      if(!garden.coins.some(coin=>coin.id===id)){coinNodes.delete(id);el.remove()}
    }
    for(const coin of garden.coins){
      if(coinNodes.has(coin.id))continue;
      const el=document.createElement('button');
      el.type='button';el.className='sg-coin';el.dataset.sg='coin';el.dataset.id=coin.id;
      el.setAttribute('aria-label','收集金币');el.innerHTML=art.coin();
      el.style.left=coin.x+'%';el.style.top=coin.y+'%';
      const field=ui.field.getBoundingClientRect(),target=ui.target.getBoundingClientRect();
      const mouthX=(target.left+target.width*.5-field.left)*fieldWidth/field.width;
      const mouthY=(target.top+target.height*.76-field.top)*fieldHeight/field.height;
      el.style.setProperty('--burst-x',mouthX-coin.x*fieldWidth/100+'px');
      el.style.setProperty('--burst-y',mouthY-coin.y*fieldHeight/100+'px');
      ui.coins.append(el);coinNodes.set(coin.id,el);
      notifyGarden('小芽送你一枚金币，点一下收好。',3200);
    }
  }
  function stopCamera(){nonce++;camera?.stop();camera=null;cameraState='off';ready=false;readySince=null}
  async function enableCamera(createCamera=window.createScreeningCamera){
    stopCamera();const token=nonce;cameraState='loading';cameraText=entryMode==='camera'?'正在连接已检查的相机。':'请允许访问前置相机，首次加载需要片刻。';mode='camera';updatePrep();
    const receiveFrame=frame=>{
      if(nonce!==token)return;
      if(frame.ended){
        observation.interrupt();cameraState='error';ready=false;readySince=null;
        cameraText=entryMode==='camera'?'相机连接已中断，请重新检查眼睛位置。':'相机连接已中断。可重新开启，或直接试玩。';
        if(page===0){paint(false);return}
        if(page===2&&!paused)paintGarden();
        return;
      }
      if(document.hidden)return;
      if(frame.time-lastFrame>300)readySince=null;
      lastFrame=frame.time;
      if(page===0){
        readySince=frame.visible?(readySince??frame.time):null;
        ready=readySince!==null&&frame.time-readySince>=300;updatePrep();
      }
      if(page===2&&!paused&&!garden.done)observation.sample(frame);
    };
    try{
      camera=createCamera(receiveFrame);
      const ok=await camera.start();
      if(token!==nonce)return;
      if(!ok)throw Error();
      cameraState='on';cameraText='';updatePrep();
    }catch(e){
      if(token!==nonce)return;
      cameraState='error';
      cameraText=entryMode==='camera'?'相机连接已中断，请重新检查眼睛位置。':e.name==='NotAllowedError'?'尚未获得相机权限。可在浏览器允许访问，或直接试玩。':'当前无法使用相机。可重新开启，或直接试玩。';updatePrep();
    }
  }
  function stopLoop(){clearInterval(animation);animation=0}
  function startLoop(){
    stopLoop();lastTick=performance.now();
    animation=setInterval(tick,reduceMotion.matches?100:33);
  }
  function tick(){
    if(!garden||paused||document.hidden||(page!==1&&page!==2))return;
    const now=performance.now(),delta=Math.max(0,now-lastTick);lastTick=now;
    if(bloomElapsed!==null){
      bloomElapsed+=delta;
      if(bloomElapsed>=bloomRevealMs)finish();
      return;
    }
    garden.advance(delta);
    if(page===2&&garden.done){showBloom();return}
    paintGarden();
    if(page===1&&garden.done)stopLoop();
  }
  function begin(practice){
    stopLoop();releaseInteraction();paused=false;noteUntil=0;ignoreClick=null;bloomElapsed=null;
    garden=window.createSproutGarden({practice,reducedMotion:reduceMotion.matches});
    if(!practice){runTime=Date.now();recordStatus.reset();observation=createSproutObservation();}
    page=practice?1:2;paint();startLoop();
  }
  function showBloom(){
    if(bloomElapsed!==null)return;
    bloomElapsed=0;summary=observation.result();stopCamera();releaseInteraction();
    $('.sg-root').dataset.bloom='true';
    notifyGarden('');paintGarden();
  }
  function finish(){
    stopLoop();releaseInteraction();page=3;
    recordStatus.update(member,runTime,summary,mode==='camera'&&summary.rate!==null);
    paint();
  }
  function deliver(el){
    if(!garden||paused||garden.done||!el)return;
    const answer=garden.give(el.dataset.id);
    if(!answer)return;
    if(answer.correct)notifyGarden('');
    else if(answer.waiting)notifyGarden('等小芽说出新愿望，再送素材。');
    else notifyGarden('小芽想要'+tools[garden.need].name+'，再试一次。');
    if(page===2&&garden.done){showBloom();return}
    paintGarden();
    if(page===1&&garden.done){stopLoop();$('[data-sg=formal]').focus({preventScroll:true})}
  }
  function releaseInteraction(){
    if(drag){
      const {el,pointer}=drag;drag=null;
      if(el.hasPointerCapture(pointer))el.releasePointerCapture(pointer);
      el.classList.remove('sg-held');garden?.release(el.dataset.id);
    }
    if(selected){
      selected.setAttribute('aria-pressed','false');garden?.release(selected.dataset.id);selected=null;
    }
    ui?.field.classList.remove('holding');
  }
  function pause(){
    if(paused||page!==2)return;
    releaseInteraction();observation.interrupt();stopLoop();paused=true;
    $('.sg-root').inert=true;
    body.insertAdjacentHTML('beforeend','<div class="sg-pause" role="dialog" aria-modal="true" aria-labelledby="sg-pause-title"><div><h3 id="sg-pause-title">花园已暂停</h3><p>小芽和金币会在这里等你。</p>'+btn('resume','继续养护')+btn('exit','结束并返回','quiet')+'</div></div>');
    $('[data-sg=resume]').focus();
  }
  function resume(){
    if(!paused||document.hidden)return;
    $('.sg-pause')?.remove();$('.sg-root').inert=false;paused=false;observation.interrupt();paintGarden();startLoop();
    $('[data-sg=pause]')?.focus({preventScroll:true});
  }
  function leave(){
    stopLoop();releaseInteraction();resizeObserver?.disconnect();resizeObserver=null;ui=null;itemNodes.clear();coinNodes.clear();
    stopCamera();page=-1;paused=false;summary=null;garden=null;entryMode=null;
  }
  function mount(id,entry){
    if(page!==-1)return;
    member=id;entryMode=entry?.mode||null;mode=entryMode||'demo';page=0;cameraText='';paint(false);
    if(entryMode==='camera')enableCamera(entry.createCamera);
  }
  function restart(){
    leave();
    if(onRestart)onRestart();
    else mount(member);
  }
  function back(){
    if(page===2){if(paused)resume();else pause();return true}
    if(page===1){restart();return true}
    return false;
  }
  body.addEventListener('click',event=>{
    const el=event.target.closest('[data-sg]');if(!el||page<0)return;
    switch(el.dataset.sg){
      case 'camera':enableCamera();break;
      case 'demo':case 'practice':
        if(el.dataset.sg==='demo'){stopCamera();mode='demo'}
        else if(!ready||cameraState!=='on'||performance.now()-lastFrame>550){ready=false;readySince=null;updatePrep();return}
        begin(true);break;
      case 'formal':
        if(page!==1||!garden?.done)return;
        if(mode==='camera'&&cameraState==='error'){restart();return}
        begin(false);break;
      case 'item':{
        if(paused||!garden||garden.done||ignoreClick===el)return;
        releaseInteraction();
        if(!garden.hold(el.dataset.id))return;
        selected=el;selected.setAttribute('aria-pressed','true');ui.field.classList.add('holding');
        notifyGarden('已选'+tools[Number(el.dataset.tool)].name+'，轻点小芽即可送出。');
        if(event.detail===0)ui.target.focus({preventScroll:true});break;
      }
      case 'target':{
        const item=selected;
        if(item){deliver(item);releaseInteraction()}
        break;
      }
      case 'coin':{
        if(!paused&&page===2&&garden&&garden.collect(el.dataset.id)){
          coinNodes.delete(el.dataset.id);el.removeAttribute('data-sg');el.disabled=true;el.setAttribute('aria-hidden','true');
          el.classList.add('sg-collected');setTimeout(()=>el.remove(),260);
          notifyGarden('收到了！已收集 '+garden.collected+' 枚金币。');paintGarden();
        }
        break;
      }
      case 'pause':pause();break;
      case 'resume':resume();break;
      case 'save':if(page===3){recordStatus.update(member,runTime,summary,mode==='camera'&&summary.rate!==null);paint();}break;
      case 'exit':case 'done':leave();onDone();break;
      case 'again':restart();break;
    }
  });
  body.addEventListener('pointerdown',event=>{
    const el=event.target.closest('.sg-item');
    if(!el||!ui||!garden||garden.done||paused||drag||event.button!==0)return;
    releaseInteraction();ignoreClick=null;
    if(!garden.hold(el.dataset.id))return;
    const box=el.getBoundingClientRect(),field=ui.field.getBoundingClientRect();
    drag={el,pointer:event.pointerId,x:event.clientX,y:event.clientY,
      originX:(box.left+box.width/2-field.left)*fieldWidth/field.width,
      originY:(box.top+box.height/2-field.top)*fieldHeight/field.height,
      scaleX:fieldWidth/field.width,scaleY:fieldHeight/field.height,moved:false};
    el.classList.add('sg-held');el.setPointerCapture(event.pointerId);ui.field.classList.add('holding');
  });
  body.addEventListener('pointermove',event=>{
    if(!drag||event.pointerId!==drag.pointer)return;
    const dx=event.clientX-drag.x,dy=event.clientY-drag.y;
    if(Math.hypot(dx,dy)>4)drag.moved=true;
    if(drag.moved){position(drag.el,drag.originX+dx*drag.scaleX,drag.originY+dy*drag.scaleY);event.preventDefault()}
  });
  body.addEventListener('pointerup',event=>{
    if(!drag||event.pointerId!==drag.pointer)return;
    const {el,moved}=drag,box=ui.target.getBoundingClientRect();
    const inside=event.clientX>=box.left-8&&event.clientX<=box.right+8&&event.clientY>=box.top-8&&event.clientY<=box.bottom+8;
    if(moved){
      ignoreClick=el;setTimeout(()=>{if(ignoreClick===el)ignoreClick=null},0);
      if(inside)deliver(el);else notifyGarden('把素材拖到小芽或花盆上，再松手。');
    }
    releaseInteraction();if(ui)paintGarden();
  });
  body.addEventListener('pointercancel',e=>{if(drag?.pointer===e.pointerId)releaseInteraction()});
  document.addEventListener('visibilitychange',()=>{
    if(document.hidden){
      observation.interrupt();if(page===2)pause();
      else if(page===1){releaseInteraction();stopLoop()}
    }else if(page===1&&!garden.done)startLoop();
  });
  window.addEventListener('pagehide',()=>{if(page>=0)leave()});
  window.addEventListener('pageshow',e=>{if(e.persisted&&body.parentElement?.dataset.view==='sprout'&&!body.parentElement.hidden&&page<0)restart()});
  return {mount,back,leave};
};
