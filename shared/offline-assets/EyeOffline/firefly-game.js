/* The firefly is the only fixation target. All observation stays on this device. */
window.createFireflyGame = function(body, onDone, save, preferences, onRestart) {
  const points = [[50,46],[17,18],[50,18],[83,18],[83,46],[83,74],[50,74],[17,74],[17,46]];
  const directions = ['horizontal','vertical','diagonal'];
  const routeNames = ['左右','上下','斜向'];
  const stages = [
    ...points.map((pos,i)=>({key:'cal'+(i+1),duration:2000,pos})),
    {key:'near',duration:12000,pos:[50,20]},
    ...directions.map((key,i)=>({key,duration:[8000,8000,6000][i]}))
  ];
  const total = stages.reduce((sum,part)=>sum+part.duration,0);
  const followPrompt = '保持头部不动，用眼睛跟随萤火虫移动';
  const coverPrompt = '手机放稳，两眼自然睁开，用单手遮住一只眼睛，另一只眼睛跟随萤火虫移动';
  const coverCues = ['遮住右眼，左眼跟随萤火虫','放下手，双眼看亮点','遮住左眼，右眼跟随萤火虫','放下手，双眼看亮点'];
  const signal = '<div class="ff-signal" role="status" data-ff-signal></div>';
  const cue = '<div class="ff-cue" role="status" data-ff-instruction></div>';
  const stageArt = kind=>window.FireflyArt.stage(kind);
  const $ = selector=>body.querySelector(selector);
  const btn = (action,label,style='primary',attr='')=>'<button type="button" data-ff="'+action+'" class="ff-'+style+'" '+attr+'>'+label+'</button>';
  const head = (title,description,feedback='')=>'<header class="ff-heading"><h3 tabindex="-1">'+title+'</h3><p>'+description+'</p>'+feedback+'</header>';
  const progress = '<div class="ff-progress" role="progressbar" aria-label="观察进度" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><i class="ff-progress-fill"></i></div>';
  const paragraph = (cls,content)=>'<p class="ff-'+cls+'">'+content+'</p>';
  function row(label,detail,state) {
    return '<div class="ff-row"><div><strong>'+label+'</strong><small>'+detail+'</small></div><b>'+state+'</b></div>';
  }
  function option(key,label,detail) {
    return '<button type="button" class="ff-option" data-ff="'+key+'"><div><strong>'+label+'</strong><small>'+detail+'</small></div><span aria-hidden="true">›</span></button>';
  }

  let page='', member=null, mode='demo', observer=null, loading=false, generation=0, error='';
  let entryMode='';
  const recordStatus=window.createTestRecordStatus(save);
  let report=null, runTime=0, step=0, elapsed=0, last=0, ticker=0, waiting=0;
  let paused=false, playingExtra=false, extraMode='', practiceDone=false, audio=null, audioFailed=false;
  let currentCue=-1, ui=null, baseEnded=false, landing=false, extraNotice='';
  const visited=new Set(), completed=new Set();

  function write(node, text) {if(node && node.textContent!==text)node.textContent=text}
  function stopClock() {clearInterval(ticker);ticker=0}
  function stopAudio() {
    if(audio){audio.pause();audio.currentTime=0;audio=null}
    audioFailed=false;
  }
  function stopCamera() {generation++;observer?.stop();observer=null;loading=false}
  function leave() {
    stopClock();stopAudio();stopCamera();ui=null;page='';paused=playingExtra=landing=false;error='';
  }
  function mount(id,entry) {
    if(page)return;
    entryMode=entry?.mode||'';
    member=id;mode=entryMode||'demo';page='prepare';error=extraNotice='';report=null;runTime=0;baseEnded=false;paint();
    if(entryMode==='camera')openCamera(()=>{updatePrep();startClock()},entry.createCamera);
  }
  function prepMarkup() {
    return head('送萤火虫回家','把手机放稳，双眼自然睁开，看着萤火虫腹部的亮点。',signal)+stageArt('prepare')+
      '<div class="ff-controls">'+
      btn('camera','开启前置相机')+btn('practice','开始练习','primary','hidden')+
      btn('demo',entryMode==='demo'?'开始练习':'直接试玩 · 不使用相机',entryMode==='demo'?'primary':'secondary')+'</div>';
  }
  function practiceMarkup() {
    return head('先跟萤火虫试一次',followPrompt)+stageArt('practice')+
      '<div class="ff-controls">'+btn('formal','开始正式测试','primary','disabled')+'</div>';
  }
  function testMarkup() {
    return head('跟着萤火虫',followPrompt,cue)+stageArt('test')+progress+
      '<div class="ff-foot"><span data-ff-recovery hidden>'+btn('skip','跳过未拍清的这段','subtle')+'</span>'+btn('pause','暂停','subtle')+'</div>';
  }
  function reportMarkup() {
    const near=report.near,tracks=directions.map(key=>report[key]);
    const captured=tracks.filter(item=>item?.hasData).length;
    const compared=tracks.filter(item=>item?.tracking?.available).length;
    const demo=mode==='demo';
    const statusFor=ok=>demo?'试玩':ok?'已记录':'画面不足';
    const nearDetail=!visited.has('near')?'本次还没有开始近处注视。':demo?(completed.has('near')?'已体验固定注视，未采集眼部画面。':'仅体验了部分注视过程。'):near?.hasData?
      '清晰记录双眼位置约 '+Math.round(near.usableMs/1000)+' 秒。':'缺少连续清晰的双眼画面。';
    const hasTrack=visited.has('horizontal');
    let trackDetail=hasTrack?'已体验 '+directions.filter(key=>completed.has(key)).length+' / 3 段飞行，未采集轨迹。':'本次还没有开始跟随飞行。';
    if(!demo&&hasTrack)trackDetail=captured?'已采集 '+captured+' / 3 段双眼位置。'+(compared?'其中 '+compared+' 段可与飞行方向对照。':'本次轨迹对照不足。'):'没有足够的双眼位置记录。';
    const trackState=!hasTrack?'未进行':demo?'试玩':compared===3?'已记录':compared?'部分记录':captured?'对照不足':'画面不足';
    const extraStatus=key=>{
      const item=report[key];
      if(!item)return '未测';
      if(item.demo)return '已试玩 · 未采集';
      if(key==='cover')return '已完成口令引导 · 遮盖效果未核实';
      return item.hasData?'已记录眼部位置 · 距离由本人确认':'已尝试 · 画面不足';
    };
    const title=demo?(baseEnded?'试玩已结束':'试玩完成'):'本次眼位观察';
    const desc=demo?'本次为无相机试玩，以下项目均未生成测量值。':'这里记录镜头拍到了哪些动作。眼位是否异常，仍需眼科检查。';
    let sections=head(title,desc)+
      '<section class="ff-summary"><h4>本次近距离观察</h4><div class="ff-rows">'+
      row('近处注视',nearDetail,visited.has('near')?statusFor(near?.hasData):'未进行')+
      row('双眼跟随',trackDetail,trackState)+'</div></section>'+
      recordStatus.markup(btn('save','重试保存','secondary'))+stageArt(baseEnded?'prepare':'result');
    sections+='<section class="ff-summary"><h4>选做补测</h4><div class="ff-options">'+
      option('cover','遮眼测试',extraStatus('cover')+' · 左右眼轮流跟随萤火虫')+
      option('far','看远',extraStatus('far')+' · 看约 6 米外的实物')+'</div>'+
      (extraNotice?'<p role="status">'+extraNotice+'</p>':'')+'</section>';
    return sections+'<div class="ff-controls">'+btn('done',recordStatus.finishLabel)+btn('again','重新开始','subtle')+'</div>';
  }
  function extraMarkup() {
    const cover=page==='cover';
    const title=cover?'遮眼测试':'看远处的目标';
    const quiet=preferences?.sound===false||Number(preferences?.speechVolume)===0;
    const farIntro='在正前方约 6 米外选一个实物，手机稳放在眼前略低处，让镜头看到双眼。'+
      (quiet?'看远约 10 秒后看回屏幕。':'听到口令后看回屏幕，静音时可默数约 10 秒。');
    const intro=cover?coverPrompt:playingExtra?'头和手机保持不动，看约 6 米外选好的实物。':farIntro;
    const label=cover?(mode==='demo'?'开始遮眼试玩':'开始遮眼'):'已选好目标，开始看远';
    return head(title,intro,playingExtra?cue:signal)+stageArt(page)+
      (playingExtra?progress+'<div class="ff-foot">'+btn('cancel-extra','结束并返回','subtle')+'</div>':
        '<div class="ff-controls">'+
        btn('begin-extra',label)+(mode==='camera'?btn('demo-extra','无相机体验这一步','secondary','hidden'):'')+btn('cancel-extra','返回本次结果','subtle')+'</div>');
  }
  function paint(focus=true) {
    const markup=page==='prepare'?prepMarkup():page==='practice'?practiceMarkup():
      page==='test'?testMarkup():page==='result'?reportMarkup():extraMarkup();
    body.innerHTML='<section class="ff-page" data-ff-page="'+page+'">'+markup+'</section>';
    ui={flight:$('.ff-flight'),stage:$('.ff-stage'),clearing:$('.ff-clearing'),sig:$('[data-ff-signal]'),instruction:$('[data-ff-instruction]'),
      bar:$('.ff-progress'),fill:$('.ff-progress-fill'),recovery:$('[data-ff-recovery]')};
    if(focus){body.scrollTop=0;$('.ff-heading h3')?.focus({preventScroll:true})}
    if(page==='prepare'){
      const preview=document.createElement('video');
      preview.className='ff-preview';preview.hidden=true;preview.playsInline=true;preview.muted=true;
      preview.setAttribute('aria-label','前置相机预览');ui.stage.append(preview);updatePrep();
    }
    if((page==='cover'||page==='far')&&!playingExtra)updateExtraSetup();
  }
  function updatePrep() {
    const snapshot=observer?.sample(),active=Boolean(snapshot?.active);
    const camera=$('[data-ff=camera]'),practice=$('[data-ff=practice]');
    camera.hidden=active||entryMode==='demo'||(entryMode==='camera'&&!error&&!snapshot?.ended);
    camera.disabled=loading;write(camera,entryMode?'重新检查相机':loading?'正在开启相机…':error||snapshot?.ended?'重新开启相机':'开启前置相机');
    $('[data-ff=demo]').hidden=entryMode==='camera';
    practice.hidden=!active;practice.disabled=!snapshot?.visible;
    write(ui.sig,entryMode==='demo'?'':error||(loading?(entryMode?'正在连接已检查的相机。':'请允许使用前置相机。'):active?(snapshot.visible?'双眼已入镜，可以练习。':snapshot.hint):snapshot?.ended?'相机连接中断，请重试。':entryMode?'':'开启相机即可在本机观察，影像不保存。'));
    const video=$('.ff-preview');
    if(active && video.srcObject!==observer.stream){video.srcObject=observer.stream;video.play().catch(()=>{})}
    video.hidden=!active;
  }
  function updateExtraSetup() {
    const b=$('[data-ff=begin-extra]');b.disabled=loading;
    if(loading)write(b,'正在开启相机…');
    if(error)write(b,'重新开启相机');
    const trial=$('[data-ff=demo-extra]');
    if(trial)trial.hidden=!error&&!loading;
    write(ui.sig,error||(loading?'正在准备前置相机。':''));
  }
  async function openCamera(afterReady,cameraFactory) {
    stopCamera();const token=generation;
    loading=true;error='';
    if(page==='prepare')updatePrep();else updateExtraSetup();
    try {
      const device=window.FireflyObserver.create(cameraFactory);
      observer=device;
      const ready=await device.start();
      if(token!==generation)return;
      if(!ready)throw new Error();
      loading=false;afterReady();
    } catch(e) {
      if(token!==generation)return;
      stopCamera();
      error=page==='prepare'&&entryMode==='camera'?'相机连接已中断，请重新检查双眼位置。':
        e.name==='NotAllowedError'?'未获得相机权限，可在浏览器允许访问，或选择无相机试玩。':'相机暂时不可用，可重试或选择无相机试玩。';
      if(page==='prepare')updatePrep();else updateExtraSetup();
    }
  }
  function position(x,y) {
    // The original route spans 18 (top calibration row) to 85 (landing).
    // Map it into the clearing while reporting the actual target position to the camera.
    const heightFraction=(y-18)/67;
    if(observer && ui.stage.clientHeight) {
      observer.target(x/100,(ui.clearing.offsetTop+heightFraction*ui.clearing.offsetHeight)/ui.stage.clientHeight);
    }
    ui.flight.style.left=x.toFixed(2)+'%';ui.flight.style.top=(heightFraction*100).toFixed(2)+'%';
  }
  function setProgress(value) {
    const percent=Math.max(0,Math.min(100,value*100)),rounded=String(Math.floor(percent));
    if(ui.fill)ui.fill.style.width=percent.toFixed(2)+'%';
    if(ui.bar?.getAttribute('aria-valuenow')!==rounded)ui.bar?.setAttribute('aria-valuenow',rounded);
  }
  function startClock() {stopClock();last=performance.now();ticker=setInterval(tick,32)}
  function startPractice(demo) {
    if(demo)stopCamera();
    mode=demo?'demo':'camera';page='practice';elapsed=0;practiceDone=false;error='';paint();position(32,43);startClock();
  }
  function drawPractice() {
    const t=Math.min(1,Math.max(0,(elapsed-1600)/2600));
    position(32+36*(1-Math.cos(Math.PI*t))/2,43-8*Math.sin(Math.PI*t));
    if(elapsed>=4700){practiceDone=true;$('[data-ff=formal]').disabled=false;stopClock()}
  }
  function prepareStage(i) {
    step=i;elapsed=0;waiting=0;ui.recovery.hidden=true;
    const part=stages[i];
    visited.add(part.key);
    position(...(part.pos||[50,46]));
    observer?.begin(part.key);
  }
  function drawTest() {
    const part=stages[step],p=Math.min(1,elapsed/part.duration);
    if(part.pos) {
      // The CSS clearing can resize while the insect is holding a calibration point.
      if(observer)position(...part.pos);
    } else {
      const wave=Math.sin(2*Math.PI*p);
      position(50+(part.key==='vertical'?0:33*wave),46+(part.key==='horizontal'?0:(part.key==='diagonal'?-1:1)*27*wave));
    }
    setProgress((part.duration*p+stages.slice(0,step).reduce((sum,s)=>sum+s.duration,0))/total);
    const sample=observer?.sample();
    const lost=mode==='camera'&&!sample?.visible;
    const action=step<points.length?'看住亮点 · '+(step+1)+' / '+points.length:
      part.key==='near'?'看住亮点，自然眨眼。':routeNames[directions.indexOf(part.key)]+'跟随';
    write(ui.instruction,lost?(sample?.ended?'相机已中断，可跳过这段。':sample?.hint||'让双眼清晰入镜。'):
      (mode==='demo'?'无相机试玩 · ':'')+action);
    ui.recovery.hidden=waiting<3500;
    if(elapsed>=part.duration){completed.add(part.key);nextStage()}
  }
  function nextStage(skipGroup=false) {
    observer?.end();
    const next=skipGroup&&step<9?9:step+1;
    if(next>=stages.length){
      landing=true;elapsed=0;ui.recovery.hidden=true;write(ui.instruction,'萤火虫回到小芽身边了。');
      setProgress(1);return;
    }
    prepareStage(next);
  }
  function startBase() {
    stopClock();page='test';report={};recordStatus.reset();runTime=Date.now();baseEnded=paused=landing=false;visited.clear();completed.clear();
    paint();prepareStage(0);drawTest();startClock();
  }
  function storeReport() {
    recordStatus.update(member,runTime,report,mode==='camera'&&Object.values(report).some(item=>item?.hasData));
  }
  function finishBase(early=false) {
    baseEnded=early;landing=false;stopClock();observer?.end();
    for(const key of ['near',...directions])report[key]=observer?.summary(key)??null;
    stopCamera();paused=false;page='result';storeReport();paint();
  }
  function openExtra(key) {
    stopClock();stopAudio();stopCamera();playingExtra=false;error='';
    extraMode=mode;page=key;paint();
    if(mode==='camera')openCamera(()=>paint(false));
  }
  function returnToResult() {
    stopClock();stopAudio();stopCamera();paused=playingExtra=false;error='';
    page='result';paint();
  }
  function playCues() {
    audio=document.getElementById('ff-audio-'+page);
    const volume=preferences?.sound===false?0:(preferences?.speechVolume??80)/100;
    if(!audio||volume===0){audioFailed=true;return}
    audio.volume=volume;
    audio.currentTime=0;
    const playing=audio;
    playing.play().catch(()=>{if(audio===playing)audioFailed=true});
  }
  function startExtra(demo) {
    if((loading&&!demo)||playingExtra)return;
    const kind=page;
    if(!demo&&!observer?.sample().active){openCamera(()=>paint(false));return}
    if(demo)stopCamera();
    stopClock();extraMode=demo?'demo':'camera';playingExtra=true;elapsed=0;currentCue=-1;
    paint();last=performance.now();
    if(kind==='cover'){
      position(50,43);observer?.begin('cover');
    }
    playCues();drawExtra();startClock();
  }
  function drawExtra() {
    const cover=page==='cover';
    const instructions=cover?coverCues:['越过手机，看远处的实物','继续看远','可以看回屏幕了'];
    const cue=cover?Math.min(3,Math.floor(elapsed/4000)):elapsed<4000?0:elapsed<14000?1:2;
    if(cue!==currentCue) {
      currentCue=cue;
      if(!cover&&cue===1)observer?.begin('far');
      if(!cover&&cue===2){observer?.end();if(preferences?.haptic)window.navigator.vibrate?.(160)}
    }
    if(cover){
      // The covered-eye intervals use the same slow out-and-back path on opposite sides.
      const phase=Math.min(1,Math.max(0,((elapsed%4000)-650)/3350));
      const excursion=cue%2===0?27*Math.sin(Math.PI*phase)**2:0;
      position(50+(cue===2?-excursion:excursion),43);
    }
    write(ui.instruction,!cover&&cue===0&&audioFailed?'看远约 10 秒后看回屏幕':instructions[cue]);
    setProgress(elapsed/(cover?16000:17000));
    if(elapsed<(cover?16000:17000))return;
    observer?.end();
    const previous=report[page],attempt=extraMode==='demo'?{demo:true}:observer.summary(page);
    if(previous&&!previous.demo&&(attempt.demo||(previous.hasData&&!attempt.hasData))){
      extraNotice=(attempt.demo?'刚才是无相机试玩。':'刚才拍到的清晰画面较少。')+'已保留上一次的'+(cover?'遮眼':'看远')+'记录。';
    }else{
      report[page]=attempt;extraNotice='';recordStatus.reset();
    }
    storeReport();returnToResult();
  }
  function tick() {
    const now=performance.now(),dt=Math.max(0,now-last);last=now;
    if(document.hidden||paused)return;
    if(page==='prepare'){updatePrep();return}
    if(page==='test'&&landing){
      elapsed+=dt;const ratio=Math.min(1,elapsed/1200),ease=ratio*ratio*(3-2*ratio);
      position(50+38*ease,46+39*ease);
      if(ratio>=1)finishBase();return;
    }
    if(page==='test'&&mode==='camera'&&!observer?.sample().visible){
      waiting+=dt;drawTest();return;
    }
    waiting=0;elapsed+=dt;
    if(page==='practice')drawPractice();
    else if(page==='test')drawTest();
    else if(playingExtra)drawExtra();
  }
  function pause(reason='') {
    if(paused)return;
    paused=true;stopClock();observer?.setPaused(true);if(audio)audio.pause();
    const extra=playingExtra;
    const panel=document.createElement('div');panel.className='ff-pause-panel';panel.setAttribute('role','dialog');
    panel.setAttribute('aria-modal','true');panel.setAttribute('aria-labelledby','ff-paused-title');
    panel.innerHTML='<div><h4 id="ff-paused-title">已暂停</h4>'+paragraph('help',reason||'准备好后，从刚才的位置继续。')+
      '<div class="ff-controls">'+btn('resume','继续')+btn(extra?'cancel-extra':page==='practice'?'back-prep':'end-early',
        extra?'返回本次结果':page==='practice'?'返回准备':'结束并查看已有记录','secondary')+'</div></div>';
    const root=$('.ff-page');
    Array.from(root.children).forEach(el=>el.inert=true);root.append(panel);panel.querySelector('button').focus({preventScroll:true});
  }
  function resume() {
    if(!paused||document.hidden)return;
    $('.ff-pause-panel')?.remove();Array.from($('.ff-page').children).forEach(el=>el.inert=false);
    paused=false;observer?.setPaused(false);
    if(audio&&!audioFailed)audio.play().catch(()=>{audioFailed=true});
    if(page==='practice'&&practiceDone)return;
    startClock();
    ($('[data-ff=pause]')||$('[data-ff-instruction]')||$('.ff-heading h3'))?.focus({preventScroll:true});
  }
  function reset() {const id=member;leave();if(onRestart&&entryMode)onRestart();else mount(id)}
  function back() {
    if(page==='cover'||page==='far'){returnToResult();return true}
    if(page==='test'){if(paused)resume();else pause();return true}
    if(page==='practice'){reset();return true}
    return false;
  }
  body.addEventListener('click',event=>{
    const button=event.target.closest('[data-ff]');
    if(!button||button.disabled||!page)return;
    switch(button.dataset.ff) {
      case 'camera':if(entryMode&&onRestart)reset();else openCamera(()=>{updatePrep();startClock()});break;
      case 'demo':startPractice(true);break;
      case 'practice':if(observer?.sample().visible)startPractice(false);else updatePrep();break;
      case 'formal':if(practiceDone)startBase();break;
      case 'pause':pause();break;
      case 'resume':resume();break;
      case 'skip':if(page==='test')nextStage(true);break;
      case 'end-early':finishBase(true);break;
      case 'back-prep':case 'again':reset();break;
      case 'cover':case 'far':openExtra(button.dataset.ff);break;
      case 'begin-extra':startExtra(mode==='demo');break;
      case 'demo-extra':startExtra(true);break;
      case 'cancel-extra':returnToResult();break;
      case 'save':if(page==='result'){storeReport();paint();}break;
      case 'done':leave();onDone();break;
    }
  });
  document.addEventListener('visibilitychange',()=>{
    if(document.hidden && (page==='test'||playingExtra||(page==='practice'&&!practiceDone)))pause('页面已切到后台，已暂停本段观察。');
  });
  window.addEventListener('pagehide',()=>{if(page)leave()});
  window.addEventListener('pageshow',event=>{
    if(event.persisted&&!page&&body.parentElement?.dataset.view==='firefly'&&!body.parentElement.hidden)reset();
  });
  return {mount,back,leave};
};
