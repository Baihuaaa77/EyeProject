// 单次会话只接收页面标识及语音偏好，不接收成员标识或测量记录。
const host = document.querySelector('#session');
const params = new URLSearchParams(location.hash.slice(1));
const close = () => { location.href = 'eye-session://close'; };
const save = () => false;
const demo = {mode: 'demo'};

const playScenes = {
  sprout: {selector: '.sg-field-wrap', aspect: 1402 / 1122},
  firefly: {
    selector: '.ff-stage[data-scene="practice"], .ff-stage[data-scene="test"], .ff-stage[data-scene="cover"], .ff-stage[data-scene="far"]',
    aspect: 1536 / 1024
  },
  post: {selector: '.pg-desk[data-post-desk="practice"], .pg-desk[data-post-desk="test"]', aspect: 1402 / 1122}
};

function fitOfflinePlayScene(container, resetScroll = false) {
  if (container !== host) return;
  if (resetScroll) window.scrollTo(0, 0);
  const layout = playScenes[host.dataset.view];
  if (!layout) return;
  const scene = host.querySelector(layout.selector);
  if (!scene) return;
  // 旧 WebView 可能在切换游戏阶段后仍用初始视口匹配 CSS，按实际可见区域分配高度。
  const {innerHeight, innerWidth} = window;
  const {paddingTop, paddingBottom} = getComputedStyle(host);
  host.firstElementChild.style.minHeight = Math.max(0, innerHeight - parseFloat(paddingTop) - parseFloat(paddingBottom)) + 'px';
  // 竖屏至少保留背景原始比例，内容过长时由页面自然滚动。
  scene.style.minHeight = innerHeight > innerWidth ? Math.ceil(scene.clientWidth * layout.aspect) + 'px' : '';
}

window.addEventListener('resize', () => {
  if (host.dataset.view !== 'sprout') fitOfflinePlayScene(host);
});

function openGame(game) {
  const restart = () => { game.leave(); game.mount(null, demo); };
  restart();
  return game;
}

const pages = {
  garden: () => {
    const game = createSproutGame(host, close, save, () => game.mount(null, demo));
    return openGame(game);
  },
  firefly: () => {
    const game = createFireflyGame(host, close, save, {}, () => game.mount(null, demo));
    return openGame(game);
  },
  color: () => openGame(createColorGame(host, close, save)),
  post: () => openGame(createPostGame(host, close, save)),
  rest: () => { EyeRest.mount(host, close); return null; },
  booking: () => {
    for (let i = 1; i <= 9; i++) {
      const audio = document.createElement('audio');
      audio.dataset.bookingPrompt = i;
      audio.preload = 'none';
      audio.src = 'booking-audio/' + String(i).padStart(2, '0') + '.wav';
      document.body.append(audio);
    }
    const game = createBooking(host, {
      escape: value => String(value ?? '').replace(/[&<>"']/g, char =>
        ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char])),
      done: close,
      preferences: {voiceAssist: params.get('voice') === '1', sound: true, speechVolume: 65}
    });
    game.mount();
    return game;
  }
};

const page = params.get('page');
if (Object.prototype.hasOwnProperty.call(pages, page)) {
  host.dataset.view = page === 'garden' ? 'sprout' : page;
  const session = pages[page]();
  window.addEventListener('pagehide', () => session?.leave());
}
