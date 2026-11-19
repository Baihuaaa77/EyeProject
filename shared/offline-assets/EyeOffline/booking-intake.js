// The nine interview steps follow the registration assistant shown in the reference video.
window.BookingIntake = (() => {
  const questions = [
    {id:'chief', label:'主诉', title:'请问您今天最主要的眼部不适是什么？', options:[
      '视力下降或看不清','眼红、干涩或眼痒','眼痛或眼胀','飞蚊、闪光或黑影','近视、散光或配镜','复查或常规检查'
    ]},
    {id:'duration', label:'现病史 · 病程', title:'这些症状大约出现多久了？', options:[
      '今天突然出现','1～3 天','1～4 周','1 个月以上','半年以上'
    ]},
    {id:'eye', label:'现病史 · 部位', title:'不适发生在哪只眼睛？', options:['左眼','右眼','双眼','不确定']},
    {id:'danger', label:'现病史 · 危险信号', title:'目前是否有以下需要尽快处理的情况？', options:[
      '突然看不见或视力骤降','剧烈眼痛并伴恶心呕吐','眼外伤或化学物入眼','以上情况都没有'
    ]},
    {id:'history', label:'既往史 · 眼病', title:'您以前有过哪些眼病或眼部手术？', options:[
      '白内障或白内障手术','青光眼或眼压高','眼底病或视网膜病变','近视矫正或其他眼部手术','没有相关病史'
    ]},
    {id:'chronic', label:'既往史 · 全身疾病', title:'您是否患有以下慢性疾病？', options:[
      '糖尿病','高血压或心血管疾病','免疫或甲状腺疾病','有多种慢性疾病','没有以上疾病'
    ]},
    {id:'allergy', label:'过敏史', title:'您是否有药物过敏史？', options:['有药物过敏','没有药物过敏','不清楚']},
    {id:'family', label:'家族史', title:'直系亲属是否有青光眼或其他遗传性眼病？', options:[
      '有青光眼家族史','有其他遗传性眼病','没有相关家族史','不清楚'
    ]},
    {id:'time', label:'挂号偏好', title:'您希望什么时间就诊？', options:['今天，越快越好','明天上午','明天下午','时间都可以']}
  ];

  const choice = (answers,id) => answers?.[id]?.choice || '';
  const note = (answers,id) => (answers?.[id]?.note || '').trim();
  const dangerChoices = questions.find(q => q.id === 'danger').options.slice(0,3);
  const urgent = reason => ({department:'眼科急诊', priority:'建议立即就诊', urgent:true, reason});
  const manual = () => ({department:'综合眼科', priority:'请人工导诊', urgent:false,
    reason:'根据现有描述还不能确定合适的专科，请让分诊人员核对本次症状。'});
  const ordinary = (department,reason) => ({department,priority:'门诊就诊',urgent:false,reason});

  // Only explicit symptoms in the present illness affect urgent routing.
  // Medications, past operations and relatives' symptoms belong in the interview summary.
  function presentPhrases(answers) {
    return ['chief','duration','eye','danger'].flatMap(id => note(answers,id)
      .split(/[，,。；;！？!?\n]|但是|不过|但|然而|随后|而是|现在|目前|这次|今天|刚才|今早|这会儿/)
      .map(s => s.trim()).filter(Boolean));
  }
  const denial = /没(?:有)?|并无|毫无|否认|不伴(?:有)?|不是|从未|未曾|未|无|已排除/g;
  const modifiersOnly = /^(?:发现|发生|出现|伴有|感觉(?:到)?|觉得|发生过|看见|看到|有过|过|任何|明显|新的|新出现|突然|忽然|那种|上述|这种|一点|一块|的|有|眼前|视野(?:中|里)?|眼睛|左眼|右眼|双眼|像|刚刚)*$/;
  function deniedBefore(before) {
    const negatives = Array.from(before.matchAll(denial));
    if (!negatives.length) return false;
    const last = negatives[negatives.length-1];
    const between = before.slice(last.index + last[0].length).trim();
    if (modifiersOnly.test(between)) return true;
    // In “没有闪光、飞蚊或黑影”, the same denial covers the coordinated list.
    const entries = between.split(/、|或者|或|以及|和|及/);
    return entries.length > 1 && modifiersOnly.test(entries[entries.length-1]);
  }
  const uncertain = /是否|是不是|会不会|不(?:太)?确定|拿不准|说不(?:清|准)|不清楚|不知道/;
  const past = /以前|既往|过去|从前|小时候|曾经|去年|前年|年前/;
  function stated(phrase,pattern) {
    if (uncertain.test(phrase)) return false;
    for (const match of phrase.matchAll(new RegExp(pattern.source,'g'))) {
      const before = phrase.slice(0,match.index);
      if (past.test(before) && !/(?:无|没有|没|从未|未曾|否认).{0,6}(?:眼痛|头痛|外伤|眼病|病史|手术|受伤|眼疾)(?:史)?$/.test(before)) continue;
      if (!deniedBefore(before) &&
          !/^(?:都)?(?:没有|已排除|未出现)/.test(phrase.slice(match.index + match[0].length))) return true;
    }
    return false;
  }
  const loss = /(?:突然|忽然).{0,6}(?:看不见|看不到|失明|看不清|视力(?:下降|变差)|失去视力)|(?:视力|视物).{0,4}(?:骤降|急降|突然下降)|完全看不见|什么(?:都|也)看不见/;
  const injury = /眼(?:部)?外伤|眼(?:睛|球)(?:受伤|被刺伤|被刺穿)|(?:化学(?:品|物|液体)|酸液|碱液|清洁剂|漂白剂|消毒液).{0,10}(?:溅|进|入).{0,5}眼|眼.{0,6}(?:溅入|进了).{0,4}(?:酸|碱|化学|消毒液)/;
  const pain = /(?:剧烈|剧|严重).{0,2}眼痛|眼(?:睛)?(?:剧痛|剧烈疼痛|疼得厉害|痛得厉害|[疼痛]得(?:想吐|呕吐))/;
  const shadow = /黑幕|幕布|帘子|窗帘|黑布.{0,5}(?:挡|遮)|视野(?:缺(?:损|失)|被遮(?:住|挡))|黑影.{0,5}(?:遮挡|挡住|遮住)/;
  const flashes = /闪光|飞蚊|黑影|漂浮物/;
  const recent = /突然|骤然|刚刚|刚出现|新出现|第一次|首次|猛增|增多|变多/;

  // Urgency references: NHS Vision loss / Eye injuries and NEI Retinal Detachment.
  function assess(answers = {}) {
    const chief = choice(answers,'chief'), duration = choice(answers,'duration');
    const danger = choice(answers,'danger'), history = choice(answers,'history');
    const phrases = presentPhrases(answers);
    if (dangerChoices.includes(danger)) {
      return urgent('你选择了「'+danger+'」。请立即到最近的眼科急诊或综合医院急诊。');
    }
    if (phrases.some(p => [loss,injury,pain,shadow].some(pattern => stated(p,pattern)))) {
      return urgent('你的描述中有需要立即面诊的表现，请现在到眼科急诊或综合医院急诊。');
    }
    const newFlashes = phrases.some(p => stated(p,flashes) && stated(p,recent));
    const shortCourse = duration === '今天突然出现' || duration === '1～3 天';
    if (newFlashes || (shortCourse && (chief === '飞蚊、闪光或黑影' || phrases.some(p => stated(p,flashes))))) {
      return urgent('近期新出现的飞蚊、闪光或黑影需要尽快检查眼底，请现在到眼科急诊评估。');
    }
    if (duration === '今天突然出现' && ['视力下降或看不清','眼痛或眼胀'].includes(chief)) {
      return urgent('你描述的视力变化或眼部疼痛是今天突然出现的，请现在到眼科急诊评估。');
    }
    if (phrases.some(p => uncertain.test(p) && [loss,injury,pain,shadow,flashes].some(pattern => pattern.test(p)))) {
      return manual();
    }
    if (note(answers,'danger') && !danger && !/^(?:以上情况都没有|都没有|没有|无)[。！! ]*$/.test(note(answers,'danger'))) {
      return manual();
    }
    switch (chief) {
      case '近视、散光或配镜':
        return ordinary('眼视光门诊','你主要想了解屈光情况或配镜，眼视光门诊可以安排视力和验光检查。');
      case '飞蚊、闪光或黑影':
        return ordinary('眼底病门诊','你提到了飞蚊、闪光或黑影，眼底病门诊可以检查眼底。如果突然增多或出现遮挡，请立即就诊。');
      case '眼红、干涩或眼痒':
        return ordinary('眼表门诊','你提到了眼红、干涩或眼痒，可以先到眼表门诊检查。');
      case '复查或常规检查': {
        const followUp = {'白内障或白内障手术':'白内障门诊','青光眼或眼压高':'青光眼门诊','眼底病或视网膜病变':'眼底病门诊'}[history];
        return followUp ? ordinary(followUp,'结合本次复查目的和既往眼病，建议联系原就诊专科。')
          : ordinary('综合眼科','你希望复查或做常规眼部检查，可以先到综合眼科。术后复查可联系原手术门诊。');
      }
      case '视力下降或看不清':
      case '眼痛或眼胀':
        return ordinary('综合眼科','你提到的眼部不适可能有多种原因，建议先到综合眼科面诊。');
      default: return manual();
    }
  }

  function slots(answers, assessment = assess(answers)) {
    if (assessment.urgent) return [{id:'urgent-now',name:'李医生',rank:'副主任医师',when:'今天 · 立即就诊',fee:30}];
    const time = choice(answers,'time');
    const options = {
      today:{id:'today',name:'周医生',rank:'主治医师',when:'今天 · 当日门诊',fee:20},
      morning:{id:'morning',name:'陈医生',rank:'副主任医师',when:'明天上午',fee:30},
      afternoon:{id:'afternoon',name:'林医生',rank:'主治医师',when:'明天下午',fee:20}
    };
    if (time === '今天，越快越好') return [options.today];
    if (time === '明天上午') return [options.morning];
    if (time === '明天下午') return [options.afternoon];
    return Object.values(options);
  }
  return {questions,assess,slots};
})();
