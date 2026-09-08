/* Sample data + chart renderer shared by all five drafts. */

/* One day of 10-minute samples. Value = minutes the screen was on in that slot. */
var SAMPLE_MIN = 10;
var DAY_LABEL = '9월 7일 (월)';

var WEEK = [
  { d: '9/2', w: '수', ok: true },
  { d: '9/3', w: '목', ok: true },
  { d: '9/4', w: '금', ok: false },
  { d: '9/5', w: '토', ok: false },
  { d: '9/6', w: '일', ok: true },
  { d: '9/7', w: '월', ok: true },
  { d: '9/8', w: '화', ok: true }
];
var WEEK_OK = WEEK.filter(function (x) { return x.ok; }).length;
var STREAK = 3;

function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    var t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

var USAGE = (function () {
  var rnd = mulberry32(20260907);
  var profile = [.80, .70, 0, 0, 0, 0, 0, .10, .40, .30, .25, .35,
                 .45, .30, .25, .35, .30, .45, .40, .55, .65, .75, .85, .80];
  var out = [];
  for (var i = 0; i < 144; i++) {
    var h = (i / 6) | 0;
    var v = profile[h] * 10 * (0.5 + rnd());
    out.push(Math.max(0, Math.min(10, Math.round(v))));
  }
  return out;
})();

var TOTAL_MIN = USAGE.reduce(function (a, b) { return a + b; }, 0);

/* "02:00" -> 2.0 */
function toHour(v) {
  var p = String(v).split(':');
  return (+p[0]) + (+p[1]) / 60;
}

function fmtDuration(min) {
  var h = Math.floor(min / 60), m = Math.round(min % 60);
  return h ? h + '시간 ' + m + '분' : m + '분';
}

/* Curfew may wrap past midnight, so it can produce two bands. */
function curfewBands(s, e) {
  return s <= e ? [[s, e]] : [[0, e], [s, 24]];
}

function curfewMinutes(s, e) {
  var bands = curfewBands(s, e), sum = 0;
  USAGE.forEach(function (v, i) {
    var h = i / 6;
    for (var b = 0; b < bands.length; b++) {
      if (h >= bands[b][0] && h < bands[b][1]) { sum += v; return; }
    }
  });
  return sum;
}

/* Draws into the given <svg>. Colors and widths come from each draft's
   own CSS via the .cx-* classes, so the renderer stays style-free. */
function drawChart(svg, mode, s, e) {
  var W = 360, H = 170, L = 22, R = 356, T = 6, B = 140;
  var n = USAGE.length, step = (R - L) / n;
  var NS = 'http://www.w3.org/2000/svg';
  function y(v) { return B - (v / 10) * (B - T); }
  function el(tag, at) {
    var x = document.createElementNS(NS, tag);
    for (var k in at) x.setAttribute(k, at[k]);
    return x;
  }
  svg.setAttribute('viewBox', '0 0 ' + W + ' ' + (H + 22));
  while (svg.firstChild) svg.removeChild(svg.firstChild);

  curfewBands(s, e).forEach(function (b) {
    if (b[1] <= b[0]) return;
    svg.appendChild(el('rect', {
      'class': 'cx-band', x: L + b[0] * 6 * step, y: T,
      width: (b[1] - b[0]) * 6 * step, height: B - T
    }));
  });

  [0, 5, 10].forEach(function (v) {
    svg.appendChild(el('line', { 'class': 'cx-grid', x1: L, x2: R, y1: y(v), y2: y(v) }));
    var t = el('text', { 'class': 'cx-label cx-label-y', x: L - 6, y: y(v) + 3.5 });
    t.textContent = v;
    svg.appendChild(t);
  });

  if (mode === 'bar') {
    USAGE.forEach(function (v, i) {
      if (v <= 0) return;
      svg.appendChild(el('rect', {
        'class': 'cx-bar', x: L + i * step + step * 0.15, y: y(v),
        width: step * 0.7, height: B - y(v)
      }));
    });
  } else {
    var pts = USAGE.map(function (v, i) {
      return (L + i * step + step / 2).toFixed(2) + ',' + y(v).toFixed(2);
    });
    svg.appendChild(el('path', {
      'class': 'cx-area',
      d: 'M' + (L + step / 2).toFixed(2) + ',' + B + ' L' + pts.join(' L') +
         ' L' + (R - step / 2).toFixed(2) + ',' + B + ' Z'
    }));
    svg.appendChild(el('path', { 'class': 'cx-line', d: 'M' + pts.join(' L') }));
  }

  [0, 6, 12, 18, 24].forEach(function (h) {
    var t = el('text', { 'class': 'cx-label cx-label-x', x: L + h * 6 * step, y: H + 12 });
    t.setAttribute('text-anchor', h === 0 ? 'start' : h === 24 ? 'end' : 'middle');
    t.textContent = (h === 24 ? '24' : ('0' + h).slice(-2)) + ':00';
    svg.appendChild(t);
  });
}

/* 브라우저 로케일이 시각 입력에 붙이는 "오전/오후" 표기는 끌 수 없다. 그래서 네이티브
   입력은 투명하게 덮어 두어 선택기와 키보드 조작을 그대로 살리고, 보이는 숫자는 각
   초안이 정한 서체로 24시간 표기해 다시 그린다. */
(function () {
  var st = document.createElement('style');
  st.textContent =
    '.tskin{position:relative; display:inline-flex; align-items:baseline}' +
    '.tskin > input[type=time]{position:absolute; left:0; top:0; width:100%; height:100%;' +
    ' opacity:0; border:0; margin:0; padding:0; font:inherit; cursor:pointer}' +
    '.tskin > b{pointer-events:none; font:inherit; color:inherit; white-space:nowrap}';
  document.head.appendChild(st);
})();

function skinTime(input) {
  var wrap = document.createElement('span');
  wrap.className = 'tskin';
  input.parentNode.insertBefore(wrap, input);
  wrap.appendChild(input);
  var label = document.createElement('b');
  wrap.appendChild(label);
  function sync() { label.textContent = input.value || '--:--'; }
  input.addEventListener('change', sync);
  sync();
}

/* Wires the two <input type="time"> fields and the 선/막대 toggle for a draft. */
function wireDraft(opts) {
  var svg = document.querySelector(opts.svg);
  var from = document.querySelector(opts.from);
  var to = document.querySelector(opts.to);
  var mode = 'line';
  skinTime(from);
  skinTime(to);
  function render() {
    var s = toHour(from.value), e = toHour(to.value);
    drawChart(svg, mode, s, e);
    if (opts.onRender) opts.onRender(curfewMinutes(s, e), s, e);
  }
  document.querySelectorAll(opts.toggle).forEach(function (btn) {
    btn.addEventListener('click', function () {
      mode = btn.dataset.mode;
      document.querySelectorAll(opts.toggle).forEach(function (b) {
        b.classList.toggle('is-on', b === btn);
        b.setAttribute('aria-pressed', String(b === btn));
      });
      render();
    });
  });
  from.addEventListener('change', render);
  to.addEventListener('change', render);
  render();
}
