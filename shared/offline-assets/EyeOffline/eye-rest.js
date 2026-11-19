/* The phone and watch show the same silent, thirty-second flight. */
window.EyeRest = (() => {
  'use strict';
  const takeoff = 2400, lap = 12000, turn = 1200, duration = 30000;
  const turnStart = takeoff + lap;
  const returnLapStart = turnStart + turn;
  const landingStart = returnLapStart + lap;
  const angularSpeed = 2 * Math.PI / lap;
  // This radius gives the U-turn the same tangential velocity as the two laps.
  const turnRadius = angularSpeed * turn / Math.PI;
  const changes = new EventTarget();
  const views = new Map();
  const labels = {
    ready: ['开始', '30 秒'], running: ['暂停', '进行中'],
    paused: ['继续', '已暂停'], finished: ['再来一次', '已完成']
  };
  let status = 'ready', elapsed = 0, startedAt = 0, frame = 0;
  let lastSecond = -1;

  function smoothstep(t) {
    return t * t * (3 - 2 * t);
  }
  function translate(x, y) {
    return 'translate(calc(50cqw + var(--orbit-x) * '+x.toFixed(6)+
      '), calc(50cqh + var(--orbit-y) * '+y.toFixed(6)+')) translate(-50%, -50%)';
  }
  function position(ms) {
    if (ms < takeoff) {
      const t = ms / takeoff;
      return translate(t * (t - 2), .68 * (1 - smoothstep(t)) + angularSpeed * takeoff * t * t * (1 - t));
    }
    if (ms < turnStart) {
      const angle = angularSpeed * (ms - takeoff);
      return translate(-Math.cos(angle), -Math.sin(angle));
    }
    if (ms < returnLapStart) {
      const angle = Math.PI * (ms - turnStart) / turn;
      return translate(-1 + turnRadius * (1 - Math.cos(angle)), -turnRadius * Math.sin(angle));
    }
    if (ms < landingStart) {
      const t = (ms - returnLapStart) / lap;
      const angle = 2 * Math.PI * t;
      // Rejoin the full-width orbit gradually after leaving the inside of the turn.
      const inset = 2 * turnRadius * (1 - smoothstep(Math.min(1, 2 * t)));
      return translate(-Math.cos(angle) + inset, Math.sin(angle));
    }
    // Leave the final lap tangentially and settle above the stream at the bottom of the clearing.
    const landingTime = duration - landingStart;
    const t = (ms - landingStart) / landingTime;
    const blend = smoothstep(t);
    return translate(1.25 * blend - 1, .8 * blend + angularSpeed * landingTime * t * (1 - t) ** 2);
  }
  function draw() {
    const transform = position(elapsed);
    for (const view of views.values()) view.fly.style.transform = transform;
  }
  function refresh(force = false) {
    const seconds = Math.ceil((duration - elapsed) / 1000);
    if (!force && seconds === lastSecond) return;
    lastSecond = seconds;
    for (const {root, timer, toggleButton, endButton} of views.values()) {
      root.dataset.state = status;
      timer.textContent = status === 'finished' ? '已完成' : '0:' + String(seconds).padStart(2, '0');
      toggleButton.textContent = labels[status][0];
      endButton.hidden = status === 'ready';
      endButton.textContent = status === 'finished' ? '完成' : '结束';
    }
  }
  function setState(next) {
    status = next;
    refresh(true);
    changes.dispatchEvent(new Event('exercise'));
  }
  function update() {
    elapsed = Math.min(duration, Math.max(0, performance.now() - startedAt));
    draw();
    if (elapsed === duration) setState('finished');
    else refresh();
  }
  function tick() {
    update();
    if (status === 'running') frame = requestAnimationFrame(tick);
  }
  function toggle() {
    if (status === 'running') {
      cancelAnimationFrame(frame);
      update();
      if (status === 'running') setState('paused');
      return;
    }
    if (status !== 'paused') elapsed = 0;
    startedAt = performance.now() - elapsed;
    draw();
    setState('running');
    frame = requestAnimationFrame(tick);
  }
  function end() {
    cancelAnimationFrame(frame);
    elapsed = 0;
    draw();
    if (status !== 'ready') setState('ready');
  }
  function mount(host, close) {
    if (views.has(host)) return;
    host.innerHTML = '<section class="rest-session"><div class="rest-heading"><p class="rest-instruction">目光跟着萤火虫转一转。</p>'+
      '<output class="rest-time" role="timer" aria-live="off"></output></div>'+
      '<div class="rest-field">'+window.FireflyArt.stage('rest')+'</div>'+
      '<div class="rest-controls"><button type="button" data-rest-toggle>开始</button><button type="button" data-rest-end hidden>结束</button></div></section>';
    const root = host.firstElementChild;
    const toggleButton = root.querySelector('[data-rest-toggle]'), endButton = root.querySelector('[data-rest-end]');
    toggleButton.addEventListener('click', toggle);
    endButton.addEventListener('click', () => { end(); close(); });
    views.set(host, {root, timer:root.querySelector('output'), fly:root.querySelector('.ff-flight'), toggleButton, endButton});
    draw();
    refresh(true);
  }
  function leave(host) {
    if (!views.delete(host)) return;
    host.replaceChildren();
    if (!views.size) end();
  }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && status === 'running') toggle();
  });
  return {mount, leave, toggle, changes, get status() { return status; },
    get summary() { return labels[status][1]; }, duration};
})();
