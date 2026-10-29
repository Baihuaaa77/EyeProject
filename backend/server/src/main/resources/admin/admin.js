// 管理网页：口令登录后看统计与去标识记录。令牌只放 sessionStorage（关掉标签页即失效），
// 接口一律用相对地址（页面在 …/admin/ 下），部署在反向代理子路径下也能用。所有数据用 textContent 写入，不拼 HTML。
'use strict';

const KINDS = { DRY_EYE: '干眼', PRESBYOPIA: '老视', STRABISMUS: '眼位', COLOR_VISION: '色觉' };
const RISKS = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险', UNCERTAIN: '不确定', UNKNOWN: '无法判断' };
const QUALITY = { SUFFICIENT: '有效', INSUFFICIENT: '不足' };
const RELATIONS = { SELF: '本人', CHILD: '孩子', PARENT: '父母', SPOUSE: '配偶', OTHER: '其他' };
const PAGE = 50;
const TOKEN_KEY = 'eye-admin-token';

const $ = (id) => document.getElementById(id);
const el = (tag, props = {}, children = []) => {
  const node = Object.assign(document.createElement(tag), props);
  for (const child of [].concat(children)) node.append(child);
  return node;
};
const riskColor = (risk) => `var(--${(risk || 'unknown').toLowerCase()})`;
const label = (map, key) => map[key] || key || '—';
const fmtTime = (ms) => new Date(ms).toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

let token = null;
try { token = sessionStorage.getItem(TOKEN_KEY); } catch (_) { /* 隐私模式等读不到时每次重新登录 */ }
let offset = 0;
let total = 0;

function saveToken(value) {
  token = value;
  try { value ? sessionStorage.setItem(TOKEN_KEY, value) : sessionStorage.removeItem(TOKEN_KEY); } catch (_) { /* 同上 */ }
}

async function api(path, options = {}) {
  const response = await fetch('api/' + path, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: 'Bearer ' + token } : {}) },
  });
  if (response.status === 401 && path !== 'login') { saveToken(null); showLogin('登录已过期，请重新登录'); throw new Error('unauthorized'); }
  if (!response.ok) {
    let message = '请求失败（' + response.status + '）';
    try { message = (await response.json()).message || message; } catch (_) { /* 非 JSON 错误体 */ }
    throw new Error(message);
  }
  return response;
}

function showLogin(message = '') {
  $('app').hidden = true; $('logout').hidden = true; $('login').hidden = false;
  $('login-error').textContent = message;
  $('password').value = ''; $('password').focus();
}

async function showApp() {
  $('login').hidden = true; $('app').hidden = false; $('logout').hidden = false;
  await Promise.all([loadOverview(), loadRecords()]);
}

// 总览

async function loadOverview() {
  const data = await (await api('overview')).json();
  const tiles = [
    [data.accounts, '注册账号', '含未同意研究用途的账号'],
    [data.researchAccounts, '同意研究用途', '以下数据只含这些账号'],
    [data.subjects, '被测者', '本人与家庭成员'],
    [data.records, '测试记录', '已上传到云端'],
  ];
  $('tiles').replaceChildren(...tiles.map(([value, name, note]) => el('div', { className: 'tile' }, [
    el('div', { className: 'value', textContent: value.toLocaleString('zh-CN') }),
    el('div', { className: 'label', textContent: name }),
    el('div', { className: 'note', textContent: note }),
  ])));

  const kinds = data.byKind.length ? data.byKind.map((k) => {
    const segments = Object.keys(RISKS).filter((r) => k.byRisk[r]).map((r) =>
      el('span', { title: `${RISKS[r]} ${k.byRisk[r]} 次`, style: `width:${(k.byRisk[r] / k.total) * 100}%;background:${riskColor(r)}` }));
    const summary = Object.keys(RISKS).filter((r) => k.byRisk[r]).map((r) => `${RISKS[r]} ${k.byRisk[r]}`).join(' · ');
    return el('div', { className: 'kind' }, [
      el('div', { className: 'kind-head' }, [el('b', { textContent: `${label(KINDS, k.kind)}　${k.total} 次` }), el('span', { className: 'muted', textContent: summary })]),
      el('div', { className: 'stack' }, segments),
    ]);
  }) : [el('p', { className: 'empty', textContent: '还没有同意研究用途的测试记录' })];
  $('kinds').replaceChildren(...kinds);
  $('legend').replaceChildren(...Object.entries(RISKS).map(([r, name]) => el('span', {}, [el('i', { style: `background:${riskColor(r)}` }), name])));

  const max = Math.max(1, ...data.daily.map((d) => d.count));
  $('daily').replaceChildren(...data.daily.map((d) => el('span', {
    className: d.count ? '' : 'zero', title: `${d.date}：${d.count} 次`, style: `height:${d.count ? Math.max(4, (d.count / max) * 100) : 2}%`,
  })));
  $('daily-start').textContent = data.daily[0]?.date || '';
  $('daily-end').textContent = data.daily[data.daily.length - 1]?.date || '';
  const platforms = Object.entries(data.byPlatform);
  $('platforms').replaceChildren(...(platforms.length ? platforms.map(([p, n]) => el('span', { className: 'chip', textContent: `${p} ${n}` }))
    : [el('span', { className: 'muted', textContent: '暂无' })]));
  $('generated').textContent = ' 统计时间：' + fmtTime(data.generatedAt) + '。';
}

