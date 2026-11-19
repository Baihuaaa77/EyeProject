/* A single, local pre-registration interview. Symptoms and draft answers live only in this session. */
window.createBooking = function createBooking(body, {escape, done, preferences}) {
  const {questions, assess, slots} = window.BookingIntake;
  let page = '', index = 0, answers = {}, editing = false;
  let selectedSlot = '', urgentAcknowledged = false, reading = false, audio = null, listening = null, version = 0;
  const get = selector => body.querySelector(selector);
  const answer = id => answers[id] || {choice:'',note:''};
  const hasAnswer = id => Boolean(answer(id).choice || answer(id).note.trim());
  const btn = (key,label,kind='secondary') => '<button type="button" class="bk-'+kind+'" data-bk="'+key+'">'+label+'</button>';
  const sayStatus = text => { const node=get('[data-bk-status]'); if(node)node.textContent=text; };
  function voiceState(active) {
    const button=get('[data-bk=voice]');
    if(!button)return;
    button.setAttribute('aria-pressed',String(active));
    button.setAttribute('aria-label',active?'结束语音输入':'语音输入');
    button.title=button.getAttribute('aria-label');
  }
  function stopListening() {
    if (!listening) return;
    const previous = listening;
    listening = null;
    previous.onresult = previous.onerror = previous.onend = null;
    try { previous.abort(); } catch {}
    voiceState(false);
  }
  function stopMedia() {
    version++;
    stopListening();
    if (audio) {audio.pause();audio.currentTime=0;audio=null;}
    if (reading && window.speechSynthesis?.speaking) window.speechSynthesis.cancel();
  }
  function leave() {
    stopMedia(); page=''; answers={}; reading=false; selectedSlot='';
    editing=urgentAcknowledged=false; index=0;
  }
  function mount() {
    if (page) return;
    leave(); page='question';
    reading=Boolean(preferences.voiceAssist && preferences.sound && preferences.speechVolume>0);
    paint();
  }
  function speak() {
    if (!reading || page!=='question' || document.hidden) return;
    const recorded=document.querySelector('[data-booking-prompt="'+(index+1)+'"]');
    const token=version;
    const unavailable=()=>{
      if(token!==version)return;
      reading=false; updateSpeaker(); sayStatus('此设备暂时无法朗读，请看下方问题。');
    };
    if(recorded){
      audio=recorded;
      audio.volume=Math.max(.01,Math.min(1,(preferences.speechVolume || 65)/100));
      audio.playbackRate=(preferences.speed || 100)/100;
      audio.play().catch(unavailable);
      return;
    }
    const synth=window.speechSynthesis;
    const voices=synth?.getVoices().filter(v=>v.localService && /^zh/i.test(v.lang)) || [];
    if(!voices.length){unavailable();return;}
    const speech=new SpeechSynthesisUtterance(questions[index].title);
    speech.voice=voices.find(v=>v.name===preferences.speaker)||voices[0];
    speech.rate=(preferences.speed||100)/100; speech.volume=(preferences.speechVolume||65)/100;
    speech.onerror=event=>{if(!['interrupted','canceled'].includes(event.error))unavailable()};
    synth.speak(speech);
  }
  function updateSpeaker() {
    const button=get('[data-bk=speak]');
    if(button){button.setAttribute('aria-pressed',String(reading));button.title=reading?'关闭朗读':'朗读问题';button.setAttribute('aria-label',button.title);}
  }
  function warning() {
    return '<aside class="bk-alert" role="alert" data-bk-warning hidden><strong>建议立即就诊</strong><p data-bk-warning-copy></p>'+
      btn('urgent','查看急诊指引','urgent')+'</aside>';
  }
  function question() {
    const q=questions[index], a=answer(q.id);
    const options=q.options.map((value,i)=>'<button type="button" data-bk-option="'+i+'" class="bk-option assistant-tile" aria-pressed="'+(a.choice===value)+'">'+
      '<span>'+escape(value)+'</span></button>').join('');
    const speakLabel=reading?'关闭朗读':'朗读问题';
    return '<div class="bk-content"><header class="bk-prompt assistant-hero"><span class="assistant-portrait" aria-hidden="true"></span>'+
      '<div class="assistant-heading"><p class="aike-role"><span lang="en">Eyecho</span> · 爱科挂号助手</p><h3 id="bk-question-title" tabindex="-1">'+escape(q.title)+'</h3></div></header>'+
      '<section class="bk-options assistant-tray" aria-labelledby="bk-question-title"><div class="bk-tray-head">'+
      '<span class="bk-stage">'+escape(q.label)+'</span><span class="bk-count" role="progressbar" aria-label="预问诊进度" aria-valuemin="0" aria-valuemax="'+questions.length+
      '" aria-valuenow="'+(index+1)+'" aria-valuetext="第 '+(index+1)+' 题，共 '+questions.length+' 题">'+(index+1)+' / '+questions.length+'</span>'+
      '<button type="button" data-bk="speak" class="bk-listen" aria-label="'+speakLabel+'" title="'+speakLabel+'" aria-pressed="'+reading+'"></button></div>'+
      '<div class="bk-option-grid'+(index===0?' bk-symptoms':'')+'">'+options+'</div></section>'+
      (index || editing ? btn('back',editing?'返回答案列表':'上一题','back'):'')+
      '</div><footer class="bk-dock">'+warning()+'<p data-bk-status role="status" class="bk-status"></p><div class="bk-editor assistant-composer">'+
      '<span class="assistant-portrait assistant-portrait-mini" aria-hidden="true"></span>'+
      '<textarea id="bk-note" data-bk-note rows="1" maxlength="300" aria-label="补充描述" placeholder="'+(index===0?'描述你的眼部情况…':'补充这个问题…')+'">'+escape(a.note)+'</textarea>'+
      '<button class="bk-voice" type="button" data-bk="voice" aria-label="语音输入" title="语音输入" aria-pressed="false"></button>'+
      '<button class="bk-send" type="button" data-bk="next" hidden></button></div></footer>';
  }
  function refreshQuestion() {
    if(page!=='question')return;
    const assessment=assess(answers), q=questions[index];
    const alert=get('[data-bk-warning]');
    alert.hidden=!assessment.urgent || urgentAcknowledged;
    if(!alert.hidden)get('[data-bk-warning-copy]').textContent=assessment.reason;
    const next=get('[data-bk=next]');
    next.hidden=!hasAnswer(q.id);
    next.title=editing?'保存修改':index===questions.length-1?'查看挂号建议':!alert.hidden?'继续补充资料':'下一题';
    next.setAttribute('aria-label',next.title);
  }
  function answerLine(q) {
    const a=answer(q.id);
    return [a.choice,a.note.trim()].filter(Boolean).join(' · ')||'未填写';
  }
  function review() {
    return '<h3 tabindex="-1">修改预问诊答案</h3><div class="bk-review">'+questions.map((q,i)=>
      '<button type="button" data-bk-edit="'+i+'"><span><small>'+escape(q.label)+'</small><strong>'+escape(answerLine(q))+
      '</strong></span><span aria-hidden="true">›</span></button>').join('')+'</div>'+btn('return-result','返回挂号建议');
  }
  function visitData(){
    const assessment=assess(answers), schedule=slots(answers,assessment);
    return {assessment,schedule,chosen:schedule.find(s=>s.id===selectedSlot)||schedule[0]};
  }
  function result(){
    const {assessment,schedule,chosen}=visitData(); selectedSlot=chosen?.id||'';
    const rows=questions.filter(q=>hasAnswer(q.id)).map(q=>'<div><dt>'+escape(q.label)+'</dt><dd>'+escape(answerLine(q))+'</dd></div>').join('');
    const options=schedule.map(s=>'<button type="button" class="bk-slot" data-bk-slot="'+escape(s.id)+'" aria-pressed="'+(s.id===selectedSlot)+'">'+
      '<span><strong>'+escape(s.name)+'</strong><small>'+escape(s.rank)+'</small><em>'+escape(s.when)+'</em></span>'+
      '<b>¥'+escape(s.fee)+'</b></button>').join('');
    return '<div class="bk-outcome '+(assessment.urgent?'bk-acute':'')+'"><span class="assistant-portrait bk-result-portrait" aria-hidden="true"></span><span class="bk-kicker">建议挂号</span><h3 tabindex="-1">'+
      escape(assessment.department)+'</h3><strong>'+escape(assessment.priority)+'</strong><p>'+escape(assessment.reason)+'</p></div>'+
      '<details class="bk-draft"><summary>病历信息草稿 <small>'+questions.filter(q=>hasAnswer(q.id)).length+' 项回答</small></summary><dl>'+rows+'</dl></details>'+
      '<div class="bk-section-title"><h4>可预约医生</h4><span>演示号源</span></div><div class="bk-slots">'+options+'</div>'+
      '<p class="bk-caption">分诊建议仅供就诊参考。号源和费用均为演示。</p>'+
      (chosen?btn('confirm','确认选择并模拟挂号','primary'):'')+btn('edit','返回修改预问诊答案','text');
  }
  function receipt() {
    const {assessment,chosen}=visitData();
    return '<article class="bk-ticket"><div class="bk-ticket-icon" aria-hidden="true">✓</div><h3 tabindex="-1">已完成模拟挂号</h3>'+
      '<p>本次为离线演示，实际就诊请通过医院挂号。</p><dl>'+
      [['就诊科室',assessment.department],['接诊医生',chosen.name+' · '+chosen.rank],['就诊时间',chosen.when],['演示挂号费','¥'+chosen.fee]].map(([k,v])=>
        '<div><dt>'+escape(k)+'</dt><dd>'+escape(v)+'</dd></div>').join('')+'</dl></article>'+
      (assessment.urgent?'<p class="bk-alert-note">请立即前往就近的眼科急诊或综合医院急诊。</p>':'')+btn('done','完成并返回首页','primary');
  }
  function paint() {
    stopMedia();
    const html=page==='question'?question():page==='review'?review():page==='result'?result():receipt();
    body.innerHTML='<section class="bk-page" data-bk-page="'+page+'">'+html+'</section>';
    body.scrollTop=0;
    get('h3')?.focus({preventScroll:true});
    if(page==='question'){refreshQuestion();speak();}
  }
  function showResult() {
    const missing=questions.findIndex(q=>!hasAnswer(q.id));
    if(missing>=0 && !assess(answers).urgent){editing=false;navigate(missing);return;}
    page='result';editing=false;selectedSlot='';paint();
  }
  function navigate(i) {
    index=i; page='question'; paint();
  }
  function next() {
    if(!hasAnswer(questions[index].id))return;
    if(assess(answers).urgent)urgentAcknowledged=true;
    if(editing){
      editing=false;
      const missing=questions.findIndex(q=>!hasAnswer(q.id));
      if(missing>=0 && !assess(answers).urgent){navigate(missing);return;}
      showResult();return;
    }
    if(index===questions.length-1)showResult();
    else navigate(index+1);
  }
  function choose(i){
    const q=questions[index], value=q.options[i];
    if(value===undefined)return;
    const previous=assess(answers);
    answers[q.id]={...answer(q.id),choice:value};
    const current=assess(answers);
    if(current.urgent && (!previous.urgent || (q.id==='danger' && previous.reason!==current.reason))) urgentAcknowledged=false;
    if(current.urgent && !urgentAcknowledged){
      paint();
      get('[data-bk=urgent]').focus();
    } else next();
  }
  function back(){
    if(page==='receipt')return false;
    if(page==='review'){showResult();return true;}
    if(page==='result'){editing=false;navigate(index);return true;}
    if(page==='question' && editing){editing=false;page='review';paint();return true;}
    if(page==='question' && index){navigate(index-1);return true;}
    return false;
  }
  function setNote(value) {
    const q=questions[index];
    const prior=assess(answers);
    answers[q.id]={...answer(q.id),note:value.slice(0,300)};
    if(!prior.urgent && assess(answers).urgent)urgentAcknowledged=false;
    refreshQuestion();
  }
  function dictate(){
    if(listening){listening.stop();return;}
    stopMedia();
    const token=version;
    const Rec=window.SpeechRecognition||window.webkitSpeechRecognition;
    const fallback='可使用键盘上的麦克风听写，或直接输入文字。';
    const fail=text=>{if(token!==version)return;stopListening();voiceState(false);sayStatus(text);get('[data-bk-note]')?.focus();};
    try{
      if(!Rec || !('processLocally' in Rec.prototype)){fail(fallback);return;}
      const recognizer=new Rec();
      listening=recognizer;recognizer.lang='zh-CN';recognizer.processLocally=true;
      recognizer.onresult=e=>{
        if(token!==version)return;
        const note=get('[data-bk-note]');
        note.value=(note.value+(note.value?'，':'')+e.results[0][0].transcript).slice(0,300);
        setNote(note.value);sayStatus('请核对识别文字后继续。');
      };
      recognizer.onerror=()=>fail(fallback);
      recognizer.onend=()=>{if(token===version){listening=null;voiceState(false);if(get('[data-bk-status]')?.textContent==='正在听…')sayStatus('可以修改文字或重新听写。');}};
      recognizer.start(); voiceState(true);sayStatus('正在听…');
    }catch{fail(fallback);}
  }
  body.addEventListener('input',e=>{if(page==='question' && e.target.matches('[data-bk-note]'))setNote(e.target.value);});
  body.addEventListener('click',e=>{
    const button=e.target.closest('button');
    if(!page||!button||button.disabled||!button.closest('[data-bk-page]'))return;
    if(button.hasAttribute('data-bk-option') && page==='question'){choose(Number(button.dataset.bkOption));return;}
    if(button.hasAttribute('data-bk-edit') && page==='review'){editing=true;navigate(Number(button.dataset.bkEdit));return;}
    if(button.hasAttribute('data-bk-slot') && page==='result'){
      selectedSlot=button.dataset.bkSlot;
      body.querySelectorAll('[data-bk-slot]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
      return;
    }
    switch(button.dataset.bk){
      case 'voice':dictate();break;
      case 'speak':stopMedia();reading=!reading;updateSpeaker();sayStatus('');if(reading)speak();break;
      case 'next':next();break;
      case 'back':back();break;
      case 'urgent':if(assess(answers).urgent)showResult();break;
      case 'return-result':showResult();break;
      case 'edit':page='review';paint();break;
      case 'confirm':if(page==='result' && visitData().chosen){page='receipt';paint();}break;
      case 'done':done();break;
    }
  });
  document.addEventListener('visibilitychange',()=>{if(document.hidden&&page){stopMedia();sayStatus('');}});
  window.addEventListener('pagehide',()=>{if(page)stopMedia();});
  return {mount,leave,back};
};
