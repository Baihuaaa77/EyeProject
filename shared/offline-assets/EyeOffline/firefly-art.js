/* The luminous point sits at the center of the insect's coordinate system. */
window.FireflyArt = (() => {
  const scenes = new Set(['prepare', 'practice', 'test', 'result', 'cover', 'far', 'rest']);

  function insect() {
    return `<svg class="ff-insect" viewBox="0 0 96 96" aria-hidden="true" focusable="false">
      <g transform="rotate(-29 48 48)">
        <g fill="#b6c4a3" stroke="#e2d5a9" stroke-width="1.3" stroke-linejoin="round">
          <path d="M47 39C32 40 22 33 22 21 34 18 46 27 48 37Z"/>
          <path d="M49 39C49 24 57 13 67 11 72 23 64 37 52 41Z"/>
        </g>
        <g fill="none" stroke="#758775" stroke-width="1.2" stroke-linecap="round">
          <path d="M46 37 27 25m23 12 13-19"/>
        </g>
        <path d="M41 35c-2-7 1-13 6-14 7-2 12 4 11 10l-5 11Z" fill="#34443a" stroke="#e4c897" stroke-width="1.4"/>
        <path d="M47 24q-1-7-5-9m10 8q4-7 9-7" fill="none" stroke="#d7c092" stroke-width="1.6" stroke-linecap="round"/>
        <circle cx="52.5" cy="27" r="1.6" fill="#ece5be"/>
        <ellipse cx="48" cy="42" rx="9" ry="13.5" fill="#6c4a2b" stroke="#e7a659" stroke-width="1.5"/>
        <path d="M40 38q7 4 15 .5" fill="none" stroke="#d7a562" stroke-width="2"/>
      </g>
      <circle cx="48" cy="48" r="9.7" fill="#dca052" stroke="#76502e" stroke-width="1.25"/>
      <circle cx="48" cy="48" r="6.8" fill="#ffe997"/>
      <circle cx="48" cy="48" r="2.9" fill="#fffdee"/>
    </svg>`;
  }

  function stage(kind = 'test') {
    const scene = scenes.has(kind) ? kind : 'test';
    const fly = `<div class="ff-flight" aria-hidden="true"><i class="ff-tail-glow"></i>${insect()}</div>`;
    return `<div class="ff-stage" data-scene="${scene}">${scene === 'rest' ? fly : `<div class="ff-clearing">${fly}</div>`}</div>`;
  }

  return {stage};
})();