// 记录列表

function filterQuery() {
  const params = new URLSearchParams();
  if ($('f-kind').value) params.set('kind', $('f-kind').value);
  if ($('f-risk').value) params.set('risk', $('f-risk').value);
  // 日期按浏览器本地时区的整天：结束日期含当天
  if ($('f-from').value) params.set('from', new Date($('f-from').value + 'T00:00:00').getTime());
  if ($('f-to').value) params.set('to', new Date($('f-to').value + 'T00:00:00').getTime() + 86400000);
  return params;
}

async function loadRecords() {
  const params = filterQuery();
  params.set('offset', offset); params.set('limit', PAGE);
  const page = await (await api('records?' + params)).json();
  total = page.total;
  const rows = page.rows.flatMap((r) => {
    const toggle = el('button', { type: 'button', className: 'link', textContent: '指标' });
    const row = el('tr', {}, [
      el('td', { textContent: fmtTime(r.testedAt) }),
      el('td', { textContent: label(KINDS, r.kind) }),
      el('td', {}, el('span', { className: 'pill' }, [el('i', { style: `background:${riskColor(r.riskLevel)}` }), label(RISKS, r.riskLevel)])),
      el('td', { textContent: label(QUALITY, r.quality) }),
      el('td', { textContent: r.user }),
      el('td', { textContent: `${r.subject}（${label(RELATIONS, r.relation)}）` }),
      el('td', { textContent: r.ageBand || '—' }),
      el('td', { textContent: r.sex || '—' }),
      el('td', { textContent: [r.platform, r.appVersion].filter(Boolean).join(' ') || '—' }),
      el('td', {}, toggle),
    ]);
    const features = Object.entries(r.features);
    const detail = el('tr', { className: 'detail', hidden: true }, el('td', { colSpan: 10 }, [
      el('div', { className: 'muted', textContent: '评估版本：' + (r.assessmentVersion || '—') }),
      el('div', { className: 'features' }, features.length
        ? features.map(([k, v]) => el('div', {}, [el('code', { textContent: k }), '　' + (v === null ? '—' : v)]))
        : [el('span', { className: 'muted', textContent: '无指标' })]),
    ]));
    toggle.addEventListener('click', () => { detail.hidden = !detail.hidden; });
    return [row, detail];
  });
  $('rows').replaceChildren(...(rows.length ? rows : [el('tr', {}, el('td', { colSpan: 10, className: 'empty', textContent: '没有符合条件的记录' }))]));
  const end = Math.min(offset + PAGE, total);
  $('count').textContent = total ? `第 ${offset + 1}–${end} 条，共 ${total} 条` : '';
  $('prev').disabled = offset === 0;
  $('next').disabled = end >= total;
}

async function exportCsv() {
  const button = $('export');
  button.disabled = true;
  try {
    const blob = await (await api('records.csv?' + filterQuery())).blob();
    const link = el('a', { href: URL.createObjectURL(blob), download: `eyeproject-records-${new Date().toISOString().slice(0, 10)}.csv` });
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  } catch (error) {
    if (error.message !== 'unauthorized') alert(error.message);
  } finally {
    button.disabled = false;
  }
}

// 事件

document.addEventListener('DOMContentLoaded', () => {
  for (const [value, name] of Object.entries(KINDS)) $('f-kind').append(el('option', { value, textContent: name }));
  for (const [value, name] of Object.entries(RISKS)) $('f-risk').append(el('option', { value, textContent: name }));

  $('login-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    $('login-error').textContent = '';
    try {
      const result = await (await api('login', { method: 'POST', body: JSON.stringify({ password: $('password').value }) })).json();
      saveToken(result.token);
      await showApp();
    } catch (error) {
      $('login-error').textContent = error.message;
    }
  });
  $('logout').addEventListener('click', async () => {
    try { await api('logout', { method: 'POST' }); } catch (_) { /* 已过期也照样退出 */ }
    saveToken(null); showLogin();
  });
  $('filters').addEventListener('submit', (event) => { event.preventDefault(); offset = 0; loadRecords().catch(() => {}); });
  $('prev').addEventListener('click', () => { offset = Math.max(0, offset - PAGE); loadRecords().catch(() => {}); });
  $('next').addEventListener('click', () => { offset += PAGE; loadRecords().catch(() => {}); });
  $('export').addEventListener('click', exportCsv);

  if (token) showApp().catch(() => {}); else showLogin();
});
