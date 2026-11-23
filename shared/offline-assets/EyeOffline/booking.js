/* 双端共用智能挂号页面。回答、姓名和草稿仅在本次页面内存中使用。 */
window.createBooking = function createBooking(body, {escape,action,preferences,provider,prefillDryEye}) {
  const {questions,update,assess,draft,line} = window.BookingIntake;
  const cities = {
    '长沙':[['长沙爱尔眼科医院','天心区芙蓉南路一段188号','changsha-appointment'],
      ['长沙湘江爱尔眼科医院','岳麓区桐梓坡路336号','appointment']],
    '武汉':[['武汉爱尔眼科医院','武昌区中山路481号','appointment'],
      ['汉口爱尔眼科医院','江汉区马场路34号','appointment']],
    '北京':[['北京爱尔英智眼科医院','朝阳区潘家园南里12号','appointment'],
      ['北京爱尔眼科医院','朝阳区安立路66号','appointment']]
  };
  // 官方公开资料仅提供科室匹配的医生参考，出诊、费用和可预约时段以院方为准。
  const listedDoctors = [
    {hospital:'长沙爱尔眼科医院',departments:['屈光手术科','青光眼科','白内障科'],
      name:'林丁',title:'主任医师',profile:'doctor-lin'},
    {hospital:'汉口爱尔眼科医院',departments:['角膜及眼表科'],
      name:'曾庆延',title:'主任医师',profile:'doctor-zeng'}
  ];
  let page = 'question', index = 0, answers = prefillDryEye ? {chief:{choice:'surface',note:''}} : {};
  let city = '', otherCity = '', hospital = '', preferredDate = '', preferredPeriod = '', doctor = '';
  let patients = [], patient = '', manualName = false;
  let demoVersion = 0, demoSlots = [], selectedSlot = '', demoReceipt = null, demoQuery = null;
  let reading = Boolean(preferences.voiceAssist), speaking = false, listening = null, version = 0;
  const get = selector => body.querySelector(selector);
  const current = () => questions(answers)[index];
  const answer = q => answers[q.id] || {choice:'',note:''};
  const response = q => line(q,answers);
  const btn = (name,label,kind='secondary',attrs='') =>
    '<button type="button" class="bk-'+kind+'" data-bk="'+name+'" '+attrs+'>'+escape(label)+'</button>';
  const status = () => '<p data-bk-status role="status" class="bk-status"></p>';
  const sayStatus = message => { const node=get('[data-bk-status]'); if(node)node.textContent=message; };
  const option = (value,label,selected) => '<option value="'+escape(value)+'"'+(value===selected?' selected':'')+'>'+escape(label)+'</option>';
  const calendarDay = (offset=0) => {
    const local=new Date();
    local.setDate(local.getDate()+offset);
    return [local.getFullYear(),String(local.getMonth()+1).padStart(2,'0'),String(local.getDate()).padStart(2,'0')].join('-');
  };
  const rows = data => '<dl>'+data.map(([label,value])=>'<div><dt>'+escape(label)+'</dt><dd>'+escape(value)+'</dd></div>').join('')+'</dl>';

  function stopListening() {
    if (!listening) return;
    const recognizer = listening;
    listening = null;
    recognizer.onresult = recognizer.onerror = recognizer.onend = null;
    try { recognizer.abort(); } catch {}
    get('[data-bk=voice]')?.setAttribute('aria-pressed','false');
  }
  function stopMedia() {
    version++;
    stopListening();
    if (speaking) window.speechSynthesis?.cancel();
    speaking = false;
  }
  function leave() {
    stopMedia();
    page=''; answers={}; patients=[]; patient=''; doctor='';
    city=otherCity=hospital=preferredDate=preferredPeriod='';
    demoVersion++; demoSlots=[]; demoReceipt=null; demoQuery=null; selectedSlot='';
  }
  function speak() {
    if (!reading || page!=='question' || document.hidden) return;
    const available = window.speechSynthesis?.getVoices().filter(v=>v.localService && /^zh/i.test(v.lang)) || [];
    if (!available.length) {
      sayStatus('此设备暂时无法朗读，请阅读当前问题。');
      return;
    }
    const utterance = new SpeechSynthesisUtterance(current().title);
    utterance.voice=available[0]; utterance.volume=.65;
    const token=version;
    speaking=true;
    utterance.onerror=event=>{
      if(token===version && !['interrupted','canceled'].includes(event.error))sayStatus('此设备暂时无法朗读，请阅读当前问题。');
    };
    window.speechSynthesis.speak(utterance);
  }
  function dictate() {
    if (listening) { listening.stop(); return; }
    stopMedia();
    const token = version;
    const Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
    const fallback = '可使用键盘听写，或直接输入文字。';
    const fail = message => { if (token===version) { stopListening(); sayStatus(message); get('[data-bk-note]')?.focus(); } };
    try {
      if (!Rec || !('processLocally' in Rec.prototype)) { fail(fallback); return; }
      const recognizer = new Rec();
      listening=recognizer; recognizer.lang='zh-CN'; recognizer.processLocally=true;
      recognizer.onresult=e=>{
        if(token!==version)return;
        const note=get('[data-bk-note]');
        note.value=(note.value+(note.value?'，':'')+e.results[0][0].transcript).slice(0,300);
        setNote(note.value); sayStatus('请核对识别文字后继续。');
      };
      recognizer.onerror=()=>fail(fallback);
      recognizer.onend=()=>{ if(token===version) { listening=null; get('[data-bk=voice]')?.setAttribute('aria-pressed','false'); } };
      recognizer.start(); get('[data-bk=voice]').setAttribute('aria-pressed','true'); sayStatus('正在听…');
    } catch { fail(fallback); }
  }

  function questionPage() {
    const q=current(), a=answer(q);
    const options=Object.entries(q.options).map(([key,label])=>
      '<button type="button" data-bk-option="'+key+'" class="bk-option assistant-tile" aria-pressed="'+(a.choice===key)+'">'+
      '<span>'+escape(label)+'</span></button>').join('');
    const readLabel=reading?'关闭朗读':'朗读问题';
    return '<div class="bk-content"><header class="bk-prompt assistant-hero"><span class="assistant-portrait" aria-hidden="true"></span>'+
      '<div class="assistant-heading"><h3 id="bk-question-title" tabindex="-1">'+escape(q.title)+'</h3></div></header>'+
      '<section class="bk-options assistant-tray" aria-labelledby="bk-question-title"><div class="bk-tray-head">'+
      '<span class="bk-stage">'+escape(q.label)+'</span><span class="bk-count" role="progressbar" aria-label="预问诊进度"'+
      ' aria-valuemin="0" aria-valuemax="3" aria-valuenow="'+(index+1)+'">'+(index+1)+' / 3</span>'+
      '<button type="button" data-bk="speak" class="bk-listen" aria-label="'+readLabel+'" aria-pressed="'+reading+'"></button></div>'+
      '<div class="bk-option-grid'+(index===0?' bk-symptoms':'')+'">'+options+'</div></section></div>'+
      '<footer class="bk-dock">'+status()+
      '<div class="bk-editor assistant-composer"><textarea data-bk-note rows="1" maxlength="300" aria-label="补充说明" placeholder="补充说明（可选）">'+
      escape(a.note)+'</textarea><button class="bk-voice" type="button" data-bk="voice" aria-label="语音输入" aria-pressed="false"></button></div>'+
      '<div class="bk-question-actions">'+(index ? btn('previous','上一题') : '')+
      btn('next',index===2?'查看导诊结果':'下一题','primary','disabled')+'</div>'+
      '<aside class="bk-alert" role="alert" data-bk-warning hidden><strong>建议立即就诊</strong><p data-bk-warning-copy></p></aside></footer>';
  }
  function refreshQuestion() {
    const result=assess(answers);
    const alert=get('[data-bk-warning]');
    alert.hidden=!result.urgent;
    if(result.urgent)get('[data-bk-warning-copy]').textContent=result.reason;
    const next=get('[data-bk=next]');
    next.disabled=!response(current()) && !result.urgent;
    next.textContent=result.urgent?'查看急诊指引':index===2?'查看导诊结果':'下一题';
  }
  function draftRows() { return draft(answers,patient); }
  function patientField() {
    const selection=patients.length?'<label>从家庭成员填入<select data-bk-patient>'+
      option('','请选择',patients.includes(patient)&&!manualName?null:'')+
      patients.map((name,i)=>option(String(i),name,patient===name&&!manualName?String(i):'')).join('')+'</select></label>':'';
    return '<div class="bk-fields">'+selection+'<label>就诊人姓名<input type="text" data-bk-name maxlength="40" autocomplete="off" value="'+
      escape(patient)+'"></label></div>';
  }
  function resultPage() {
    const result=assess(answers);
    return '<div class="bk-outcome '+(result.urgent?'bk-acute':'')+'"><span class="assistant-portrait bk-result-portrait" aria-hidden="true"></span>'+
      '<span class="bk-kicker">'+escape(result.priority)+'</span><h3 tabindex="-1">'+escape(result.department)+'</h3><p>'+
      escape(result.reason)+'</p></div>'+
      (result.checks.length?'<div class="bk-section-title"><h4>可向医生咨询的检查</h4></div><p class="bk-caption">'+escape(result.checks.join('、'))+'</p>':'')+
      '<section class="bk-draft" aria-labelledby="bk-draft-title"><h4 id="bk-draft-title">预问诊草稿</h4>'+patientField()+
      '<div data-bk-draft-rows>'+rows(draftRows().slice(1))+'</div></section>'+status()+
      '<div class="bk-stack">'+(result.urgent?'':btn('hospitals','选择医院','primary'))+
      btn('copy','复制预问诊草稿')+btn('edit','修改回答','text')+'</div>'+
      '<p class="bk-caption">导诊建议供就医参考，草稿内容待医生核实。</p>';
  }
  function matchingDoctors() {
    const department=assess(answers).department;
    return listedDoctors.filter(item=>item.hospital===hospital && item.departments.includes(department));
  }
  function doctorChoices() {
    return matchingDoctors().map(item=>'<div class="bk-doctor">'+
      btn('pick-doctor',item.name+' · '+item.title,'secondary','data-doctor-name="'+escape(item.name)+'"')+
      btn(item.profile,'官方资料','text')+'</div>').join('');
  }
  function hospitalPage() {
    const items=Object.prototype.hasOwnProperty.call(cities,city)?cities[city]:[];
    const places=items.map(([name,address])=>'<button type="button" class="bk-slot" data-bk-hospital="'+escape(name)+
      '" aria-pressed="'+(name===hospital)+'"><span><strong>'+escape(name)+'</strong><em>'+escape(address)+'</em></span></button>').join('');
    return '<h3 tabindex="-1">选择就诊医院</h3><div class="bk-fields"><label>就诊城市<select data-bk-city>'+
      option('','请选择城市',city)+Object.keys(cities).map(key=>option(key,key,city)).join('')+
      option('other','其他城市',city)+'</select></label>'+
      (city==='other'?'<label>城市名称<input type="text" data-bk-other-city maxlength="30" value="'+escape(otherCity)+'"></label>':'')+
      '</div><div class="bk-slots">'+places+'</div>'+
      (city==='other'?'<p class="bk-caption">可在爱尔官方目录查找当地院区。</p>'+btn('directory','查看爱尔医院目录'):'')+
      '<div data-bk-doctors>'+doctorChoices()+'</div><div class="bk-fields"><label>意向医生（可选）<input type="text" data-bk-doctor maxlength="40" value="'+escape(doctor)+'"></label>'+
      '<label>期望就诊日期（可选）<input type="date" data-bk-date min="'+calendarDay()+'" value="'+escape(preferredDate)+'"></label>'+
      '<label>期望时段（可选）<select data-bk-period>'+['','上午','下午'].map(key=>option(key,key||'不限',preferredPeriod)).join('')+
      '</select></label></div><p class="bk-caption">医生排班、号源和费用由爱尔官方渠道提供。</p>'+status()+
      '<div class="bk-stack">'+btn('review','核对挂号信息','primary',(hospital||city==='other'&&otherCity.trim())?'':'disabled')+
      btn('result','返回导诊结果','text')+'</div>';
  }
  function reviewPage() {
    const result=assess(answers), time=[preferredDate,preferredPeriod].filter(Boolean).join(' ');
    const destination=city==='other'?(otherCity+' · 待在官方渠道选择'):hospital;
    const checkName=patient.trim()?'':'disabled';
    return '<article class="bk-ticket bk-prefill"><h3 tabindex="-1">核对预填挂号信息</h3>'+patientField()+
      rows([['医院',destination],['科室',result.department],['医生',doctor.trim()||'待选择'],['期望时间',time||'不限'],
      ['挂号费用','以官方渠道显示为准']])+'</article><p class="bk-caption">请在爱尔官方渠道确认医生、实际号源和费用。当前信息保留在本次页面。</p>'+
      status()+'<div class="bk-stack">'+(provider.mode==='demo'?btn('demo','体验模拟挂号','primary',checkName):'')+
      btn('official','前往爱尔官方预约',provider.mode==='demo'?'secondary':'primary',checkName)+
      btn('copy','复制预问诊草稿')+btn('hospitals','修改医院或就诊偏好','text')+'</div>';
  }
  function demoPage() {
    const slots=demoSlots.map(slot=>'<button type="button" data-bk-slot="'+escape(slot.id)+
      '" class="bk-slot" aria-pressed="'+(slot.id===selectedSlot)+'"><span><strong>'+escape(slot.time)+'</strong><em>'+
      escape(slot.date+' · '+slot.period)+'</em></span><b>'+escape(slot.fee+' 元')+'</b></button>').join('');
    return '<h3 tabindex="-1">选择演示时段</h3><p class="bk-caption">以下时段和费用用于演示，不会提交预约或支付。</p>'+
      '<div class="bk-slots">'+slots+'</div>'+status()+'<div class="bk-stack">'+
      btn('demo-confirm','确认模拟挂号','primary',selectedSlot?'':'disabled')+
      btn('review','返回核对信息','text')+'</div>';
  }
  function receiptPage() {
    const slot=demoReceipt.slot;
    return '<article class="bk-ticket bk-prefill"><h3 tabindex="-1">模拟挂号已完成</h3>'+
      rows([['就诊人',patient.trim()],['医院',hospital||otherCity],['科室',assess(answers).department],
        ['意向医生',doctor.trim()||'未选择'],['演示时段',slot.date+' '+slot.time],['演示费用',slot.fee+' 元']])+
      '</article><p class="bk-caption">这是一份演示回执。实际就诊请在爱尔官方渠道办理。</p>'+
      status()+'<div class="bk-stack">'+btn('copy','复制预问诊草稿')+btn('review','返回核对信息','text')+
      btn('done','关闭','primary')+'</div>';
  }
  async function enterDemo(button) {
    if(page!=='review' || provider.mode!=='demo' || assess(answers).urgent || !patient.trim())return;
    const token=++demoVersion;
    demoQuery={date:preferredDate||calendarDay(1),period:preferredPeriod};
    button.disabled=true;
    try {
      const slots=await provider.listSlots(demoQuery);
      if(token!==demoVersion || page!=='review')return;
      if(!slots.length){sayStatus('当前没有演示时段，请修改就诊偏好。');return;}
      demoSlots=slots;selectedSlot='';demoReceipt=null;show('demo');
    } catch {
      if(token===demoVersion && page==='review')sayStatus('无法加载演示时段，请重试。');
    } finally {
      if(token===demoVersion && page==='review')button.disabled=!patient.trim();
    }
  }
  async function confirmDemo(button) {
    if(page!=='demo' || !selectedSlot || assess(answers).urgent)return;
    const token=++demoVersion;
    button.disabled=true;
    try {
      const receipt=await provider.confirm({query:demoQuery,slotId:selectedSlot});
      if(token!==demoVersion || page!=='demo')return;
      if(receipt.status!=='demo-confirmed')throw new Error('未取得演示回执');
      demoReceipt=receipt;show('receipt');
    } catch {
      if(token===demoVersion && page==='demo'){sayStatus('未生成演示回执，请重新选择时段。');button.disabled=false;}
    }
  }
  function paint() {
    stopMedia();
    const render={question:questionPage,result:resultPage,hospitals:hospitalPage,review:reviewPage,demo:demoPage,receipt:receiptPage};
    const html=render[page]();
    body.innerHTML='<section class="bk-page" data-bk-page="'+page+'">'+html+'</section>';
    body.scrollTop=0; window.scrollTo(0,0);
    get('h3')?.focus({preventScroll:true});
    if (page==='question') {refreshQuestion(); speak();}
  }
  function show(view) { page=view; paint(); }
  function next() {
    if (assess(answers).urgent) { show('result'); return; }
    if(!response(current()))return;
    if(index===2)show('result');else{index++;paint();}
  }
  function choose(option) {
    const q=current();
    if(!Object.prototype.hasOwnProperty.call(q.options,option))return;
    answers=update(answers,q,{...answer(q),choice:option});
    body.querySelectorAll('[data-bk-option]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.bkOption===option)));
    refreshQuestion();
  }
  function setNote(value) {
    const q=current();
    answers=update(answers,q,{...answer(q),note:value.slice(0,300)});
    refreshQuestion();
  }
  function setPatient(name,typed) {
    manualName=typed; patient=name.slice(0,40);
    const input=get('[data-bk-name]'), select=get('[data-bk-patient]'), official=get('[data-bk=official]');
    if(input && !typed)input.value=patient;
    if(select && typed)select.value='';
    if(official)official.disabled=!patient.trim();
    const demo=get('[data-bk=demo]');
    if(demo)demo.disabled=!patient.trim();
  }
  function setPatients(names) {
    if(!page || !Array.isArray(names))return;
    const next=[...new Set(names.filter(name=>typeof name==='string').map(name=>name.trim().slice(0,40)).filter(Boolean))].slice(0,30);
    if(next.length===patients.length && next.every((name,i)=>name===patients[i]))return;
    patients=next;
    if(!patient&&!manualName)patient=patients[0]||'';
    if(page==='result'||page==='review')paint();
  }
  function copyText() {
    if(!['result','review','receipt'].includes(page))return '';
    return '预问诊草稿（供医生核实）\n'+draftRows().map(([label,value])=>label+'：'+value).join('\n');
  }
  body.addEventListener('input',e=>{
    const field=e.target;
    if (page==='question' && field.matches('[data-bk-note]'))setNote(field.value);
    if (['result','review'].includes(page) && field.matches('[data-bk-name]'))setPatient(field.value,true);
    if (page!=='hospitals')return;
    if(field.matches('[data-bk-doctor]'))doctor=field.value.slice(0,40);
    if(field.matches('[data-bk-other-city]')){
      otherCity=field.value.slice(0,30);
      get('[data-bk=review]').disabled=!otherCity.trim();
    }
  });
  body.addEventListener('change',e=>{
    const field=e.target;
    if(field.matches('[data-bk-patient]') && field.value!==''){
      setPatient(patients[Number(field.value)]||'',false);
    }
    if(page!=='hospitals')return;
    if(field.matches('[data-bk-city]')){
      city=field.value;hospital='';doctor='';paint();get('[data-bk-city]')?.focus();
    }
    if(field.matches('[data-bk-date]')){
      preferredDate=field.validity.valid?field.value:'';
      if(field.value&&!preferredDate){field.value='';sayStatus('请选择今天或之后的日期。');}
    }
    if(field.matches('[data-bk-period]'))preferredPeriod=field.value;
  });
  body.addEventListener('click',e=>{
    const button=e.target.closest('button');
    if(!page||!button||button.disabled||!button.closest('[data-bk-page]'))return;
    if(button.hasAttribute('data-bk-option') && page==='question'){choose(button.dataset.bkOption);return;}
    if(button.hasAttribute('data-bk-hospital') && page==='hospitals'){
      const picked=button.dataset.bkHospital;
      if(!cities[city]?.some(([name])=>name===picked))return;
      if(hospital!==picked){hospital=picked;doctor='';get('[data-bk-doctor]').value='';}
      body.querySelectorAll('[data-bk-hospital]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
      get('[data-bk-doctors]').innerHTML=doctorChoices();
      get('[data-bk=review]').disabled=false;
      return;
    }
    if(button.hasAttribute('data-bk-slot') && page==='demo'){
      selectedSlot=button.dataset.bkSlot;
      body.querySelectorAll('[data-bk-slot]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
      get('[data-bk=demo-confirm]').disabled=false;
      return;
    }
    switch(button.dataset.bk){
      case 'voice':dictate();break;
      case 'speak':stopMedia();reading=!reading;button.setAttribute('aria-pressed',String(reading));
        button.setAttribute('aria-label',reading?'关闭朗读':'朗读问题');sayStatus('');if(reading)speak();break;
      case 'next':next();break;
      case 'previous':index--;paint();break;
      case 'edit':index=0;show('question');break;
      case 'hospitals':if(!assess(answers).urgent)show('hospitals');break;
      case 'review':if(!assess(answers).urgent && (hospital||city==='other'&&otherCity.trim()))show('review');break;
      case 'result':show('result');break;
      case 'demo':enterDemo(button);break;
      case 'demo-confirm':confirmDemo(button);break;
      case 'done':action('close');break;
      case 'copy':if(copyText())action('copy-draft');break;
      case 'directory':action('directory');break;
      case 'pick-doctor': {
        const item=matchingDoctors().find(item=>item.name===button.dataset.doctorName);
        if(item){doctor=item.name; get('[data-bk-doctor]').value=doctor; sayStatus('已填入意向医生');}
        break;
      }
      case 'doctor-lin': case 'doctor-zeng':
        if(matchingDoctors().some(item=>item.profile===button.dataset.bk))action(button.dataset.bk);
        break;
      case 'official':
        if(page==='review' && patient.trim() && !assess(answers).urgent){
          const route=cities[city]?.find(([name])=>name===hospital)?.[2] || 'appointment';
          action(route);
        }
        break;
    }
  });
  document.addEventListener('visibilitychange',()=>{if(document.hidden&&page)stopMedia();});
  return {mount:paint,leave,setPatients,copyText,status:sayStatus};
};
