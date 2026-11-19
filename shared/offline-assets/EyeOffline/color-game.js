/* Mixed dot plates. Only the original eight numeric plates use the original scoring rule. */
window.createColorGame = function (body, onDone, save, onRestart) {
  'use strict';
  const drawings = window.ColorPlates;
  const plates = [drawings.example, ...drawings.questions];
  const unknown = '看不出';
  const numberTotal = drawings.questions.filter(plate => plate.kind === 'number').length;
  const animalTotal = drawings.questions.length - numberTotal;
  let page = '', member = null, index = 0, runTime = 0;
  let choices = [], answers = [], started = null, spent = 0;
  const recordStatus = window.createTestRecordStatus(save);
  let pending = 0, revisit = [];

  function choiceOrder(options) {
    const values = options.filter(value => value !== unknown).slice();
    for (let i = values.length - 1; i > 0; i--) {
      const other = Math.floor(Math.random() * (i + 1));
      [values[i], values[other]] = [values[other], values[i]];
    }
    return [...values, unknown];
  }

  function button(action, label, style = 'secondary', extra = '') {
    return '<button type="button" class="cg-' + style + '" data-color="' + action + '" ' + extra + '>' + label + '</button>';
  }

  function plateCanvas(label, preview = false) {
    return '<canvas width="600" height="600" role="img" aria-label="' + label + '" class="cg-canvas' +
      (preview ? ' cg-preview' : '') + '">彩色点阵图</canvas>';
  }

  function draw(html, selectedPlate, focus = true) {
    const attributes = page === 'plate' ? ' data-plate-id="' + selectedPlate.id + '" data-kind="' + selectedPlate.kind + '"' : '';
    body.innerHTML = '<section class="cg-page" data-cg-page="' + page + '"' + attributes + '>' + html + '</section>';
    if (selectedPlate) drawings.draw(body.querySelector('.cg-canvas'), selectedPlate);
    if (focus) {
      body.scrollTop = 0;
      body.querySelector('.cg-title')?.focus({ preventScroll: true });
    }
  }

  function prep() {
    page = 'prepare';
    draw('<header><h3 class="cg-title" tabindex="-1">辨认点阵里的图案</h3>' +
      '<p class="cg-intro">找个光线均匀的地方，关闭夜览、护眼色温和颜色滤镜。看彩色圆点，选出里面的数字或动物。</p>' +
      '<p class="cg-count">1 张练习图 · ' + numberTotal + ' 张数字图 · ' + animalTotal + ' 张动物图</p></header>' +
      '<div class="cg-cover">' + plateCanvas('准备页练习点阵', true) + '</div>' +
      button('begin', '开始自测', 'primary'), drawings.example);
  }

  function pauseClock() {
    if (started === null) return;
    spent += Math.max(0, performance.now() - started);
    started = null;
  }

  function showPlate(next) {
    page = 'plate'; index = next; spent = 0;
    const plate = plates[index], animal = plate.kind === 'animal';
    const progress = index ? '正式图 · ' + index + ' / ' + drawings.questions.length : '练习图 · 1 / 1';
    const title = animal ? '你看到哪种动物？' : '你看到哪个数字？';
    const prior = answers[index]?.value;
    const answersMarkup = choices[index].map(value => button('answer', value, 'choice' +
      (value === unknown ? ' cg-unknown' : ''), 'data-value="' + value + '" aria-pressed="' + (prior === value) + '"')).join('');
    const navigation = (index ? button('previous', '上一张', 'text') : '') +
      (animal ? button('skip', '跳过这张', 'text') : '') + button('finish', '提前结束', 'text');
    draw('<header><h3 class="cg-title" tabindex="-1">' + title + '</h3>' +
      '<p class="cg-counter" aria-live="polite">' + progress + '</p></header>' +
      '<div class="cg-progress" aria-hidden="true"><i style="width:' + (index / drawings.questions.length * 100) + '%"></i></div>' +
      '<div class="cg-field">' + plateCanvas(animal ? '由彩色圆点组成的动物图' : '由彩色圆点组成的数字图') + '</div>' +
      '<div class="cg-choices" role="group" aria-label="选择看到的图案">' + answersMarkup + '</div>' +
      '<nav class="cg-nav" aria-label="作答导航">' + navigation + '</nav>', plate);
    started = document.hidden ? null : performance.now();
  }

  function summarizeKind(kind) {
    const rows = plates.slice(1).map((plate, idx) => ({ plate, response: answers[idx + 1] }))
      .filter(item => item.plate.kind === kind);
    const answered = rows.filter(item => item.response && !item.response.skipped);
    const correct = answered.filter(item => item.response.correct).length;
    return { total: rows.length, answered: answered.length, correct, errors: answered.length - correct };
  }

  function report() {
    const numbers = summarizeKind('number'), animals = summarizeKind('animal');
    const example = answers[0] || null;
    numbers.valid = Boolean(example?.correct && numbers.answered === numberTotal);
    numbers.risk = !numbers.valid ? 'UNKNOWN' : numbers.errors < 2 ? 'LOW' : numbers.errors < 4 ? 'MEDIUM' : 'HIGH';
    const misses = plates.slice(1).filter((plate, idx) => plate.kind === 'number' && answers[idx + 1] && !answers[idx + 1].correct);
    numbers.protanErrors = misses.filter(plate => plate.type === 'PROTAN').length;
    numbers.deutanErrors = misses.filter(plate => plate.type === 'DEUTAN').length;
    const titles = { UNKNOWN: '数字题暂无法评估', LOW: '数字图辨认顺利', MEDIUM: '数字图有几张未辨认', HIGH: '多张数字图未辨认' };
    const responses = plates.map((plate, idx) => ({
      id: plate.id, kind: plate.kind, example: idx === 0, target: plate.answer,
      ...(answers[idx] || { value: null, skipped: false, correct: null, rtMs: null })
    }));
    return { answer: titles[numbers.risk], numbers, animals, example: example && { ...example }, responses };
  }

  function stat(name, label, value, desc) {
    return '<section class="cg-stat" data-cg-summary="' + name + '"><h4>' + label + '</h4>' +
      '<strong>' + value + '</strong><small>' + desc + '</small></section>';
  }

  function records(summary) {
    return '<details class="cg-breakdown"><summary>逐张作答记录</summary><ol>' +
      summary.responses.map((row, i) => {
        const label = i === 0 ? '练习图' : '第 ' + i + ' 张 · ' + (row.kind === 'animal' ? '动物' : '数字');
        const result = row.skipped ? '已跳过' : row.value === null ? '未作答' :
          row.value + ' · ' + (row.correct ? '辨认正确' : '未辨认出');
        return '<li><span>' + label + '</span><b>' + result + '</b></li>';
      }).join('') + '</ol></details>';
  }

  function unfinished() {
    return plates.map((_, i) => i).filter(i => !answers[i] || answers[i].skipped);
  }

  function results(focus = true) {
    page = 'result'; pauseClock();
    const r = report(), n = r.numbers, a = r.animals;
    const detail = n.valid ? (n.risk === 'LOW' ? '' : '共辨认出 ' + n.correct + ' / ' + n.total + ' 张数字图。可在光线均匀时复测，或到眼科做规范检查。') :
      !r.example?.correct ? '练习图尚未正确辨认，本次只展示作答记录。' : '完成全部数字图后，可以查看数字题提示。';
    const numericDetail = n.valid ? '辨认正确 / 共 ' + n.total + ' 张' : '已作答 ' + n.answered + ' / ' + n.total + ' 张';
    const animalDetail = a.answered ? '已作答 ' + a.answered + ' / ' + a.total + ' 张' : '尚无动物题作答';
    let groups = '';
    if (n.valid) {
      const diff = n.protanErrors - n.deutanErrors;
      const direction = n.errors >= 2 ? '<p>' + (Math.abs(diff) < 2 ? '两组错题接近，本次未能区分倾向。' :
        (diff > 0 ? '红色觉组' : '绿色觉组') + '的错题较多。') + '</p>' : '';
      groups = '<details class="cg-breakdown"><summary>数字图分组</summary><ul><li>红色觉组<b>' + n.protanErrors +
        ' / 4 张未辨认</b></li><li>绿色觉组<b>' + n.deutanErrors + ' / 4 张未辨认</b></li></ul>' + direction + '</details>';
    }
    const outstanding = unfinished();
    recordStatus.update(member,runTime,r,n.answered + a.answered > 0);
    const controls = (outstanding.length ? button('continue', outstanding.every(i => answers[i]?.skipped) ? '补做跳过的图' : '继续作答', 'primary') : '') +
      button('done',recordStatus.finishLabel,outstanding.length ? 'secondary' : 'primary') + button('again','重新测试','text');
    draw('<header><h3 class="cg-title" tabindex="-1">' + r.answer + '</h3>' +
      (detail ? '<p class="cg-intro">' + detail + '</p>' : '') + '</header>' +
      '<div class="cg-stats">' + stat('number','数字辨认', n.correct + ' 张答对',numericDetail) +
      stat('animal','动物辨形',a.correct + ' 张答对',animalDetail) + '</div>' +
      recordStatus.markup(button('save','重试保存','secondary')) + groups + records(r) + '<div class="cg-actions">' + controls + '</div>', null, focus);
  }

  function reset() {
    clearTimeout(pending); pending = 0; index = 0; runTime = Date.now();
    choices = plates.map(plate => choiceOrder(plate.choices));
    answers = []; revisit = []; started = null; spent = 0; recordStatus.reset();
    prep();
  }

  function respond(value, skip, buttonNode) {
    if (pending || page !== 'plate') return;
    const plate = plates[index];
    if (skip ? plate.kind !== 'animal' : !choices[index].includes(value)) return;
    pauseClock(); recordStatus.reset();
    answers[index] = { value: skip ? null : value, skipped: skip, correct: skip ? null : value === plate.answer, rtMs: Math.round(spent) };
    buttonNode.setAttribute('aria-pressed', 'true');
    body.querySelectorAll('.cg-page button').forEach(node => { node.disabled = true; });
    pending = setTimeout(() => {
      pending = 0;
      if (revisit.length) {
        const next = revisit.indexOf(index) + 1;
        if (next >= revisit.length) { revisit = []; results(); }
        else showPlate(revisit[next]);
      } else if (index + 1 < plates.length) showPlate(index + 1);
      else results();
    }, 145);
  }

  function mount(id) { if (!page) { member = id; reset(); } }
  function leave() {
    clearTimeout(pending); pending = 0; started = null;
    page = ''; answers = []; revisit = []; choices = []; member = null;
  }
  function back() {
    if (pending) return true;
    if (page !== 'plate') return false;
    if (revisit.length) {
      const pos = revisit.indexOf(index);
      if (pos > 0) showPlate(revisit[pos - 1]);
      else { revisit = []; results(); }
    } else if (index > 0) showPlate(index - 1);
    else prep();
    return true;
  }
  body.addEventListener('click', event => {
    const b = event.target.closest('[data-color]');
    if (!b || !page || b.disabled || pending) return;
    switch (b.dataset.color) {
      case 'begin': showPlate(0); break;
      case 'answer': respond(b.dataset.value, false, b); break;
      case 'skip': respond(null, true, b); break;
      case 'previous': back(); break;
      case 'finish': revisit = []; results(); break;
      case 'continue': revisit = unfinished(); if (revisit.length) showPlate(revisit[0]); break;
      case 'again':
        if (onRestart) { leave(); onRestart(); }
        else reset();
        break;
      case 'save': results(); break;
      case 'done': leave(); onDone(); break;
    }
  });
  document.addEventListener('visibilitychange', () => {
    if (page !== 'plate' || pending) return;
    if (document.hidden) pauseClock();
    else if (started === null) started = performance.now();
  });
  return { mount, back, leave };
};
