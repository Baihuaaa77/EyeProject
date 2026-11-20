/* Binocular near reading at the user's usual phone distance. */
window.createPostGame = function createPostGame(body, onDone, save, onRestart) {
  'use strict';
  const art = window.PostArt;
  const levels = [
    { name: '大号', size: 30 },
    { name: '较大号', size: 24 },
    { name: '中号', size: 19 },
    { name: '较小号', size: 15 },
    { name: '小号', size: 12 }
  ];
  const habits = ['经常', '偶尔', '很少'];
  const rangeLetters = 6, minimumLetters = 10, maximumLetters = 14;
  const unclear = '看不清';
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let page = '', member = null, habit = '';
  let letter = null, trials = [], level = 0, confirmationPair = [], runTime = 0;
  const recordStatus = window.createTestRecordStatus(save);
  let pending = 0, complete = false, learned = false;

  const find = selector => body.querySelector(selector);
  function button(action, label, type = 'primary', attrs = '') {
    return '<button type="button" class="pg-' + type + '" data-post="' + action + '" ' + attrs + '>' + label + '</button>';
  }
  function heading(title, text) {
    return '<header class="pg-heading"><h3 tabindex="-1">' + title + '</h3><p>' + text + '</p></header>';
  }
  function show(name, html, focus = true) {
    page = name;
    body.innerHTML = '<section class="pg-page" data-pg-page="' + name + '">' + html + '</section>';
    window.fitOfflinePlayScene?.(body, focus);
    if (focus) {
      body.scrollTop = 0;
      find('.pg-heading h3').focus({preventScroll: true});
    }
  }
  function numberInk(number) {
    return '<span class="pg-address-ink">' + number + '</span>';
  }
  function envelope(number, fontSize) {
    return '<div class="pg-envelope" role="img" aria-label="信封上的门牌号">' +
      '<span class="pg-address" aria-hidden="true" style="font-size:' +
      fontSize + 'px">' + numberInk(number) + '</span></div>';
  }
  function desk(kind, contents) {
    return '<div class="pg-desk" data-post-desk="' + kind + '"><div class="pg-backdrop" aria-hidden="true"></div>' + contents + '</div>';
  }
  function stamps(count) {
    return Array.from({length: count}, (_, i) => '<span class="pg-stamp">' + art.stamp(i) + '</span>').join('');
  }
  function prepare() {
    show('prepare', heading('小芽的花园邮局', '按平时看消息的距离拿好手机，双眼看信封上的号码，把 10–14 封信投进同号信箱。') +
      desk('prepare', '<div class="pg-tray">' + envelope('28', 36) + '</div>') +
      button('practice', '试送第一封信'));
  }
  function shuffle(items) {
    for (let i = items.length - 1; i > 0; i--) {
      const other = Math.floor(Math.random() * (i + 1));
      [items[other], items[i]] = [items[i], items[other]];
    }
    return items;
  }
  function makeLetter() {
    const used = new Set([28, ...trials.map(item => item.number)]);
    const available = Array.from({length: 90}, (_, i) => i + 10).filter(n => !used.has(n));
    shuffle(available);
    const pivot = available[0];
    const tensMatch = available.find(n => n !== pivot && Math.floor(n / 10) === Math.floor(pivot / 10));
    const onesMatch = available.find(n => n !== pivot && n % 10 === pivot % 10);
    const boxes = tensMatch && onesMatch ? shuffle([pivot, tensMatch, onesMatch]) : available.slice(0, 3);
    // Choose the recipient after making the decoys. Their digit pattern cannot identify the answer.
    return {number: boxes[Math.floor(Math.random() * boxes.length)], boxes};
  }
  function playing(practice, hint = '') {
    const n = practice ? 28 : letter.number;
    const choices = practice ? [26, 28, 38] : letter.boxes;
    const boxes = choices.map((value, i) => {
      const guide = practice && value === n ? (learned ? ' pg-received' : ' pg-guided') : '';
      return '<button type="button" class="pg-box' + guide + '" data-post="mail" data-number="' + value +
        '" aria-label="' + value + ' 号信箱"' + (practice && learned ? ' disabled' : '') + '>' +
        art.mailbox(i) + '<b aria-hidden="true">' + numberInk(value) + '</b></button>';
    }).join('');
    const text = practice ? (hint || (learned ? '第一封信送到了！可以开始送信了。' : '信上写着 28，点一下 28 号信箱。')) :
      '保持刚才的距离，点同号信箱或「看不清」。';
    const count = Math.floor(trials.length / 2);
    const meter = practice ? '' : '<div class="pg-meter"><span data-pg-round>第 ' + (trials.length + 1) +
      ' 封</span><span class="pg-marks" aria-label="已收集 ' + count + ' 枚邮戳">' +
      (count ? stamps(count) : '') + '</span></div>';
    const controls = practice ? button('start', '开始送信', 'primary', learned ? '' : 'disabled') :
      '<div class="pg-actions">' + button('unclear', unclear, 'secondary') + button('finish', '提前结束', 'text') + '</div>';
    show(practice ? 'practice' : 'test', heading(practice ? '送出第一封信' : '帮小芽送信', text) +
      meter + desk(practice ? 'practice' : 'test', '<div class="pg-tray">' +
      envelope(n, practice ? 36 : levels[level].size) +
      '</div><div class="pg-boxes" role="group" aria-label="选择同号信箱">' + boxes + '</div>') + controls);
  }

  function start() {
    runTime = Date.now(); level = 0; trials = []; confirmationPair = []; habit = '';
    recordStatus.reset(); complete = false; letter = makeLetter(); playing(false);
  }
  function gradeCounts(index) {
    const rows = trials.filter(row => row.level === index);
    return {answered: rows.length, correct: rows.filter(row => row.correct).length,
      unclear: rows.filter(row => row.answer === null).length};
  }
  function probeLevel() {
    // First try the intermediate size just beyond the smallest readable anchor.
    const smallestRead = [4, 2, 0].find(i => {
      const {correct, answered} = gradeCounts(i);
      return correct >= 2 && correct * 2 > answered;
    });
    return smallestRead === undefined ? 0 : Math.min(smallestRead + 1, levels.length - 1);
  }
  function consistentResponse(index) {
    const rows = trials.filter(row => row.level === index);
    if (rows.filter(row => row.phase === 'confirm').length < 2) return null;
    // At 10 letters include the original observations. After another pair,
    // three consecutive observations can resolve an early slip.
    const recent = trials.length === minimumLetters ? rows : rows.slice(-3);
    if (recent.every(row => row.correct)) return true;
    if (recent.every(row => !row.correct)) return false;
    return null;
  }
  function refinePair() {
    const [easy, hard] = confirmationPair;
    const easyRead = consistentResponse(easy), hardRead = consistentResponse(hard);
    if (easyRead === null || hardRead === null || (!easyRead && hardRead)) return;
    // If both land on one side, recenter towards an observed contrary result.
    if (!easyRead && easy > 0 && trials.some(row => row.level < easy && row.correct)) {
      confirmationPair = [easy - 1, easy];
      level = easy - 1;
    } else if (hardRead && hard < levels.length - 1 && trials.some(row => row.level > hard && !row.correct)) {
      confirmationPair = [hard, hard + 1];
      level = hard + 1;
    } else {
      complete = true;
    }
  }
  function record(value) {
    const phase = trials.length < rangeLetters ? 'range' : 'confirm';
    trials.push({number: letter.number, level, size: levels[level].size, phase, answer: value, correct: value === letter.number});
    recordStatus.reset();
    if (trials.length < rangeLetters) {
      if (trials.length % 2 === 0) {
        const pair = trials.slice(-2);
        if (pair.every(row => row.correct)) level = Math.min(level + 2, levels.length - 1);
        else if (pair.every(row => !row.correct)) level = Math.max(level - 2, 0);
      }
    } else if (trials.length === rangeLetters) {
      level = probeLevel();
    } else {
      if (trials.length === rangeLetters + 1) {
        const easier = Math.max(0, Math.min(levels.length - 2, level - (value === letter.number ? 0 : 1)));
        confirmationPair = [easier, easier + 1];
      }
      level = level === confirmationPair[0] ? confirmationPair[1] : confirmationPair[0];
      if (trials.length >= maximumLetters) complete = true;
      else if (trials.length >= minimumLetters && trials.length % 2 === 0) refinePair();
    }
    letter = null;
  }
  function receive(box) {
    if (pending || (page !== 'test' && page !== 'practice')) return;
    const practice = page === 'practice', number = box ? Number(box.dataset.number) : null;
    if (practice) {
      if (learned) return;
      if (number !== 28) {
        find('.pg-heading p').textContent = '再看一眼信封上的 28，点相同号码的信箱。';
        return;
      }
      learned = true;
    } else {
      if (!letter || (number !== null && !letter.boxes.includes(number))) return;
      record(number);
    }
    body.querySelectorAll('.pg-box, [data-post=unclear]').forEach(el => { el.disabled = true; });
    const paper = find('.pg-envelope');
    if (!motion.matches && typeof paper.animate === 'function') {
      const from = paper.getBoundingClientRect(), to = box?.getBoundingClientRect();
      const scale = from.width / paper.offsetWidth;
      const x = box ? (to.left + to.width / 2 - from.left - from.width / 2) / scale : -paper.offsetWidth;
      const y = box ? (to.top + to.height * .36 - from.top - from.height / 2) / scale : 10;
      paper.animate([{transform:'translate(0,0) scale(1)',opacity:1},
        {transform:'translate('+x+'px,'+y+'px) scale(.12)',opacity:0}],
        {duration:330,easing:'ease-in',fill:'forwards'});
    }
    pending = setTimeout(() => {
      pending = 0;
      if (practice) playing(true);
      else if (complete) result();
      else { letter = makeLetter(); playing(false); }
    }, motion.matches ? 90 : 370);
  }
  function statistics() {
    const bySize = levels.map((item, i) => ({...item, ...gradeCounts(i)}));
    let answer;
    if (!complete) answer = '本次已记录 ' + trials.length + ' 封信';
    else if (trials.every(row => row.answer === null)) answer = trials.length + ' 封信都选了「看不清」';
    else {
      const shown = [...confirmationPair];
      if (shown.every(i => bySize[i].correct < 2)) {
        for (let i = shown[0] - 1; i >= 0; i--) {
          const item = bySize[i];
          if (item.correct >= 2 && item.correct * 2 > item.answered) { shown.unshift(i); break; }
        }
      }
      answer = shown.map(i => {
        const item = bySize[i];
        return item.name + '读对 ' + item.correct + '/' + item.answered + ' 封';
      }).join('，');
    }
    return {version: 3, answer, complete, habit: habit || null,
      distance: '自选阅读距离', viewing: '双眼', levels: bySize,
      trials: trials.map(row => ({...row})), stamps: Math.floor(trials.length / 2)};
  }
  function result(focus = true) {
    clearTimeout(pending); pending = 0;
    const report = statistics();
    const description = trials.length ? '按刚才的阅读距离，' + report.answer + '。' :
      '还没有投出信件。可以继续送信，记录哪些大小的号码能看清。';
    const postcard = '<div class="pg-postcard"><span>本次邮戳</span><strong>' + report.stamps +
      ' <small>枚</small></strong><div class="pg-collection" aria-hidden="true">' + stamps(report.stamps) + '</div></div>';
    const rows = report.levels.map(item => '<li><span>' + item.name + '数字</span><b>' +
      (item.answered ? '读对 ' + item.correct + ' / ' + item.answered + ' 封' : '未投递') + '</b></li>').join('');
    const survey = '<fieldset class="pg-habit"><legend>平时看小字，你会把手机拿远一点吗？</legend><div>' +
      habits.map(value => button('habit',value,'pill','data-value="' + value + '" aria-pressed="' + (value === habit) + '"')).join('') + '</div></fieldset>';
    recordStatus.update(member,runTime,report,trials.length > 0);
    const actions = (!complete ? button('resume','继续送信') : '') +
      button('done',recordStatus.finishLabel,complete?'primary':'secondary') + button('again','重新测试','text');
    show('result', heading('本次小字辨认记录',description) + recordStatus.markup(button('save','重试保存','secondary')) + desk('result',postcard) +
      '<ul class="pg-levels" aria-label="各档辨认记录">' + rows + '</ul>' + (trials.length ? survey : '') +
      '<div class="pg-actions">' + actions + '</div>', focus);
  }
  function leave() {
    clearTimeout(pending); pending = 0; page = ''; member = null; letter = null; trials = [];
  }
  function mount(id) {
    if (page) return;
    member = id;
    learned = false; prepare();
  }
  function back() {
    if (page === 'practice') { clearTimeout(pending); pending = 0; prepare(); return true; }
    if (page === 'test') { result(); return true; }
    return false;
  }
  body.addEventListener('click', event => {
    const el = event.target.closest('[data-post]');
    if (!el || !page || el.disabled) return;
    switch (el.dataset.post) {
      case 'practice': learned = false; playing(true); break;
      case 'start': if (learned) start(); break;
      case 'mail': receive(el); break;
      case 'unclear': receive(null); break;
      case 'finish': result(); break;
      case 'resume': if (!letter) letter = makeLetter(); playing(false); break;
      case 'again':
        if (onRestart) { leave(); onRestart(); }
        else { learned = false; prepare(); }
        break;
      case 'habit':
        habit = el.dataset.value === habit ? '' : el.dataset.value;
        recordStatus.reset();
        result(false);
        find('[data-post=habit][data-value="' + el.dataset.value + '"]').focus({preventScroll:true});
        break;
      case 'save': result(); break;
      case 'done': leave(); onDone(); break;
    }
  });
  return {mount, back, leave};
};
