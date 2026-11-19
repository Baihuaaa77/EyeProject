// 单次会话只接收页面标识及语音偏好，不接收成员标识或测量记录。
const host = document.querySelector('#session');
const params = new URLSearchParams(location.hash.slice(1));
const close = () => { location.href = 'eye-session://close'; };
const save = () => false;
const demo = {mode: 'demo'};

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
