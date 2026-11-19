// A result is written when it is first shown, and again only when the user adds observations.
window.createTestRecordStatus = function (save) {
  let state = 'empty';
  const messages = {
    empty: '本次没有可保存的记录',
    guest: '访客体验，本次记录仅在此页显示',
    saved: '已保存到测试记录',
    failed: '本次记录尚未保存'
  };
  return {
    reset() { state = 'empty'; },
    update(member, time, report, hasData) {
      if (!hasData) state = 'empty';
      else if (!member) state = 'guest';
      else if (state !== 'saved') {
        try { state = save(member, time, report) === false ? 'failed' : 'saved'; }
        catch { state = 'failed'; }
      }
    },
    markup(retryButton) {
      return '<div class="test-record-status" role="status" data-record-state="' + state + '"><span>' +
        messages[state] + '</span>' + (state === 'failed' ? retryButton : '') + '</div>';
    },
    get finishLabel() { return state === 'failed' ? '不保存并结束' : '完成'; }
  };
};
