/* Illustrated stationery for the garden post office. The game overlays all readable numbers. */
window.PostArt = (() => {
  let serial = 0;

  function svg(viewBox, content, fit = 'xMidYMid meet') {
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" width="100%" height="100%"
      preserveAspectRatio="${fit}" aria-hidden="true" focusable="false">${content}</svg>`;
  }

  const stampColors = [
    ['#527360', '#c6d5a1'], ['#a35942', '#f5ce8e'],
    ['#496574', '#f5ba83'], ['#69674e', '#dfcca0']
  ];
  const stampMotifs = [
    `<path d="M30 52q9-17 3-30" fill="none" stroke="#eff0c4" stroke-width="2.4"/>
      <path d="M33 36Q19 36 16 22q14-2 17 14Z" fill="#bfd79c"/>
      <path d="M34 28q0-15 16-17-1 14-16 17Z" fill="#e0e9b4"/>
      <path d="m20 25 12 10m5-10 8-9" fill="none" stroke="#7e9b6e" stroke-width="1.3"/>
      <path d="M20 46q6-3 11 0m5-1 6 1" fill="none" stroke="#cdd49e" stroke-width="1.2"/>`,
    window.LilyArt.stampSprig(),
    `<g stroke-linejoin="round"><path d="M32 35C12 32 13 13 18 15q13 2 15 18 2-18 16-20 4 15-12 23 15-5 12 4-7 11-15-3-9 14-16 6-7-9 14-8Z" fill="#efb781"/>
      <path d="M20 20q7 5 10 12m8-1q4-8 9-12m-27 21 7-3m12-1 5 3" fill="none" stroke="#b98268" stroke-width="1.5"/>
      <path d="m31 36 4-11m-1 2-3-6m4 5 4-5" fill="none" stroke="#ecdbad" stroke-width="1.5" stroke-linecap="round"/></g>`,
    `<path d="M12 47q16-3 39-2" fill="none" stroke="#d7c59c" stroke-width="2" stroke-linecap="round"/>
      <path d="M14 33q9 2 14-5c2-5 5-10 12-10 7 0 10 5 9 10l6 2-8 3c-4 9-11 13-21 8l-10 1 5-5Z" fill="#e6d8b0"/>
      <path d="M22 36q6-6 17-6-6 10-16 8Z" fill="#ad9c78"/>
      <path d="m30 40-1 5m7-5-1 5" stroke="#e6d8b0" stroke-width="1.4"/><circle cx="44.3" cy="25" r="1.4" fill="#545b48"/>`
  ];

  // Each repeat takes one little semicircular bite out of the paper edge.
  const stampEdge = 'M4 3h2' + 'h2a1.75 1.75 0 0 0 3.5 0h1'.repeat(8) +
    'h2v3' + 'v2a1.75 1.75 0 0 0 0 3.5v1'.repeat(8) +
    'v2h-2' + 'h-2a1.75 1.75 0 0 0-3.5 0h-1'.repeat(8) +
    'h-2v-2' + 'v-2a1.75 1.75 0 0 0 0-3.5v-1'.repeat(8) + 'Z';

  function stampFace(index) {
    const i = Math.abs(Math.trunc(index) || 0) % stampMotifs.length;
    const [field, ink] = stampColors[i];
    return `<path d="${stampEdge}" fill="#f3e1b9"/>
      <rect x="9" y="8.5" width="45" height="46.5" rx=".6" fill="${field}"/>
      <rect x="11.4" y="10.9" width="40.2" height="41.7" fill="none" stroke="${ink}" stroke-width=".75" opacity=".74"/>
      <g fill="${ink}" opacity=".7"><circle cx="15.5" cy="15" r=".9"/><circle cx="47.6" cy="15" r=".9"/>
        <path d="M15 48h3m27 0h3" fill="none" stroke="${ink}" stroke-width="1"/></g>${stampMotifs[i]}`;
  }

  function stamp(index = 0) {
    return svg('0 0 64 64', stampFace(index));
  }

  function mailbox(index = 0) {
    const id = 'po-box-' + ++serial;
    const lid = ['#73907b','#7b8d78','#728b7a'][Math.abs(Math.trunc(index) || 0) % 3];
    return svg('0 0 120 92', `<defs>
      <linearGradient id="${id}-body" x1="0" x2=".4" y2="1"><stop stop-color="#576e5f"/><stop offset="1" stop-color="#304a40"/></linearGradient>
      <linearGradient id="${id}-lid" x2=".3" y2="1"><stop stop-color="${lid}"/><stop offset="1" stop-color="#50695a"/></linearGradient>
      <linearGradient id="${id}-plate" x2="1" y2="1"><stop stop-color="#354e42"/><stop offset="1" stop-color="#294137"/></linearGradient>
    </defs>
    <path d="M7 80q0 7 9 8h88q8-1 8-7v-4H7Z" fill="#202f29"/>
    <path d="M7 78V30C7 15 15 6 28 6h63c14 0 22 9 22 24v49q0 5-6 5H13q-6 0-6-6Z" fill="url(#${id}-body)" stroke="#84927b" stroke-width="1"/>
    <path d="M7 31C7 15 14 6 28 6h63q20 0 22 25v8H7Z" fill="url(#${id}-lid)"/>
    <path d="M13 22q3-12 17-12h57M9 38h101" fill="none" stroke="#c1c6a7" stroke-width=".9" opacity=".59" stroke-linecap="round"/>
    <path d="M20 21.5q0-2 2-2h76q2 0 2 2v4.8H20Z" fill="#d4b38a"/>
    <rect x="22" y="22" width="76" height="5.2" rx="2.2" fill="#1e3029"/><path d="M22 28h76" stroke="#c5bb93" opacity=".74" stroke-width=".7"/>
    <rect x="13.5" y="42" width="93" height="35.8" rx="3" fill="url(#${id}-plate)" stroke="#8f947a" stroke-width=".85"/>
    <g fill="#c5b38c"><circle cx="18" cy="47" r="1.05"/><circle cx="102" cy="47" r="1.05"/>
      <circle cx="18" cy="73" r="1.05"/><circle cx="102" cy="73" r="1.05"/></g>
    <path d="M13 81h94" stroke="#8d9a82" stroke-width=".8" opacity=".5"/>`);
  }

  return { mailbox, stamp };
})();
