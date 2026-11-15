/* 카톡 대화 분석기 - 화면 */
(function () {
  'use strict';
  var K = KakaoCore;
  var $ = function (s) { return document.querySelector(s); };
  var state = { parsed: null, a: null, b: null, sample: true, dir: 0 };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function num(n) { return Math.round(n || 0).toLocaleString('ko-KR'); }
  function pct(x) { return Math.round((x || 0) * 100) + '%'; }
  function d(ts) { var t = new Date(ts); return t.getFullYear() + '. ' + (t.getMonth() + 1) + '. ' + t.getDate() + '.'; }
  function dk(key) { var p = key.split('-'); return p[0] + '. ' + (+p[1]) + '. ' + (+p[2]) + '.'; }
  function who(name, cls) { return '<span class="who"><span class="dot ' + cls + '"></span>' + esc(name) + '</span>'; }
  function J(word, a, b) { return esc(K.josa(word, a, b)); }

  /* ---------- 불러오기 ---------- */
  function setStatus(msg, err) { var s = $('#status'); s.textContent = msg || ''; s.className = 'status' + (err ? ' err' : ''); }

  function load(text, isSample, label) {
    var parsed = K.parse(text);
    if (parsed.participants.length < 2) {
      setStatus(parsed.messages.length ? '두 사람 이상의 대화가 필요해요. 1:1 채팅방 대화를 올려 주세요.' :
        '대화 내용을 찾지 못했어요. 카카오톡 "대화 내보내기"로 저장한 txt 파일인지 확인해 주세요.', true);
      return;
    }
    state.parsed = parsed;
    state.sample = isSample;
    state.a = parsed.participants[0].name;
    state.b = parsed.participants[1].name;
    state.dir = 0;
    if (!isSample) setStatus(label + ' · ' + num(parsed.messages.length) + '개 메시지 · ' + parsed.format + ' 형식으로 읽었어요.');
    render();
  }

  async function readFile(file) {
    setStatus('읽는 중이에요…');
    try {
      var buf = await file.arrayBuffer();
      var text = await K.readChatBytes(new Uint8Array(buf));
      load(text, false, file.name);
    } catch (e) {
      setStatus(e && e.message ? e.message : '파일을 읽지 못했어요.', true);
    }
  }

  /* ---------- 그리기 ---------- */
  function render() {
    var p = state.parsed;
    var r;
    try { r = K.analyze(p, state.a, state.b); } catch (e) { setStatus(e.message, true); return; }
    var A = r.people[0], B = r.people[1];
    var html = [];

    if (state.sample) html.push('<div class="banner">지금 보이는 건 가짜 커플 "민준·서연"의 예시 대화예요. 위에서 내 대화 파일을 올리면 바로 바뀌어요.</div>');

    if (p.participants.length > 2) {
      var opts = function (sel) {
        return p.participants.map(function (x) { return '<option' + (x.name === sel ? ' selected' : '') + '>' + esc(x.name) + '</option>'; }).join('');
      };
      html.push('<div class="pick card"><span>단체방이에요. 분석할 두 사람을 골라 주세요:</span>' +
        '<label>첫째 <select id="selA">' + opts(state.a) + '</select></label>' +
        '<label>둘째 <select id="selB">' + opts(state.b) + '</select></label></div>');
    }

    html.push(secSummary(r, A, B));
    html.push(secApology(r, A, B));
    html.push(secPatterns(r, A, B));
    html.push(secTopics(r, A, B));
    html.push(secTypes(r));
    html.push(secFights(r, A, B));
    html.push(secCourt(r, A, B));
    html.push(secRhythm(r, A, B));
    html.push(secFun(r, A, B));

    $('#report').innerHTML = html.join('');
    bindReport();
  }

  function secSummary(r, A, B) {
    var perDay = r.total / Math.max(1, r.spanDays);
    return '<section class="sec" id="sum"><header><span class="eyebrow">한눈에 보기</span>' +
      '<div class="couple">' + who(A.name, 'a') + '<span class="heart" aria-hidden="true">♥</span>' + who(B.name, 'b') + '</div>' +
      '<p class="muted">' + d(r.first) + ' ~ ' + d(r.last) + ' · ' + num(r.spanDays) + '일 중 ' + num(r.daysTalked) + '일 대화했어요</p></header>' +
      '<div class="grid4">' +
      tile('주고받은 메시지', num(r.total), '개') +
      tile('대화 횟수', num(r.sessions), '번') +
      tile('하루 평균', num(perDay), '마디') +
      tile('연속 대화 기록', num(r.streak.len), '일') +
      '</div>' +
      '<div class="card"><h3>누가 더 많이 말했을까</h3><div style="height:10px"></div>' +
      '<div class="split" role="img" aria-label="' + esc(A.name) + ' ' + pct(A.share) + ', ' + esc(B.name) + ' ' + pct(B.share) + '">' +
      '<span class="a" style="width:' + (A.share * 100) + '%" data-tip="' + esc(A.name) + ': ' + num(A.msgs) + '개 (' + pct(A.share) + ')"></span>' +
      '<span class="b" style="width:' + (B.share * 100) + '%" data-tip="' + esc(B.name) + ': ' + num(B.msgs) + '개 (' + pct(B.share) + ')"></span></div>' +
      '<div class="splitlab"><span>' + who(A.name, 'a') + ' ' + num(A.msgs) + '개 · ' + pct(A.share) + '</span><span>' + who(B.name, 'b') + ' ' + num(B.msgs) + '개 · ' + pct(B.share) + '</span></div></div>' +
      '</section>';
  }
  function tile(k, v, unit) { return '<div class="card tile"><span class="k">' + k + '</span><span class="v">' + v + '<small>' + unit + '</small></span></div>'; }

  function secApology(r, A, B) {
    var verdict;
    if (!A.apology && !B.apology) verdict = '둘 다 사과한 적이 없어요. 평화로운 커플이네요.';
    else {
      var hi = A.apology >= B.apology ? A : B, lo = hi === A ? B : A;
      if (hi.apology === lo.apology) verdict = '둘이 똑같이 ' + num(hi.apology) + '번씩 사과했어요.';
      else if (!lo.apology) verdict = J(hi.name, '이', '가') + ' 혼자 ' + num(hi.apology) + '번 사과했어요. ' + J(lo.name, '은', '는') + ' 한 번도 안 했어요.';
      else {
        var ratio = hi.apology / lo.apology;
        verdict = J(hi.name, '이', '가') + ' ' + esc(lo.name) + '보다 ' + (ratio >= 1.95 ? ratio.toFixed(1) + '배' : num(hi.apology - lo.apology) + '번 더') + ' 많이 사과했어요.';
      }
    }
    function types(P, cls) {
      if (!P.apologyTypes.length) return '<p class="muted small">사과한 말이 없어요.</p>';
      var max = P.apologyTypes[0].count;
      return '<div class="hbars">' + P.apologyTypes.map(function (t) {
        return '<div class="hbar" data-tip="' + esc(P.name) + ' · ' + esc(t.key) + ': ' + t.count + '번"><span class="lab">' + esc(t.key) + '</span><span class="track"><span class="fill ' + cls + '" style="width:' + (t.count / max * 100) + '%;display:block"></span></span><span class="val">' + t.count + '번</span></div>';
      }).join('') + '</div>';
    }
    function quotes(P, cls) {
      if (!P.apologySamples.length) return '';
      return '<div class="bubbles">' + P.apologySamples.map(function (s) {
        return '<div class="bub ' + cls + '"><span class="by">' + esc(P.name) + ' · ' + d(s.ts) + '</span>' + esc(s.text) + '</div>';
      }).join('') + '</div>';
    }
    var maxAp = Math.max(1, Math.max.apply(null, r.monthly.map(function (m) { return Math.max(m.apA, m.apB); })));
    var cols = '<div class="cols" role="img" aria-label="달마다 사과한 횟수">' + r.monthly.map(function (m) {
      return '<div class="m" data-tip="' + esc(m.month.replace('-', '년 ')) + '월\n' + esc(A.name) + ' ' + m.apA + '번 · ' + esc(B.name) + ' ' + m.apB + '번">' +
        '<span class="a" style="height:' + (m.apA / maxAp * 100) + '%"></span><span class="b" style="height:' + (m.apB / maxAp * 100) + '%"></span></div>';
    }).join('') + '</div><div class="colx">' + r.monthly.map(function (m) { return '<span>' + (+m.month.slice(5)) + '월</span>'; }).join('') + '</div>';

    return '<section class="sec" id="apology"><header><span class="eyebrow">사과 리포트</span><h2>누가 더 많이 사과했을까</h2>' +
      '<p class="muted small">"미안", "죄송", "잘못했어", "화 풀어", "쏘리" 같은 말이 들어간 메시지를 셌어요.</p></header>' +
      '<div class="card" style="display:flex;flex-direction:column;gap:14px">' +
      '<div class="vs"><div class="side">' + who(A.name, 'a') + '<span class="big a">' + num(A.apology) + '</span><span class="muted small">1,000마디당 ' + A.apologyPer1000.toFixed(1) + '번</span></div>' +
      '<span class="mid">VS</span>' +
      '<div class="side right">' + who(B.name, 'b') + '<span class="big b">' + num(B.apology) + '</span><span class="muted small">1,000마디당 ' + B.apologyPer1000.toFixed(1) + '번</span></div></div>' +
      '<p class="verdict">' + verdict + '</p>' +
      (r.fights.length ? '<p class="muted small">다툰 대화 ' + r.fights.length + '번 중 먼저 사과한 사람: ' + esc(A.name) + ' ' + A.apologyFirst + '번, ' + esc(B.name) + ' ' + B.apologyFirst + '번</p>' : '') +
      '</div>' +
      '<div class="grid2"><div class="card"><h3>' + esc(A.name) + '의 사과 스타일</h3><div style="height:10px"></div>' + types(A, 'a') + '</div>' +
      '<div class="card"><h3>' + esc(B.name) + '의 사과 스타일</h3><div style="height:10px"></div>' + types(B, 'b') + '</div></div>' +
      '<div class="card"><h3>달마다 사과한 횟수</h3><div class="legend" style="margin:6px 0 10px">' + who(A.name, 'a') + who(B.name, 'b') + '</div>' + cols + '</div>' +
      ((A.apologySamples.length || B.apologySamples.length) ? '<div class="card"><h3>최근 사과 메시지</h3><div style="height:10px"></div><div class="grid2">' + '<div>' + quotes(A, 'a') + '</div><div>' + quotes(B, 'b') + '</div></div></div>' : '') +
      '</section>';
  }

  function secPatterns(r, A, B) {
    var names = [A.name, B.name];
    var list = r.patterns.filter(function (p) { return p.from === names[state.dir]; });
    var fromCls = state.dir === 0 ? 'a' : 'b', toCls = state.dir === 0 ? 'b' : 'a';
    var cards = list.map(function (p) {
      var chips = p.labels.map(function (l) {
        return '<span class="chip' + (l.id === p.key ? ' hot' : '') + '">' + esc(l.name) + ' ' + pct(l.share) + '</span>';
      }).join('');
      var lift = '';
      if (p.key && p.keyRate !== null && p.baseKeyRate !== null && p.keyRate >= 0.1 && p.keyRate >= p.baseKeyRate * 1.5) lift = '<span class="chip hot">평소보다 ' + (p.baseKeyRate > 0.01 && p.keyRate / p.baseKeyRate < 5 ? (p.keyRate / p.baseKeyRate).toFixed(1) + '배' : '훨씬') + ' 자주</span>';
      var speed = '';
      if (p.delay !== null && p.baseDelay) {
        if (p.delay <= p.baseDelay * 0.6) speed = '<span class="chip">답장 평소보다 빠름</span>';
        else if (p.delay >= p.baseDelay * 1.6) speed = '<span class="chip">답장 평소보다 느림</span>';
      }
      var ex = p.example ? '<div class="bubbles"><div class="bub ' + fromCls + '"><span class="by">' + esc(p.from) + '</span>' + esc(p.example.from) + '</div>' +
        '<div class="bub ' + toCls + '"><span class="by">' + esc(p.to) + '</span>' + esc(p.example.to) + '</div></div>' : '';
      return '<div class="card pcard"><div class="trig"><span>' + esc(p.when) + '</span><span class="num">' + num(p.n) + '번</span></div>' +
        '<p class="sent">' + esc(p.sentence) + '</p>' + ex +
        '<div class="chips">' + lift + speed + chips + '</div>' +
        '<p class="muted small">평소 ' + J(p.to, '이', '가') + ' 답하는 데 ' + K.fmtDuration(p.baseDelay) + (p.key && p.baseKeyRate !== null ? ' · 평소 이런 반응 ' + pct(p.baseKeyRate) : '') + '</p></div>';
    }).join('');
    if (!cards) cards = '<p class="muted">패턴을 찾을 만큼 대화가 많지 않아요.</p>';
    return '<section class="sec" id="patterns"><header><span class="eyebrow">반응 패턴</span><h2>누가 뭘 하면, 상대는 이렇게 반응했어요</h2>' +
      '<p class="muted small">한 사람이 말한 뒤 12시간 안에 상대가 처음 보낸 답(최대 5마디)을 봤어요. 색칠된 칩이 그 상황에서 눈여겨볼 반응이에요.</p></header>' +
      '<div class="tabs" role="tablist">' +
      '<button class="tab" role="tab" data-dir="0" aria-selected="' + (state.dir === 0) + '">' + '<span class="dot a"></span><span>' + J(A.name, '이', '가') + ' 했을 때</span></button>' +
      '<button class="tab" role="tab" data-dir="1" aria-selected="' + (state.dir === 1) + '">' + '<span class="dot b"></span><span>' + J(B.name, '이', '가') + ' 했을 때</span></button></div>' +
      '<div class="pgrid">' + cards + '</div></section>';
  }

  function secTopics(r, A, B) {
    var max = Math.max.apply(null, r.topics.map(function (t) { return Math.max(t.aShare, t.bShare); }).concat([0.0001]));
    var rows = r.topics.map(function (t) {
      return '<div class="row" data-tip="' + esc(t.name) + '\n' + esc(A.name) + ': ' + num(t.a) + '번 (' + (t.aShare * 100).toFixed(1) + '%)\n' + esc(B.name) + ': ' + num(t.b) + '번 (' + (t.bShare * 100).toFixed(1) + '%)">' +
        '<div class="l"><span class="v">' + (t.aShare * 100).toFixed(1) + '%</span><span class="bar" style="width:' + (t.aShare / max * 80) + '%"></span></div>' +
        '<div class="name">' + esc(t.name) + '</div>' +
        '<div class="r"><span class="bar" style="width:' + (t.bShare / max * 80) + '%"></span><span class="v">' + (t.bShare * 100).toFixed(1) + '%</span></div></div>';
    }).join('');
    var top = r.topics[0];
    return '<section class="sec" id="topics"><header><span class="eyebrow">대화 주제</span><h2>어떤 얘기를 가장 많이 했을까</h2>' +
      '<p class="muted small">' + (top ? '가장 많이 나온 주제는 <b>' + esc(top.name) + '</b>예요. ' : '') + '막대는 각자 보낸 메시지 중 그 주제가 나온 비율이에요.</p></header>' +
      '<div class="card fly"><div class="row head"><div class="l">' + who(A.name, 'a') + '</div><div></div><div class="r">' + who(B.name, 'b') + '</div></div>' + rows + '</div></section>';
  }

  function secTypes(r) {
    var max = r.sessionTypes.length ? r.sessionTypes[0].count : 1;
    var rows = r.sessionTypes.map(function (t) {
      return '<div class="type"><div class="top2"><h3>' + esc(t.name) + '</h3><span class="num muted small">' + num(t.count) + '번 · ' + pct(t.count / r.sessions) + '</span></div>' +
        '<div class="hbar" style="grid-template-columns:1fr" data-tip="' + esc(t.name) + ': ' + num(t.count) + '번"><span class="track"><span class="fill" style="display:block;width:' + (t.count / max * 100) + '%"></span></span></div>' +
        '<p class="muted small">' + esc(t.desc) + '</p>' +
        (t.example ? '<p class="ex">가장 길었던 날 ' + d(t.example.ts) + ' (' + num(t.example.len) + '마디): "' + esc(t.example.text) + '"</p>' : '') + '</div>';
    }).join('');
    return '<section class="sec" id="types"><header><span class="eyebrow">대화 유형</span><h2>우리는 주로 어떤 대화를 할까</h2>' +
      '<p class="muted small">1시간 넘게 쉬면 새 대화로 나눠서, 대화 ' + num(r.sessions) + '번을 하나씩 분류했어요.</p></header>' +
      '<div class="card types">' + rows + '</div></section>';
  }

  function secFights(r, A, B) {
    if (!r.fights.length) return '<section class="sec" id="fights"><header><span class="eyebrow">다툼 기록</span><h2>다툰 날</h2></header><div class="card"><p>다툼으로 보이는 대화가 없어요.</p></div></section>';
    var list = r.fights.slice().sort(function (a, b) { return b.intensity * Math.sqrt(b.len) - a.intensity * Math.sqrt(a.len); }).slice(0, 8)
      .sort(function (a, b) { return b.ts - a.ts; });
    var cls = function (n) { return n === A.name ? 'a' : 'b'; };
    var rows = list.map(function (f) {
      var meta = [];
      if (f.upsetBy) meta.push('먼저 서운해한 사람 ' + who(f.upsetBy, cls(f.upsetBy)));
      meta.push(f.apologyBy ? '먼저 사과한 사람 ' + who(f.apologyBy, cls(f.apologyBy)) : '사과 없음');
      meta.push(num(f.len) + '마디 · ' + K.fmtDuration(f.duration));
      if (f.makeupAfter !== null) meta.push('화해 신호(웃음·애정)까지 ' + K.fmtDuration(f.makeupAfter));
      return '<div class="fight card"><span class="d">' + d(f.ts) + '</span><div style="display:flex;flex-direction:column;gap:4px;min-width:0"><p class="q">"' + esc(f.quote) + '"</p><div class="meta">' + meta.map(function (m) { return '<span>' + m + '</span>'; }).join('') + '</div></div></div>';
    }).join('');
    var upA = A.upsetFirst, upB = B.upsetFirst;
    return '<section class="sec" id="fights"><header><span class="eyebrow">다툼 기록</span><h2>다툰 날 ' + r.fights.length + '번</h2>' +
      '<p class="muted small">서운함·짜증 표현이 여러 번 오간 대화예요. 먼저 서운해한 횟수: ' + esc(A.name) + ' ' + upA + '번, ' + esc(B.name) + ' ' + upB + '번. 가장 진했던 ' + list.length + '번을 보여 드려요.</p></header>' +
      '<div class="fights">' + rows + '</div></section>';
  }

  function secCourt(r, A, B) {
    var c = r.court;
    var cls = function (n) { return n === A.name ? 'a' : 'b'; };
    var bub = function (q) { return '<div class="bub ' + cls(q.who) + '"><span class="by">' + esc(q.who) + '</span>' + esc(q.text) + '</div>'; };
    var head = '<header><span class="eyebrow">대화 재판</span><h2>그래서 누가 잘못했을까</h2>' +
      '<p class="muted small">다툰 대화에서 "맨날", "항상" 같은 단정, 서운하다는 말에 해명부터 하기, 무응답, 단답을 잘못으로 보고, 먼저 공감하기, 먼저 사과하기, 먼저 화해하기를 잘한 점으로 봤어요. 재미로 보는 판결이에요.</p></header>';
    if (!c.caseCount && !c.misreadCount) return '<section class="sec" id="court">' + head + '<div class="card"><p>재판할 다툼이 없어요. 사이좋은 커플이네요.</p></div></section>';
    var pa = c.people[0], pb = c.people[1], tot = pa.score + pb.score;
    var bar = tot ? '<div class="split" role="img" aria-label="과실 비율"><span class="a" style="width:' + (pa.score / tot * 100) + '%" data-tip="' + esc(pa.name) + ' 과실 점수 ' + pa.score + '"></span><span class="b" style="width:' + (pb.score / tot * 100) + '%" data-tip="' + esc(pb.name) + ' 과실 점수 ' + pb.score + '"></span></div>' +
      '<div class="splitlab"><span>' + who(pa.name, 'a') + ' 과실 ' + Math.round(pa.score / tot * 100) + '%</span><span>' + who(pb.name, 'b') + ' 과실 ' + Math.round(pb.score / tot * 100) + '%</span></div>' : '';
    var finalCard = '<div class="card gavel"><span class="eyebrow">최종 판결 · 사건 ' + c.caseCount + '건</span><p class="final">' + esc(c.overall) + '</p>' + bar +
      '<p class="muted small">각자 진 사건: ' + esc(pa.name) + ' ' + pa.lost + '건, ' + esc(pb.name) + ' ' + pb.lost + '건</p></div>';

    function person(p, k) {
      var bad = p.charges.filter(function (x) { return x.count; }).map(function (x) { return '<span class="tag bad">' + esc(x.name) + ' ' + x.count + '번</span>'; }).join('');
      var good = p.merits.filter(function (x) { return x.count; }).map(function (x) { return '<span class="tag good">' + esc(x.name) + ' ' + x.count + '번</span>'; }).join('');
      return '<div class="card" style="display:flex;flex-direction:column;gap:10px">' + who(p.name, k) +
        '<p class="style">' + esc(p.style) + '</p>' +
        (p.ackRate !== null ? '<p class="small muted">상대가 서운하다고 할 때 먼저 받아준 비율 <b>' + pct(p.ackRate) + '</b> (' + p.feelingsHeard + '번 중)</p>' : '') +
        (p.misread ? '<p class="small muted">상대 말을 오해한 것 같은 순간 <b>' + p.misread + '번</b></p>' : '') +
        (bad ? '<div class="chips">' + bad + '</div>' : '') + (good ? '<div class="chips">' + good + '</div>' : '') + '</div>';
    }

    var cases = c.cases.map(function (cs) {
      var lines = cs.items.map(function (it) {
        return '<div class="count"><div class="line"><span class="tag ' + (it.merit ? 'good' : 'bad') + '">' + (it.merit ? '정상 참작 · ' : '') + esc(it.name) + (it.count > 1 ? ' ×' + it.count : '') + '</span>' + who(it.who, cls(it.who)) + '<span class="muted small">' + esc(it.desc) + '</span></div>' +
          (it.quotes.length ? '<div class="bubbles">' + it.quotes.map(bub).join('') + '</div>' : '') + '</div>';
      }).join('');
      return '<div class="card case"><div class="head"><span class="d num"><b>' + d(cs.ts) + '</b> 사건</span><span class="muted small">' + num(cs.len) + '마디</span></div>' +
        '<p class="ruling">' + (cs.guilty ? '<span class="dot ' + cls(cs.guilty) + '" style="display:inline-block;margin-right:6px"></span>' : '') + esc(cs.verdict) + '</p>' + lines + '</div>';
    }).join('');

    var mis = c.misreads.map(function (m) {
      return '<div class="card case"><div class="head"><span><b>' + d(m.ts) + '</b></span></div>' +
        '<p>' + J(m.misreadBy, '이', '가') + ' ' + esc(m.speaker) + '의 말을 오해한 것 같아요. ' + (m.realized ? J(m.misreadBy, '이', '가') + ' 나중에 "그런 뜻이었구나" 하고 알아들었어요.' : J(m.speaker, '이', '가') + ' 바로 "그런 뜻 아니야" 하고 해명했거든요.') + '</p>' +
        '<div class="bubbles">' + (m.original ? bub(m.original) : '') + bub(m.reaction) + bub(m.clarify) + '</div></div>';
    }).join('');

    return '<section class="sec" id="court">' + head + finalCard +
      '<div class="grid2">' + person(pa, 'a') + person(pb, 'b') + '</div>' +
      (cases ? '<h3>사건별 판결문 (잘못이 많았던 순)</h3><div class="fights">' + cases + '</div>' : '') +
      (mis ? '<h3>이런 부분은 오해한 것 같아요 (' + c.misreadCount + '번)</h3><div class="pgrid">' + mis + '</div>' : '') +
      '</section>';
  }

  function lineChart(r, A, B) {
    var data = r.monthly, n = data.length;
    if (n < 2) return '<p class="muted small">한 달치 대화라 달별 그래프는 건너뛰었어요.</p>';
    var W = 640, H = 200, L = 40, R = 12, T = 12, Bm = 28;
    var max = Math.max.apply(null, data.map(function (m) { return Math.max(m[A.name], m[B.name]); }));
    var step = Math.pow(10, Math.floor(Math.log10(Math.max(1, max))));
    var nice = Math.ceil(max / step) * step; if (nice / step > 5 && step >= 10) { step *= 2; nice = Math.ceil(max / step) * step; }
    var ticks = []; for (var v = 0; v <= nice; v += nice / 4) ticks.push(v);
    var x = function (i) { return L + (W - L - R) * (n === 1 ? 0.5 : i / (n - 1)); };
    var y = function (v) { return T + (H - T - Bm) * (1 - v / nice); };
    var path = function (k) { return data.map(function (m, i) { return (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(m[k]).toFixed(1); }).join(' '); };
    var g = ticks.map(function (t) { return '<line class="grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + y(t) + '" y2="' + y(t) + '"></line><text x="' + (L - 6) + '" y="' + (y(t) + 4) + '" text-anchor="end">' + num(t) + '</text>'; }).join('');
    var every = Math.ceil(n / 8);
    var xl = data.map(function (m, i) { return i % every && i !== n - 1 ? '' : '<text x="' + x(i) + '" y="' + (H - 8) + '" text-anchor="middle">' + (m.month.slice(5) === '01' || i === 0 ? m.month.slice(2, 4) + '년 ' : '') + (+m.month.slice(5)) + '월</text>'; }).join('');
    var bw = (W - L - R) / Math.max(1, n - 1);
    var hits = data.map(function (m, i) {
      return '<rect class="hit" x="' + (x(i) - bw / 2) + '" y="' + T + '" width="' + bw + '" height="' + (H - T - Bm) + '" data-tip="' + m.month.replace('-', '년 ') + '월\n' + esc(A.name) + ' ' + num(m[A.name]) + '개\n' + esc(B.name) + ' ' + num(m[B.name]) + '개"></rect>';
    }).join('');
    var endA = '<circle class="pa" cx="' + x(n - 1) + '" cy="' + y(data[n - 1][A.name]) + '" r="4"></circle>';
    var endB = '<circle class="pb" cx="' + x(n - 1) + '" cy="' + y(data[n - 1][B.name]) + '" r="4"></circle>';
    return '<svg class="chart" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="달마다 보낸 메시지 수">' + g + xl + hits +
      '<path class="la" d="' + path(A.name) + '"></path><path class="lb" d="' + path(B.name) + '"></path>' + endA + endB + '</svg>';
  }

  function secRhythm(r, A, B) {
    var days = ['일', '월', '화', '수', '목', '금', '토'];
    var hmax = 1;
    r.heat.forEach(function (row) { row.forEach(function (v) { if (v > hmax) hmax = v; }); });
    var heat = '<div class="heat" role="img" aria-label="요일과 시간대별 메시지 수"><span></span>';
    for (var h = 0; h < 24; h++) heat += '<span class="h">' + (h % 3 === 0 ? h : '') + '</span>';
    [1, 2, 3, 4, 5, 6, 0].forEach(function (di) {
      heat += '<span class="d">' + days[di] + '</span>';
      for (var hh = 0; hh < 24; hh++) {
        var v = r.heat[di][hh], a = v ? 12 + Math.round(v / hmax * 88) : 0;
        heat += '<span class="c" style="' + (v ? 'background:color-mix(in srgb, var(--accent) ' + a + '%, var(--sunken))' : '') + '" data-tip="' + days[di] + '요일 ' + hh + '시: ' + num(v) + '개"></span>';
      }
    });
    heat += '</div>';
    var faster = A.replyMedian !== null && B.replyMedian !== null ? (A.replyMedian <= B.replyMedian ? A : B) : null;
    var replyLine = faster ? J(faster.name, '이', '가') + ' 답장이 더 빨라요.' : '';
    function rowP(label, va, vb) { return '<div class="fact card"><span class="k">' + label + '</span><div class="pair"><div>' + who(A.name, 'a') + '<b>' + va + '</b></div><div>' + who(B.name, 'b') + '<b>' + vb + '</b></div></div></div>'; }
    return '<section class="sec" id="rhythm"><header><span class="eyebrow">답장과 리듬</span><h2>누가 더 빨리, 더 먼저 연락할까</h2><p class="muted small">' + replyLine + '</p></header>' +
      '<div class="facts">' +
      rowP('답장 걸리는 시간 (중간값)', K.fmtDuration(A.replyMedian), K.fmtDuration(B.replyMedian)) +
      rowP('5분 안에 답한 비율', pct(A.fastShare), pct(B.fastShare)) +
      rowP('먼저 말 건 횟수 (선톡)', num(A.starts) + '번', num(B.starts) + '번') +
      rowP('대화를 마무리한 횟수', num(A.ends) + '번', num(B.ends) + '번') +
      rowP('질문에 3시간 넘게 답 안 함', num(A.ghosted) + '번', num(B.ghosted) + '번') +
      rowP('한 번에 몰아 보내는 메시지', A.avgBurst.toFixed(1) + '개', B.avgBurst.toFixed(1) + '개') +
      '</div>' +
      '<div class="card"><h3>달마다 보낸 메시지</h3><div class="legend" style="margin:6px 0 4px">' + who(A.name, 'a') + who(B.name, 'b') + '</div>' + lineChart(r, A, B) + '</div>' +
      '<div class="card"><h3>언제 가장 많이 얘기할까</h3><p class="muted small" style="margin:2px 0 10px">진할수록 메시지가 많은 시간이에요. ' + J(A.name, '은', '는') + ' ' + A.peakHour + '시, ' + J(B.name, '은', '는') + ' ' + B.peakHour + '시에 가장 활발해요.</p><div class="heat-wrap">' + heat + '</div></div>' +
      '</section>';
  }

  function secFun(r, A, B) {
    function pair(label, va, vb) { return '<div class="fact card"><span class="k">' + label + '</span><div class="pair"><div>' + who(A.name, 'a') + '<b>' + va + '</b></div><div>' + who(B.name, 'b') + '<b>' + vb + '</b></div></div></div>'; }
    function one(label, v, sub) { return '<div class="fact card"><span class="k">' + label + '</span><span class="one">' + v + '</span>' + (sub ? '<span class="muted small">' + sub + '</span>' : '') + '</div>'; }
    function words(P, cls) {
      if (!P.words.length) return '<p class="muted small">-</p>';
      return '<div class="words ' + cls + '">' + P.words.map(function (w) { return '<span class="w">' + esc(w.key) + '<i>' + w.count + '</i></span>'; }).join('') + '</div>';
    }
    function emo(P) { return P.emojis.length ? P.emojis.map(function (e) { return esc(e.key) + ' ' + e.count; }).join('  ') : '-'; }
    function nick(P) { return P.nick.length ? esc(P.nick[0].key) + ' (' + P.nick[0].count + ')' : '-'; }
    var calls = A.calls + B.calls, callSec = A.callSec + B.callSec;
    var longest = [A, B].filter(function (p) { return p.longest; }).sort(function (x, y) { return y.longest.len - x.longest.len; })[0];
    return '<section class="sec" id="fun"><header><span class="eyebrow">재미로 보는 통계</span><h2>우리 대화의 소소한 기록</h2></header>' +
      '<div class="facts">' +
      pair('"사랑해" 말한 횟수', num(A.love) + '번', num(B.love) + '번') +
      pair('애정 표현 (100마디당)', A.affPer100.toFixed(1) + '번', B.affPer100.toFixed(1) + '번') +
      pair('ㅋ·ㅎ 친 개수', num(A.laughChars) + '개', num(B.laughChars) + '개') +
      pair('ㅠㅠ 들어간 메시지', num(A.cryMsgs) + '개', num(B.cryMsgs) + '개') +
      pair('보낸 사진', num(A.photos) + '장', num(B.photos) + '장') +
      pair('보낸 이모티콘', num(A.emoticons) + '개', num(B.emoticons) + '개') +
      pair('지운 메시지', num(A.deleted) + '개', num(B.deleted) + '개') +
      pair('평균 메시지 길이', A.avgLen.toFixed(1) + '자', B.avgLen.toFixed(1) + '자') +
      pair('새벽 (0~5시) 메시지', pct(A.dawnShare), pct(B.dawnShare)) +
      pair('자주 부른 애칭', nick(A), nick(B)) +
      pair('잘 자 인사', num(A.night) + '번', num(B.night) + '번') +
      pair('많이 쓴 이모지', emo(A), emo(B)) +
      (r.busiest ? one('가장 많이 대화한 날', dk(r.busiest.day), num(r.busiest.count) + '마디') : '') +
      one('가장 오래 연락 없던 기간', K.fmtDuration(r.silence.ms), r.silence.from ? d(r.silence.from) + ' ~ ' + d(r.silence.to) : '') +
      (r.streak.len ? one('연속 대화 최장 기록', num(r.streak.len) + '일', dk(r.streak.from) + ' ~ ' + dk(r.streak.to)) : '') +
      (calls ? one('통화', num(calls) + '번', '통화 시간 합계 ' + K.fmtDuration(callSec * 1000)) : '') +
      '</div>' +
      '<div class="grid2"><div class="card"><h3>' + J(A.name, '이', '가') + ' 많이 쓴 말</h3><div style="height:8px"></div>' + words(A, 'a') + '</div>' +
      '<div class="card"><h3>' + J(B.name, '이', '가') + ' 많이 쓴 말</h3><div style="height:8px"></div>' + words(B, 'b') + '</div></div>' +
      (longest ? '<div class="card"><h3>가장 긴 메시지 (' + num(longest.longest.len) + '자, ' + esc(longest.name) + ')</h3><div style="height:8px"></div><div class="bubbles"><div class="bub ' + (longest === A ? 'a' : 'b') + '"><span class="by">' + d(longest.longest.ts) + '</span>' + esc(longest.longest.text.length > 400 ? longest.longest.text.slice(0, 399) + '…' : longest.longest.text) + '</div></div></div>' : '') +
      '</section>';
  }

  /* ---------- 이벤트 ---------- */
  function bindReport() {
    Array.prototype.forEach.call(document.querySelectorAll('.tab[data-dir]'), function (b) {
      b.addEventListener('click', function () {
        state.dir = +b.getAttribute('data-dir');
        var y = window.scrollY;
        render();
        window.scrollTo(0, y);
        var t = document.querySelector('.tab[data-dir="' + state.dir + '"]'); if (t) t.focus();
      });
    });
    var sa = $('#selA'), sb = $('#selB');
    if (sa && sb) {
      var ch = function () {
        if (sa.value === sb.value) { setStatus('서로 다른 두 사람을 골라 주세요.', true); return; }
        state.a = sa.value; state.b = sb.value; render();
      };
      sa.addEventListener('change', ch); sb.addEventListener('change', ch);
    }
  }

  var tip = document.createElement('div');
  tip.id = 'tip'; tip.hidden = true; document.body.appendChild(tip);
  function showTip(el, x, y) {
    tip.textContent = el.getAttribute('data-tip');
    tip.hidden = false;
    var w = tip.offsetWidth, h = tip.offsetHeight;
    var left = Math.min(window.innerWidth - w - 8, Math.max(8, x + 12));
    var top = y - h - 12; if (top < 8) top = y + 16;
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  }
  document.addEventListener('pointermove', function (e) {
    var el = e.target.closest && e.target.closest('[data-tip]');
    if (el && e.pointerType === 'mouse') showTip(el, e.clientX, e.clientY);
    else if (e.pointerType === 'mouse') tip.hidden = true;
  });
  document.addEventListener('pointerdown', function (e) {
    var el = e.target.closest && e.target.closest('[data-tip]');
    if (el && e.pointerType !== 'mouse') showTip(el, e.clientX, e.clientY); else if (e.pointerType !== 'mouse') tip.hidden = true;
  });
  window.addEventListener('scroll', function () { tip.hidden = true; }, { passive: true });

  var input = $('#file');
  input.addEventListener('change', function () { if (input.files[0]) readFile(input.files[0]); input.value = ''; });
  $('#pickBtn').addEventListener('click', function () { input.click(); });
  $('#sampleBtn').addEventListener('click', function () { setStatus(''); load(K.makeSample(), true); });
  var drop = $('#drop');
  ['dragenter', 'dragover'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
  ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('over'); }); });
  drop.addEventListener('drop', function (e) { var f = e.dataTransfer && e.dataTransfer.files[0]; if (f) readFile(f); });
  document.addEventListener('dragover', function (e) { e.preventDefault(); });
  document.addEventListener('drop', function (e) { e.preventDefault(); });

  load(K.makeSample(), true);
})();
