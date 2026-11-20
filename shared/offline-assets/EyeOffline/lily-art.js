/* Shared botanical drawing for the growing plant and the flower stamp. */
window.LilyArt = (() => {
  let serial = 0;

  function bell(x, y, scale, tilt, closed = false) {
    const transform = `translate(${x} ${y}) rotate(${tilt}) scale(${scale})`;
    if (closed) {
      return `<g transform="${transform}">
        <path d="M0 0C-5 1-7 8-4 13-2 17 2 17 5 13 8 8 5 1 0 0Z" fill="#edf0d0" stroke="#829066" stroke-width="1"/>
        <path d="M0 2Q-3 7-1 13" fill="none" stroke="#ffffee" stroke-width="2"/>
      </g>`;
    }
    return `<g transform="${transform}" stroke-linejoin="round"><g class="sg-petals">
      <path d="M0 0C-4.8 0-8 3.7-8.4 9.2c-.2 3.2-.3 5.7-2.1 7.7 2.8.6 4.1-.1 5.5-1.5.7 2.5 2 3.8 3.8 4.7 2-.6 3.6-2.4 4.4-4.4 1.8 2 3.7 2.6 6.9 1.6-1.7-2.3-1.9-5.2-2-8.1C8 4.2 4.6 0 0 0Z" fill="#fffef0" stroke="#879174" stroke-width="1.15"/>
      <path d="M-6.7 12.8c.5-5 .9-8.6 4.7-10.4-1.6 3.7-2 7.7-1.8 11.4l-1.3 1.3Z" fill="#e3e8d1"/>
      <path d="M4.2 3.2c2.8 2.6 2.5 7.9 3.2 11.5" fill="none" stroke="#d1d9ba" stroke-width=".95" stroke-linecap="round"/>
      <path d="M-2.6.6Q0-1.6 2.5.6" fill="none" stroke="#a6ac74" stroke-width="1"/>
    </g></g>`;
  }

  // The blade starts at the pot rim. Parallel veins and a folded side
  // distinguish the broad lily foliage even before the flower stalk appears.
  function blade(x, y, angle, length, breadth, id, dark = false) {
    return `<g transform="translate(${x} ${y}) rotate(${angle}) scale(${breadth} ${length})">
      <path d="M0 0C-7-30-28-60-15-88c4-9 8-14 10-26C-1-89 21-77 20-55 19-32 5-17 0 0Z" fill="url(#${id}-${dark ? 'shade' : 'leaf'})" stroke="#527244" stroke-width="1" stroke-linejoin="round"/>
      <path d="M0-2C1-33 7-56-4-106M-4-13c-6-26-15-45-9-69M5-18c5-17 13-36 9-54" fill="none" stroke="#e2e3a0" stroke-width="1.6" stroke-opacity=".52" stroke-linecap="round"/>
    </g>`;
  }

  function newShoot() {
    return `<g stroke-linecap="round" stroke-linejoin="round">
      <path d="M98 142q4-5 8-2l3-2 4 3 5-1 4 2" fill="none" stroke="#503724" stroke-width="1.5"/>
      <path d="M110 142c-3-7-6-12-2-16 3-3 8-1 7 3" fill="none" stroke="#587f2e" stroke-width="2.7"/>
      <path d="M109 124q-6-1-7-9 7 0 7 9Zm2 1q1-9 10-10-1 8-10 10Z" fill="#8bae3b" stroke="#54752f" stroke-width=".8"/>
      <path d="M104 141q-3-4-1-8l4 2 2-3 3 2 3-2q4 8-3 11Z" fill="#b79156" stroke="#765332" stroke-width="1"/>
      <path d="m105 134 3 3-1 3 3 2" fill="none" stroke="#f1d7a1" stroke-width="1"/>
    </g>`;
  }

  function youngStems(id) {
    return `<g transform="translate(110 143) translate(-110 -132)">
      ${blade(108,132,-54,.52,.48,id,true)}${blade(112,132,45,.57,.47,id)}
      ${blade(109,132,-19,.56,.45,id)}${blade(111,132,19,.44,.4,id,true)}
      <g fill="none" stroke="#657e30" stroke-width="1.85" stroke-linecap="round">
        <path d="M109 132q1-38-9-66M111 132q4-29 14-47"/>
      </g>
      <path d="M100 67q-6-4-5-11 5 2 5 11Zm1 3q-1-10 5-15 1 9-5 15Zm23 18q0-8 7-11-1 7-7 11Z" fill="#8cab40" stroke="#60792d" stroke-width=".8"/>
    </g>`;
  }

  function starFlower(x, y, scale, tilt) {
    const petals = [0,72,144,216,288].map(angle =>
      `<path transform="rotate(${angle})" d="M0 .5Q-4.3-2.8-.6-8.4.8-9 1.4-6.6 4.9-3 0 .5Z" fill="#5278ed" stroke="#2b4a9d" stroke-width=".7"/>`
    ).join('');
    return `<g transform="translate(${x} ${y}) rotate(${tilt}) scale(${scale})">${petals}
      <circle r="2.1" fill="#ffd25c"/><circle cx="-.45" cy="-.55" r=".7" fill="#fff0a7"/>
    </g>`;
  }

  // Petal outlines use a 490 × 645 reference frame. They let earlier stages
  // show the real leaves, stalks, calyces and flower centres from the final art.
  const cornflowerPetals = `M96 31 115 16 135 9 160 2 181 3 202 5 230 19 242 36 242 56 229 73 213 76 123 88 122 88 102 87 88 68 88 48Z
    M267 67 283 60 303 57 304 57 323 58 344 67 361 76 376 92 378 111 367 127 350 134 349 134 272 115 262 111 255 97 255 96 257 78Z
    M84 138 102 127 120 118 138 118 152 121 167 127 176 138 177 152 177 153 169 167 97 197 92 199 91 199 74 195 69 188 65 174 65 173 69 156Z
    M220 134 236 128 252 127 269 131 281 139 288 148 291 156 287 167 283 173 271 178 221 175 211 167 210 166 209 155 209 154 211 144Z
    M315 173 327 169 334 167 335 167 361 172 379 181 389 191 395 199 400 217 400 219 396 233 385 243 370 244 359 239 317 216 306 208 303 200 302 195 302 194 306 183Z`;
  const bellPetals = `M26 101 27 100 30 98 32 97 45 94 47 94 52 96 53 97 68 123 70 127 70 133 69 145 67 146 52 146 40 137 21 119 19 117 19 116 24 105Z
    M402 142 405 139 407 138 411 137 412 137 420 141 422 143 423 145 424 149 424 150 421 164 417 166 403 162 395 156 393 154 393 152Z
    M424 168 428 164 430 163 436 163 444 166 447 169 448 171 449 190 447 191 442 192 432 193 430 193 428 192 416 183 415 181Z
    M460 176 465 171 478 171 482 175 483 177 486 192 486 193 484 195 470 199 458 195 456 194 455 193 455 192 459 178Z
    M38 209 42 205 44 204 55 204 58 205 62 209 64 213 67 227 67 228 65 230 38 231 37 231 35 230 32 227 36 214 37 211Z
    M408 215 413 210 416 209 422 209 429 212 431 214 432 216 433 219 433 220 432 236 430 237 417 238 412 238 398 227 398 226Z
    M450 233 451 232 454 230 456 229 458 229 467 230 470 232 471 233 472 235 473 238 475 254 475 255 473 256 458 260 446 255 443 253 443 251 448 237Z
    M115 241 118 238 120 237 128 235 132 235 135 236 138 238 140 240 149 257 149 258 147 261 146 262 132 270 131 270 113 267 112 266 112 265 113 246 114 243Z
    M62 241 65 239 69 237 79 237 82 239 86 243 87 246 91 261 91 262 90 263 87 264 63 265 58 264 56 262 56 261 60 245Z
    M419 266 423 262 425 261 431 261 439 263 441 264 443 266 444 268 445 271 445 289 444 290 441 291 428 292 424 292 422 291 412 284 410 282 410 281 417 269Z
    M359 274 362 271 365 269 368 268 383 265 391 265 393 267 394 269 394 274 384 298 383 299 369 300 364 300 353 292 350 289 349 287Z
    M83 273 88 268 90 267 103 267 105 268 110 273 111 275 112 278 116 293 116 294 114 296 98 301 97 301 84 297 81 296 78 293 81 277Z
    M143 286 148 281 151 280 162 280 164 281 169 286 170 288 171 291 175 308 174 310 172 311 159 316 157 316 146 313 141 306 141 292 142 288Z
    M326 297 331 292 334 291 339 291 346 292 348 293 352 297 353 299 354 303 354 304 353 320 352 321 350 322 333 322 320 315 319 314 318 312 318 311Z`;
  const potFace = 'M81 141.3h4c11 3.7 39 3.7 51 0h3v49H81Z';
  const illustration = '<image href="crown-bloom.webp" x="43" y="2" width="142" height="187"/>';

  function potted(stage, pulse = false) {
    // 安卓在 SVG 内缩放枝叶，盆沿和阴影留在固定坐标，避免整张画布参与合成。
    const foliage = content => pulse
      ? `<g transform="translate(110 141.4)"><g>
          <animateTransform data-sg-feed attributeName="transform" type="scale" values="1 .97;.98 1.05;1 1"
            keyTimes="0;.4;1" calcMode="spline" keySplines="0 0 .58 1;0 0 .58 1" begin="indefinite" dur=".52s"/>
          <g transform="translate(-110 -141.4)">${content}</g></g></g>`
      : content;
    const shadow = '<ellipse cx="110" cy="188" rx="30" ry="3" fill="#203c29" opacity=".2"/>';
    if (stage === 4) return shadow + `<g class="sg-crown-bloom">${illustration}</g>`;
    const id = 'lily-' + ++serial;
    if (stage >= 2) {
      const bells = stage === 2 ? `<path d="${bellPetals}" fill="black" stroke="black" stroke-width="8"/>` : '';
      const scale = stage === 2 ? .73 : .88;
      const crownBuds = [[174,77,-8],[311,118,13],[132,175,-17],[247,165,5],[337,221,17]];
      const buds = crownBuds.map(([x,y,angle]) =>
        `<g transform="translate(${x} ${y}) rotate(${angle})">
          <path d="M0 10C-9 7-10-7-2-17 7-11 11 4 0 10Z" fill="#668521" stroke="#405d18" stroke-width="1.2"/>
          <path d="M-1 7Q-5-4-2-14q0 12 3 17" fill="none" stroke="#aabd52" stroke-width="1.5"/>
        </g>`).join('');
      const bellTips = [[37,97],[59,116],[52,205],[72,238],[97,269],[130,237],[157,282],
        [410,139],[430,165],[470,172],[419,211],[461,231],[428,263],[379,266],[362,273],[339,292]];
      const closedBells = stage === 2 ? bellTips.map(([x,y]) =>
        `<path transform="translate(${x} ${y})" d="M0-3Q-6 1-4 9 1 11 4 5 5-1 0-3Z" fill="#b2c759" stroke="#778d35" stroke-width="1"/>`).join('') : '';
      const top = `<g transform="translate(110 144) scale(1 ${scale}) translate(-110 -144)" clip-path="url(#${id}-top)">
          <g transform="translate(43 2) scale(${142/490} ${187/645})">
            <path d="M77 101Q120 160 128 221" fill="none" stroke="#7f993b" stroke-width="2"/>
            ${buds}${closedBells}</g><g mask="url(#${id}-plant)">${illustration}</g>
        </g>`;
      return `${shadow}<defs>
        <mask id="${id}-plant" maskUnits="userSpaceOnUse" x="0" y="0" width="220" height="194">
          <rect width="220" height="194" fill="white"/>
          <g transform="translate(43 2) scale(${142/490} ${187/645})" stroke-linejoin="round">
            <path d="${cornflowerPetals}" fill="black" stroke="black" stroke-width="5"/>${bells}
          </g>
        </mask>
        <clipPath id="${id}-top"><rect x="42" y="0" width="144" height="144.7"/></clipPath>
        <clipPath id="${id}-rim"><path d="${potFace}"/></clipPath>
        </defs>
        ${foliage(top)}<g clip-path="url(#${id}-rim)">${illustration}</g>`;
    }
    const defs = `<defs>
      <linearGradient id="${id}-leaf" x1="0" y1="0" x2=".85" y2="1"><stop stop-color="#cae15b"/><stop offset=".45" stop-color="#90af30"/><stop offset="1" stop-color="#3e681c"/></linearGradient>
      <linearGradient id="${id}-shade" x2=".85" y2="1"><stop stop-color="#92ab39"/><stop offset="1" stop-color="#375c1e"/></linearGradient>
      <clipPath id="${id}-rim"><path d="${potFace}"/></clipPath>
    </defs>`;
    const soil = `<ellipse cx="110.4" cy="141.4" rx="25.2" ry="3.9" fill="#f5e7c9" stroke="#c9ac80" stroke-width=".65"/>
      <ellipse cx="110.4" cy="141.45" rx="22.4" ry="2.7" fill="#705437"/>`;
    return defs + shadow + soil + foliage(stage === 0 ? newShoot() : youngStems(id)) +
      `<g clip-path="url(#${id}-rim)">${illustration}</g>`;
  }

  function stampSprig() {
    return `<g fill="none" stroke="#c0d38d" stroke-linecap="round">
      <path d="M31 49q-1-16-3-27m3 27q3-13 10-24m-10 24Q22 22 18 26q-2 1-2 4m15 19q10-22 15-17m-22 5q-3-5-5-3" stroke-width="1.35"/>
      </g><path d="M31 49Q19 46 16 39q11 2 15 10Zm0 0q4-11 16-13-5 8-16 13Z" fill="#c5d987"/>
      <g fill="#7898ff" stroke="#304fa2" stroke-width=".6" stroke-linejoin="round">
        <path d="M27.6 24q-4-1-6-7l4 2-1-5 3 3 1-5 2 5 3-3-1 5 3-2q-2 6-8 7Z"/>
        <path d="M40 27q-4-1-5-5l3 1-1-3 3 1 1-4 1 4 3-2-1 4 3-1q-2 4-7 5Z"/>
      </g>${bell(16,29,.32,8)}${bell(19,34,.28,8)}${bell(46,32,.31,-8)}
      ${starFlower(31,34,.53,-8)}${starFlower(37,40,.38,16)}`;
  }

  return Object.freeze({potted, stampSprig});
})();
