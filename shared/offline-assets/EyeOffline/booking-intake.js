// 根据预问诊回答提供科室建议。患者自述与医生诊断分别处理。
window.BookingIntake = (() => {
  const question = (id,label,title,options) => ({id,label,title,options});
  const chief = question('chief','主诉','您最想解决哪个眼部问题？',{
    vision:'视力下降或看不清',surface:'眼红、干涩或眼痒',pain:'眼痛或眼胀',
    floaters:'飞蚊、闪光或黑影',refraction:'近视、散光或配镜',review:'复查或常规检查'
  });
  const second = {
    vision:question('vision','视力变化','视力变化主要是什么样的？',{
      sudden:'突然下降或明显加重',gradual:'逐渐看不清',near:'近处更模糊',far:'远处更模糊',unknown:'说不清'
    }),
    surface:question('surface','伴随表现','除了眼红、干涩或眼痒，还有什么表现？',{
      redPain:'眼红伴眼痛、怕光或视力变化',contactPain:'戴隐形眼镜后眼红、眼痛',
      dry:'干涩、异物感',itch:'眼痒或流泪',discharge:'有分泌物',unknown:'说不清'
    }),
    pain:question('pain','疼痛表现','眼痛或眼胀时，有下面哪种情况？',{
      severe:'剧烈眼痛，或伴头痛、恶心',visionLoss:'突然看不清或看不到',
      injury:'眼外伤或化学品入眼',mild:'轻度疼痛或眼胀',unknown:'说不清'
    }),
    floaters:question('floaters','起病情况','飞蚊、闪光或黑影最近有变化吗？',{
      curtain:'看到闪光、黑幕或视野被遮挡',new:'近期新出现或突然增多',stable:'已经有一段时间，近期没有变化',unknown:'说不清'
    }),
    refraction:question('refraction','验光需求','这次验光或配镜主要是为了什么？',{
      child:'儿童或青少年近视检查',glasses:'成人验光或更换眼镜',contact:'隐形眼镜验配',
      surgery:'了解近视矫正手术',unknown:'还不确定'
    }),
    review:question('review','复查项目','这次想复查哪一项？',{
      cataract:'白内障或白内障术后',glaucoma:'青光眼或眼压偏高',retina:'眼底疾病',
      refraction:'近视矫正术后',pediatric:'斜视或小儿眼病',general:'常规检查或其他'
    }),
    other:question('other','主要表现','您补充的情况最接近哪一类？',{
      vision:'视力变化',surface:'眼红、眼干或眼痒',pain:'眼痛或眼胀',
      floaters:'飞蚊、闪光或黑影',refraction:'验光配镜或复查',unknown:'说不清'
    })
  };
  const third = {
    visionHistory:question('visionHistory','相关情况','视力变化前后，还有哪些情况？',{
      cataract:'医生说过有白内障',glaucoma:'医生说过有青光眼或眼压偏高',retina:'确诊过眼底疾病',
      light:'眩光或夜间看不清',none:'没有上述情况',unknown:'说不清'
    }),
    nearFar:question('nearFar','用眼情况','看不清在什么情况下最明显？',{
      correction:'戴原来的眼镜也看不清',reading:'阅读或看手机时明显',
      distance:'看黑板、路标时明显',both:'远近都看不清',unknown:'说不清'
    }),
    dryContext:question('dryContext','出现时机','干涩或异物感通常什么时候更明显？',{
      screen:'长时间用屏后',morning:'晨起时',contact:'佩戴隐形眼镜后',often:'经常出现',unknown:'说不清'
    }),
    surfaceContext:question('surfaceContext','伴随情况','最近还有下列哪种情况？',{
      pain:'开始眼痛、怕光或视力变差',contact:'戴隐形眼镜后出现红痛',
      both:'双眼都不适',one:'主要是一只眼',unknown:'说不清'
    }),
    painContext:question('painContext','起病情况','眼痛或眼胀发生在什么情况下？',{
      worsening:'最近突然出现或明显加重',screen:'长时间用眼后更明显',
      recurrent:'反复出现',glaucoma:'有青光眼或眼压偏高病史',unknown:'说不清'
    }),
    floatContext:question('floatContext','累及眼别','哪只眼能看到这些飞蚊或黑影？',{
      left:'主要是左眼',right:'主要是右眼',both:'两眼都有',unknown:'分不清'
    }),
    refractionChild:question('refractionChild','验光情况','孩子这次检查的主要原因是什么？',{
      first:'第一次看远看不清',progress:'近视度数近期有变化',regular:'定期复查近视',
      strabismus:'有斜视、弱视或怀疑眼位不齐',unknown:'说不清'
    }),
    surgeryContext:question('surgeryContext','就诊目的','您目前想了解哪方面？',{
      first:'首次咨询近视矫正手术',recheck:'做过术前检查，想进一步评估',
      after:'近视矫正术后复查',unknown:'还不确定'
    }),
    refractionContext:question('refractionContext','验光情况','这次检查更接近哪种情况？',{
      first:'第一次验光',replace:'原眼镜不合适',contact:'需要隐形眼镜验配',routine:'按期复查',unknown:'说不清'
    }),
    reviewContext:question('reviewContext','复查原因','这次复查前有新变化吗？',{
      routine:'按医嘱定期复查',renew:'原有症状有变化',postOp:'术后新出现明显眼痛、红眼加重或视力下降',
      latest:'拿到新的检查结果，想请医生看',unknown:'说不清'
    }),
    clarify:question('clarify','补充情况','这次主要是新出现的不适，还是计划复查？',{
      sudden:'突然看不清、剧烈眼痛或眼外伤',recent:'近期有新不适',
      follow:'按计划检查或复查',unknown:'暂时说不清'
    })
  };
  const entry = (answers,id) => answers[id] || {choice:'',note:''};
  const choice = (answers,id) => entry(answers,id).choice;
  const line = (q,answers) => [Object.prototype.hasOwnProperty.call(q.options,choice(answers,q.id)) ? q.options[choice(answers,q.id)] : '',
    entry(answers,q.id).note.trim()].filter(Boolean).join('，');
  const symptoms = {
    vision:/看不清|视物模糊|视力下降|视力变差|看东西模糊/,
    surface:/眼干|干眼|干涩|眼红|眼痒|异物感/,
    pain:/眼痛|眼胀|眼疼/,
    floaters:/飞蚊|闪光|黑影|黑幕|眼前飘|漂浮物/,
    refraction:/验光|配镜|眼镜|近视|散光/,
    review:/复查|体检|常规检查/
  };
  // 按句识别当前自述。既往史和否定表达应保留在草稿中，但不作为确诊或阳性信号。
  function phrases(note) {
    return note.split(/[，,。；;！？!?\n]|但是|不过|但|然而|随后|而是|现在|目前|这次|今天|刚才|今早|这会儿/)
      .map(s => s.trim()).filter(Boolean);
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


  function topic(answers) {
    const selected = choice(answers,'chief');
    if (Object.prototype.hasOwnProperty.call(chief.options,selected)) return selected;
    const parts = phrases(entry(answers,'chief').note);
    const hits = Object.entries(symptoms).filter(([,pattern]) => parts.some(part => !uncertain.test(part) && stated(part,pattern)));
    return hits.length === 1 ? hits[0][0] : 'other';
  }
  function questions(answers) {
    const first = topic(answers), follow = second[first], picked = choice(answers,follow.id);
    let last = third.clarify;
    switch (first) {
      case 'vision': last = ['near','far'].includes(picked) ? third.nearFar : third.visionHistory; break;
      case 'surface': last = picked === 'dry' ? third.dryContext : third.surfaceContext; break;
      case 'pain': last = third.painContext; break;
      case 'floaters': last = third.floatContext; break;
      case 'refraction': last = picked === 'child' ? third.refractionChild : picked === 'surgery' ? third.surgeryContext : third.refractionContext; break;
      case 'review': last = third.reviewContext; break;
      case 'other':
        last = ({vision:third.visionHistory,surface:third.surfaceContext,pain:third.painContext,
          floaters:third.floatContext,refraction:third.reviewContext})[picked] || third.clarify;
        break;
    }
    return [chief,follow,last];
  }
  function update(answers,q,answer) {
    const updated = {...answers,[q.id]:answer};
    const active = new Set(questions(updated).map(item => item.id));
    return Object.fromEntries(Object.entries(updated).filter(([id]) => active.has(id)));
  }

  const urgent = reason => ({department:'眼科急诊',priority:'建议立即就诊',urgent:true,reason,checks:[]});
  const manual = (prompt = false) => ({department:'综合眼科',priority:prompt?'请尽快人工导诊':'请人工导诊',urgent:false,
    reason:prompt?'请尽快让分诊人员确认就诊时效。若症状突然出现、明显加重，或伴剧烈眼痛、视力下降，请立即前往急诊。'
      :'请让分诊人员核对症状及变化，再决定合适的专科和就诊时间。',checks:[]});
  const clinics = {
    general:['综合眼科','先由综合眼科核对症状和病史。',['视力检查']],
    surface:['角膜及眼表科','主要不适集中在眼表，可先咨询角膜及眼表科。',['裂隙灯检查']],
    retina:['眼底病专科','可先由眼底病专科评估飞蚊、黑影或既往眼底问题。',['眼底检查']],
    optometry:['视光科','可先咨询验光、配镜或屈光检查。',['视力检查','验光']],
    refractive:['屈光手术科','可由医生评估矫正手术相关需求或安排原专科复查。',['视力检查','验光']],
    pediatric:['斜视及小儿眼科','可向小儿眼科咨询眼位、弱视或儿童视力问题。',['视力检查']],
    cataract:['白内障科','结合自述的白内障病史或复查目的，可联系原专科。',['视力检查','裂隙灯检查']],
    glaucoma:['青光眼科','结合自述的青光眼或眼压病史，可联系原专科。',['眼压检查']]
  };
  function routine(key) {
    const [department,reason,checks] = clinics[key];
    return {department,reason,checks,priority:'门诊参考',urgent:false};
  }
  const urgentChoices = new Set(['sudden','redPain','contactPain','severe','visionLoss','injury','curtain','new','postOp']);
  function assess(answers = {}) {
    const active = questions(answers);
    const parts = active.flatMap(q => phrases(entry(answers,q.id).note));
    if (active.some(q => urgentChoices.has(choice(answers,q.id))) ||
        parts.some(p => [loss,injury,pain,shadow].some(pattern => stated(p,pattern)))) {
      return urgent('你描述的情况需要尽快当面检查，请现在前往就近的眼科急诊或综合医院急诊。');
    }
    const any = pattern => parts.some(p => !uncertain.test(p) && stated(p,pattern));
    if ((any(flashes) && any(recent)) || (any(/眼红|红眼/) && any(/眼痛|眼疼|怕光|视力下降|看不清/))) {
      return urgent('你描述的症状组合需要尽快检查，请现在前往就近的眼科急诊或综合医院急诊。');
    }
    if (parts.some(p => uncertain.test(p) && [loss,injury,pain,shadow,flashes].some(pattern => pattern.test(p)))) return manual(true);
    const [ ,follow,last] = active;
    const selected = choice(answers,follow.id), detail = choice(answers,last.id);
    switch (follow.id) {
      case 'vision':
        return routine(({cataract:'cataract',glaucoma:'glaucoma',retina:'retina'})[detail] ||
          (['near','far'].includes(selected) ? 'optometry' : 'general'));
      case 'surface':
        return ['dry','itch','discharge'].includes(selected) ? routine('surface') : manual();
      case 'pain':
        return detail === 'glaucoma' && selected === 'mild' ? routine('glaucoma') : manual(true);
      case 'floaters':
        return selected === 'stable' ? routine('retina') : manual(true);
      case 'refraction':
        if (detail === 'strabismus') return routine('pediatric');
        if (selected === 'surgery') return routine('refractive');
        return ['child','glasses','contact'].includes(selected) ? routine('optometry') : manual();
      case 'review':
        return routine(({cataract:'cataract',glaucoma:'glaucoma',retina:'retina',
          refraction:'refractive',pediatric:'pediatric'})[selected] || 'general');
      default: return manual();
    }
  }

  function draft(answers,patient) {
    const [first,...rest] = questions(answers);
    const rows = [['就诊人',patient.trim() || '未填写'], [first.label,line(first,answers) || '未填写']];
    rest.filter(q => line(q,answers)).forEach(q => rows.push([q.label,line(q,answers)]));
    const assessment = assess(answers);
    rows.push(['导诊参考',assessment.department+'，'+assessment.priority]);
    return rows;
  }

  return {questions,update,assess,draft,line};
})();
