/*
 * 카톡 대화 분석기 - 분석 엔진
 * 브라우저와 Node(테스트) 양쪽에서 돌아가요. 네트워크를 전혀 쓰지 않아요.
 */
var KakaoCore = (function () {
  'use strict';

  /* ------------------------------------------------------------------ */
  /* 1. 파일 읽기 (인코딩, zip)                                           */
  /* ------------------------------------------------------------------ */

  function decodeBytes(input) {
    var u8 = input instanceof Uint8Array ? input : new Uint8Array(input);
    if (u8[0] === 0xff && u8[1] === 0xfe) return new TextDecoder('utf-16le').decode(u8.subarray(2));
    if (u8[0] === 0xfe && u8[1] === 0xff) return new TextDecoder('utf-16be').decode(u8.subarray(2));
    var s = new TextDecoder('utf-8').decode(u8);
    var bad = (s.slice(0, 20000).match(/�/g) || []).length;
    if (bad > 5) {
      try { return new TextDecoder('euc-kr').decode(u8); } catch (e) { /* utf-8 그대로 */ }
    }
    return s;
  }

  function isZip(u8) {
    return u8.length > 4 && u8[0] === 0x50 && u8[1] === 0x4b && u8[2] === 0x03 && u8[3] === 0x04;
  }

  // zip 안의 .txt/.csv 파일들을 꺼내요. (압축 해제는 브라우저 내장 DecompressionStream)
  async function unzipTexts(input) {
    var u8 = input instanceof Uint8Array ? input : new Uint8Array(input);
    var dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    var eocd = -1;
    for (var i = u8.length - 22; i >= Math.max(0, u8.length - 65557); i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('zip 파일을 읽지 못했어요.');
    var count = dv.getUint16(eocd + 10, true);
    var off = dv.getUint32(eocd + 16, true);
    var out = [];
    for (var k = 0; k < count; k++) {
      if (dv.getUint32(off, true) !== 0x02014b50) break;
      var method = dv.getUint16(off + 10, true);
      var csize = dv.getUint32(off + 20, true);
      var nlen = dv.getUint16(off + 28, true);
      var xlen = dv.getUint16(off + 30, true);
      var clen = dv.getUint16(off + 32, true);
      var loff = dv.getUint32(off + 42, true);
      var name = new TextDecoder('utf-8').decode(u8.subarray(off + 46, off + 46 + nlen));
      off += 46 + nlen + xlen + clen;
      if (!/\.(txt|csv)$/i.test(name) || /(^|\/)__MACOSX\//.test(name)) continue;
      var lnlen = dv.getUint16(loff + 26, true);
      var lxlen = dv.getUint16(loff + 28, true);
      var start = loff + 30 + lnlen + lxlen;
      var data = u8.subarray(start, start + csize);
      if (method === 0) {
        out.push({ name: name, bytes: data });
      } else if (method === 8) {
        if (typeof DecompressionStream === 'undefined') {
          throw new Error('이 브라우저는 zip을 풀 수 없어요. 압축을 푼 뒤 txt 파일을 올려 주세요.');
        }
        var stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
        var ab = await new Response(stream).arrayBuffer();
        out.push({ name: name, bytes: new Uint8Array(ab) });
      }
    }
    return out;
  }

  // 파일 바이트 → 대화 텍스트
  async function readChatBytes(input) {
    var u8 = input instanceof Uint8Array ? input : new Uint8Array(input);
    if (isZip(u8)) {
      var files = await unzipTexts(u8);
      if (!files.length) throw new Error('zip 안에 대화 텍스트(.txt) 파일이 없어요.');
      files.sort(function (a, b) { return b.bytes.length - a.bytes.length; });
      return decodeBytes(files[0].bytes);
    }
    return decodeBytes(u8);
  }

  /* ------------------------------------------------------------------ */
  /* 2. 대화 파싱                                                          */
  /* ------------------------------------------------------------------ */

  // 안드로이드/아이폰: "2024. 1. 5. 오후 3:21, 홍길동 : 안녕"
  //                  "2024년 1월 5일 오후 3:21, 홍길동 : 안녕"
  //                  "2024-01-05 15:21:00, 홍길동 : 안녕"
  var RE_MOBILE = /^(\d{4})\s*(?:\.|년|-)\s*(\d{1,2})\s*(?:\.|월|-)\s*(\d{1,2})\s*(?:\.|일)?\s*(오전|오후|AM|PM|am|pm)?\s*(\d{1,2}):(\d{2})(?::\d{2})?\s*(오전|오후|AM|PM|am|pm)?\s*,\s*([\s\S]*)$/;
  // 안드로이드 시스템 줄: "2024. 1. 5. 오후 3:21: 홍길동님이 들어왔습니다."
  var RE_MOBILE_PREFIX = /^(\d{4})\s*(?:\.|년|-)\s*(\d{1,2})\s*(?:\.|월|-)\s*(\d{1,2})\s*(?:\.|일)?\s*(오전|오후|AM|PM)?\s*(\d{1,2}):(\d{2})/;
  // PC: "[홍길동] [오후 3:21] 안녕"
  var RE_PC = /^\[([^\]]+)\]\s*\[(오전|오후|AM|PM|am|pm)?\s*(\d{1,2}):(\d{2})\]\s?([\s\S]*)$/;
  // 날짜 줄: "2024년 1월 5일 금요일", "--------------- 2024년 1월 5일 금요일 ---------------"
  var RE_DATE_KO = /^-*\s*(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일\s*(?:[월화수목금토일]요일)?\s*-*\s*$/;
  var RE_DATE_EN = /^-+\s*[A-Za-z]+,\s*([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})\s*-+\s*$/;
  var EN_MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };
  var RE_SYSTEM = /님이 들어왔습니다\.?$|님이 나갔습니다\.?$|님을 초대했습니다\.?$|님을 내보냈습니다\.?$|초대했습니다\.?$|채팅방 관리자가|메시지를 가렸습니다/;

  function hour24(ap, h) {
    h = Number(h);
    if (!ap) return h;
    var pm = ap === '오후' || /^pm$/i.test(ap);
    var am = ap === '오전' || /^am$/i.test(ap);
    if (pm && h < 12) h += 12;
    if (am && h === 12) h = 0;
    return h;
  }

  function parseCsv(text) {
    var rows = [], row = [], cur = '', q = false;
    for (var i = 0; i < text.length; i++) {
      var c = text[i];
      if (q) {
        if (c === '"') {
          if (text[i + 1] === '"') { cur += '"'; i++; } else q = false;
        } else cur += c;
      } else if (c === '"') q = true;
      else if (c === ',') { row.push(cur); cur = ''; }
      else if (c === '\n') { row.push(cur); rows.push(row); row = []; cur = ''; }
      else cur += c;
    }
    if (cur || row.length) { row.push(cur); rows.push(row); }
    return rows;
  }

  function parse(text) {
    text = String(text).replace(/^﻿/, '').replace(/\r\n?/g, '\n');
    var msgs = [];
    var counts = { mobile: 0, pc: 0, csv: 0 };
    var firstLine = text.slice(0, 300).split('\n')[0];

    function push(y, mo, d, h, mi, who, body) {
      var m = { ts: new Date(+y, +mo - 1, +d, +h, +mi).getTime(), who: String(who).trim(), text: body };
      msgs.push(m);
      return m;
    }

    if (/^"?date"?\s*,\s*"?user"?\s*,\s*"?message"?/i.test(firstLine)) {
      var rows = parseCsv(text);
      for (var r = 1; r < rows.length; r++) {
        var row = rows[r];
        if (row.length < 3) continue;
        var dm = /(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})/.exec(row[0]);
        if (!dm || !row[1]) continue;
        push(dm[1], dm[2], dm[3], dm[4], dm[5], row[1], row.slice(2).join(','));
        counts.csv++;
      }
      return finish(msgs, counts);
    }

    var lines = text.split('\n');
    var last = null, curDate = null;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var t = line.trim();
      if (!t) continue;
      var m = RE_MOBILE.exec(t);
      if (m) {
        var rest = m[8];
        var idx = rest.indexOf(' : ');
        var who = null, body = null;
        if (idx > 0) { who = rest.slice(0, idx); body = rest.slice(idx + 3); }
        else if (/\s:$/.test(rest)) { who = rest.slice(0, -2); body = ''; }
        if (who !== null) {
          last = push(m[1], m[2], m[3], hour24(m[4] || m[7], m[5]), m[6], who, body);
          curDate = [+m[1], +m[2], +m[3]];
          counts.mobile++;
        } else last = null; // 시스템 메시지
        continue;
      }
      if (RE_MOBILE_PREFIX.test(t)) { last = null; continue; }
      var dk = RE_DATE_KO.exec(t);
      if (dk) { curDate = [+dk[1], +dk[2], +dk[3]]; last = null; continue; }
      var de = RE_DATE_EN.exec(t);
      if (de && EN_MONTHS[de[1].slice(0, 3).toLowerCase()]) {
        curDate = [+de[3], EN_MONTHS[de[1].slice(0, 3).toLowerCase()], +de[2]]; last = null; continue;
      }
      var p = RE_PC.exec(t);
      if (p && curDate) {
        last = push(curDate[0], curDate[1], curDate[2], hour24(p[2], p[3]), p[4], p[1], p[5]);
        counts.pc++;
        continue;
      }
      if (RE_SYSTEM.test(t)) { last = null; continue; }
      if (last) last.text += '\n' + line.replace(/\s+$/, '');
      // 첫 메시지 전의 줄(제목, 저장한 날짜)은 버려요
    }
    return finish(msgs, counts);
  }

  var RE_PHOTO = /^사진(\s*\d+장)?$/;
  var RE_EMO = /^\(?이모티콘\)?/;
  var RE_VIDEO = /^동영상$/;
  var RE_VOICE = /^음성\s?메시지/;
  var RE_FILE = /^파일\s?:/;
  var RE_DELETED = /^(삭제된 메시지입니다\.?|메시지가 삭제되었습니다\.?)$/;
  var RE_CALL = /^(보이스톡|페이스톡|통화)/;
  var RE_LINK = /https?:\/\//;

  function classify(m) {
    var t = m.text.trim();
    m.n = 1;
    if (RE_PHOTO.test(t)) { m.kind = 'photo'; var pm = /(\d+)장/.exec(t); if (pm) m.n = +pm[1]; return; }
    if (RE_EMO.test(t)) { m.kind = 'emoticon'; m.body = t.replace(RE_EMO, '').trim(); return; }
    if (RE_VIDEO.test(t)) { m.kind = 'video'; return; }
    if (RE_VOICE.test(t)) { m.kind = 'voice'; return; }
    if (RE_FILE.test(t)) { m.kind = 'file'; return; }
    if (RE_DELETED.test(t)) { m.kind = 'deleted'; return; }
    if (RE_CALL.test(t) && t.length < 40) {
      m.kind = 'call';
      var c = /(\d{1,2}):(\d{2})(?::(\d{2}))?/.exec(t);
      m.callSec = 0;
      if (c) m.callSec = c[3] !== undefined ? (+c[1] * 3600 + +c[2] * 60 + +c[3]) : (+c[1] * 60 + +c[2]);
      return;
    }
    m.kind = RE_LINK.test(t) ? 'link' : 'text';
    m.body = t;
  }

  function finish(msgs, counts) {
    var by = {};
    msgs.forEach(function (m, i) {
      m.i = i;
      classify(m);
      by[m.who] = (by[m.who] || 0) + 1;
    });
    var participants = Object.keys(by).map(function (k) { return { name: k, count: by[k] }; })
      .sort(function (a, b) { return b.count - a.count; });
    var format = counts.csv ? 'Mac (CSV)' : counts.pc > counts.mobile ? 'PC' : counts.mobile ? '모바일 (안드로이드·아이폰)' : '알 수 없음';
    return { messages: msgs, participants: participants, format: format };
  }

  /* ------------------------------------------------------------------ */
  /* 3. 사전 (키워드 기반이라 대략적인 값이에요)                            */
  /* ------------------------------------------------------------------ */

  var R = {
    apology: /미안|미얀|미아내|죄송|잘못했|잘못 했|내 잘못|내가 잘못|용서해|용서 해|사과할게|사과 할게|사과할께|화 풀어|화풀어|쏘리|쏘뤼|sorry|ㅈㅅ/,
    aff: /사랑해|사랑행|사랑한다|사랑하는|싸랑|사랑해요|보고싶|보고 싶|보고시퍼|보구싶|보고파|좋아해|좋아행|귀여워|귀엽|예뻐|이뻐|예쁘다|이쁘다|뽀뽀|안아줘|안아주고|쪽쪽|쪼옥|♥|❤|💕|💗|💖|💘|💞|😘|🥰|😍/,
    love: /사랑해|사랑행|사랑한다|싸랑해|사랑해요/,
    laugh: /ㅋㅋ|ㅎㅎ|크크|키키|하하|😂|🤣|lol/,
    bigLaugh: /ㅋ{3,}|ㅎ{3,}|😂|🤣/,
    cry: /ㅠ|ㅜ|😭|😢|🥺/,
    neg: /짜증|화나|화났|화가 나|빡치|빡쳐|열받|서운|섭섭|실망|어이없|어이가 없|됐어|됐다고|그만해|그만하자|싫다고|왜 그래|왜그래|헤어지|너무해|기분 나빠|기분나빠|기분 나쁘|기분나쁘|무시하|답답해|아 진짜|ㅡㅡ|-_-|🙄|😡|😤|😠|읽씹|연락 좀|연락좀|말을 왜|그런 식|그런식|알아서 해|맘대로 해|마음대로 해/,
    comfort: /힘들|걱정|고민|속상|우울|스트레스|힘내|토닥|위로|응원해|화이팅|파이팅|괜찮아\?|괜찮아\s*\?|아프지 마/,
    forgive: /괜찮아|괜찮다|괜차나|괜춘|알았어|알겠어|용서|풀렸|화 풀었|아니야|아냐|됐어 이제|이해해/,
    q: /\?|뭐해|뭐 해|어디야|언제 와|몇 시|몇시|어때\s*$|할래|갈래|먹을래|볼래|했니|했냐|먹었어\s*$|잤어\s*$/,
    night: /잘자|잘 자|굿나잇|굿밤|꿈나라|좋은 꿈|잘게\s*$|자러 갈|자야겠|자야지/,
    morning: /굿모닝|잘잤|잘 잤|좋은 아침|일어났어|기상/,
    short: /^(ㅇㅇ|ㅇㅋ|ㅇ|응|웅|엉|어|그래|알겠어|알았어|ㅇㅋㅇㅋ|오키|ok|okay|넵|네|넹|녜|ㄱㄱ|ㄴㄴ)[.!~ ]*$/i,
    praise: /귀엽|귀여|예쁘|이쁘|예뻐|이뻐|멋있|멋지|대박|우와|와아|오오|맛있겠|부럽|좋다|좋네|좋아 보|잘 나왔|잘나왔|미쳤/
  };

  var TOPICS = [
    { id: 'food', name: '음식·맛집', re: /밥|먹|맛있|맛집|배고|점심|저녁|아침|치킨|피자|커피|카페|술|맥주|소주|라면|고기|디저트|배달|메뉴|간식|떡볶이|초밥|빵|파스타/ },
    { id: 'date', name: '약속·데이트', re: /만나|데이트|보자|볼까|주말에|영화 보|놀러|약속|어디서|데리러|픽업|도착|출발|가는 중|가는중|기다려|예매/ },
    { id: 'work', name: '일·회사·공부', re: /회사|출근|퇴근|야근|업무|회의|팀장|과장|부장|상사|사장님|월급|알바|학교|수업|과제|시험|공부|강의|교수|미팅|보고서|프로젝트/ },
    { id: 'love', name: '애정 표현', re: R.aff },
    { id: 'fight', name: '서운함·갈등', re: R.neg },
    { id: 'health', name: '건강·컨디션', re: /아파|아프|병원|약 먹|감기|열나|두통|머리 아|배 아|피곤|졸려|졸리|잠 와|컨디션|운동|헬스|다이어트|필라테스/ },
    { id: 'money', name: '돈·쇼핑', re: /돈|결제|카드값|샀어|사줘|살까|쇼핑|주문|택배|할인|가격|비싸|만원|적금|월세|용돈|쿠팡/ },
    { id: 'travel', name: '여행·나들이', re: /여행|비행기|호텔|숙소|펜션|바다|제주|캠핑|기차|ktx|휴가|드라이브|해외/ },
    { id: 'family', name: '가족·친구', re: /엄마|아빠|부모님|어머님|아버님|어머니|아버지|언니|동생|할머니|할아버지|친구|가족|조카|삼촌|이모(?!티)|고모/ },
    { id: 'daily', name: '일상 보고', re: /뭐해|뭐 해|집이야|집 왔|집에 왔|도착했|일어났|씻고|씻었|누웠|퇴근했|출근했|지금 \S+ 중/ },
    { id: 'greet', name: '굿모닝·굿나잇', re: /잘자|잘 자|굿나잇|굿밤|굿모닝|잘잤|잘 잤|좋은 아침|꿈나라|좋은 꿈/ },
    { id: 'fun', name: '취미·콘텐츠', re: /게임|넷플|유튜브|드라마|영화|노래|음악|웹툰|애니|축구|야구|쇼츠|릴스|콘서트|전시/ },
    { id: 'future', name: '결혼·미래', re: /결혼|웨딩|상견례|청첩장|신혼|전세|이사|아기 낳|미래|프로포즈|반지|스드메|예식장|혼수/ }
  ];

  var APOLOGY_TYPES = [
    { name: '미안해형', re: /미안|미얀|미아내/ },
    { name: '죄송형 (존댓말)', re: /죄송/ },
    { name: '잘못 인정형', re: /잘못했|잘못 했|내 잘못|내가 잘못/ },
    { name: '용서·화 풀어형', re: /용서|화 풀어|화풀어|사과할게|사과 할게|사과할께/ },
    { name: '가벼운 쏘리형', re: /쏘리|쏘뤼|sorry|ㅈㅅ/ }
  ];

  var NICKNAMES = ['자기야', '자기', '자갸', '여보', '애기야', '애기', '아가', '공주', '왕자', '곰돌이', '베이비', '허니', '달링', '울애기', '돼지', '토끼', '강아지', '애깅', '꼬맹이'];

  var STOP = ('그냥 진짜 근데 그래서 그리고 이제 아니 그거 이거 저거 지금 오늘 내가 너가 니가 나도 너도 우리 혹시 그럼 ' +
    '그래 그럼 너무 완전 많이 조금 좀 하고 해서 했어 했는데 하는 있어 있는데 없어 같아 같은 근데 그건 이건 ' +
    '나는 너는 나랑 너랑 제가 저도 저는 그게 이게 뭐야 그런 이런 어떻게 왜 언제 어디 다시 계속 정말 ' +
    '응응 웅웅 그치 그니까 아니야 아냐 맞아 사진 이모티콘 동영상 아직 벌써 이따 내일 어제').split(' ');
  var STOPSET = {};
  STOP.forEach(function (w) { STOPSET[w] = 1; });

  /* ------------------------------------------------------------------ */
  /* 4. 유틸                                                              */
  /* ------------------------------------------------------------------ */

  var MIN = 60000, HOUR = 3600000, DAY = 86400000;
  var SESSION_GAP = 60 * MIN;   // 1시간 넘게 쉬면 새 대화
  var REPLY_LIMIT = 12 * HOUR;  // 12시간 안에 온 답만 "답장"으로 봐요

  function median(arr) {
    if (!arr.length) return null;
    var s = arr.slice().sort(function (a, b) { return a - b; });
    var h = s.length >> 1;
    return s.length % 2 ? s[h] : (s[h - 1] + s[h]) / 2;
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function dayKey(ts) { var d = new Date(ts); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function monthKey(ts) { var d = new Date(ts); return d.getFullYear() + '-' + pad(d.getMonth() + 1); }

  function hasBatchim(word) {
    var ch = String(word).trim().slice(-1);
    var code = ch.charCodeAt(0);
    if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 !== 0;
    if (/[0-9]/.test(ch)) return '013678'.indexOf(ch) >= 0;
    return null;
  }
  // josa('민준', '이', '가') → '민준이'
  function josa(word, withB, without) {
    var b = hasBatchim(word);
    if (b === null) return word + '(' + withB + ')' + without;
    return word + (b ? withB : without);
  }

  function fmtDuration(ms) {
    if (ms === null || ms === undefined || isNaN(ms)) return '-';
    var m = Math.round(ms / MIN);
    if (m < 1) return '1분';
    if (m < 60) return m + '분';
    var h = Math.floor(m / 60), r = m % 60;
    if (h < 24) return r ? h + '시간 ' + r + '분' : h + '시간';
    var d = Math.floor(h / 24), rh = h % 24;
    return rh ? d + '일 ' + rh + '시간' : d + '일';
  }

  function inc(obj, key, n) { obj[key] = (obj[key] || 0) + (n || 1); }

  /* ------------------------------------------------------------------ */
  /* 5. 분석                                                              */
  /* ------------------------------------------------------------------ */

  function flagsOf(m) {
    var isText = m.kind === 'text' || m.kind === 'link' || m.kind === 'emoticon';
    var body = isText ? String(m.body || '').toLowerCase() : '';
    var f = {
      media: m.kind === 'emoticon' || m.kind === 'photo' || m.kind === 'video',
      photo: m.kind === 'photo' || m.kind === 'video',
      emo: m.kind === 'emoticon'
    };
    if (body) {
      f.apology = R.apology.test(body);
      f.aff = R.aff.test(body);
      f.love = R.love.test(body);
      f.laugh = R.laugh.test(body);
      f.bigLaugh = R.bigLaugh.test(body);
      f.cry = R.cry.test(body);
      f.neg = R.neg.test(body) && !f.laugh;
      f.comfort = R.comfort.test(body);
      f.forgive = R.forgive.test(body);
      f.q = R.q.test(body);
      f.night = R.night.test(body);
      f.morning = R.morning.test(body);
      f.short = m.kind === 'text' && R.short.test(body.trim());
      f.praise = R.praise.test(body);
      f.topics = [];
      for (var i = 0; i < TOPICS.length; i++) if (TOPICS[i].re.test(body)) f.topics.push(TOPICS[i].id);
    } else f.topics = [];
    return f;
  }

  var LABELS = [
    ['apology', '사과'], ['neg', '서운함·짜증'], ['forgive', '괜찮다고 받아줌'], ['aff', '애정 표현'],
    ['comfort', '걱정·위로'], ['praise', '칭찬·감탄'], ['laugh', '웃음'], ['q', '되묻기·질문'],
    ['media', '사진·이모티콘'], ['short', '짧은 대답'], ['other', '그냥 이어서 대화']
  ];

  function orFlags(msgs, limit) {
    var o = {};
    for (var i = 0; i < msgs.length && i < (limit || 1e9); i++) {
      var f = msgs[i].f;
      for (var k in f) if (f[k] === true) o[k] = true;
    }
    return o;
  }

  function primaryLabel(of) {
    for (var i = 0; i < LABELS.length - 1; i++) if (of[LABELS[i][0]]) return LABELS[i][0];
    return 'other';
  }

  var TRIGGERS = [
    { id: 'apology', when: '사과하면', test: function (t) { return t.of.apology; }, key: 'forgive', keyText: '괜찮다고 받아 줬어요' },
    { id: 'upset', when: '서운함이나 짜증을 드러내면', test: function (t) { return t.of.neg; }, key: 'apology', keyText: '사과부터 했어요' },
    { id: 'aff', when: '애정 표현을 하면', test: function (t) { return t.of.aff; }, key: 'aff', keyText: '애정 표현으로 돌려줬어요' },
    { id: 'photo', when: '사진이나 동영상을 보내면', test: function (t) { return t.of.photo; }, key: 'praise', keyText: '칭찬이나 감탄으로 반응했어요' },
    { id: 'laugh', when: '크게 웃으면 (ㅋㅋㅋ)', test: function (t) { return t.of.bigLaugh; }, key: 'laugh', keyText: '같이 웃어 줬어요' },
    { id: 'cry', when: '우는 소리 (ㅠㅠ)를 하면', test: function (t) { return t.of.cry && !t.of.laugh; }, key: 'comfort', keyText: '걱정하거나 위로해 줬어요' },
    { id: 'night', when: '잘 자라고 하면', test: function (t) { return t.of.night; }, key: 'night', keyText: '잘 자라고 답해 줬어요' },
    { id: 'emo', when: '이모티콘을 보내면', test: function (t) { return t.of.emo; }, key: 'emo', keyText: '이모티콘으로 받아쳤어요' },
    { id: 'q', when: '질문을 하면', test: function (t) { return t.of.q; }, key: null },
    { id: 'late', when: '질문에 1시간 넘게 늦게 답하면', test: function (t) { return t.late; }, key: 'neg', keyText: '서운함을 드러냈어요' },
    { id: 'first', when: '먼저 말을 걸면 (선톡)', test: function (t) { return t.first; }, key: null }
  ];

  var KEY_NAMES = { forgive: '괜찮다는', apology: '사과하는', aff: '애정 표현', praise: '칭찬하는', laugh: '같이 웃는',
    comfort: '위로하는', night: '잘 자 인사', emo: '이모티콘', neg: '서운해하는' };

  function snippet(msgs, max) {
    var s = msgs.slice(0, 3).map(function (m) {
      if (m.kind === 'photo') return '(사진)';
      if (m.kind === 'video') return '(동영상)';
      if (m.kind === 'emoticon') return m.body ? '(이모티콘) ' + m.body : '(이모티콘)';
      return m.text.replace(/\s+/g, ' ').trim();
    }).join(' / ');
    max = max || 60;
    return s.length > max ? s.slice(0, max - 1) + '…' : s;
  }

  function analyze(parsed, A, B) {
    var all = parsed.messages;
    var who = [A, B];
    var M = all.filter(function (m) { return m.who === A || m.who === B; });
    if (!M.length) throw new Error('두 사람의 메시지를 찾지 못했어요.');
    M.forEach(function (m) { m.f = flagsOf(m); });

    var P = {};
    who.forEach(function (w) {
      P[w] = {
        name: w, msgs: 0, chars: 0, textMsgs: 0, photos: 0, emoticons: 0, videos: 0, voices: 0, files: 0, deleted: 0, links: 0,
        calls: 0, callSec: 0, laughChars: 0, cryMsgs: 0, apology: 0, aff: 0, love: 0, neg: 0, q: 0, night: 0, morning: 0,
        topics: {}, apologyTypes: {}, words: {}, emojis: {}, nick: {}, hours: new Array(24).fill(0),
        dawn: 0, longest: null, starts: 0, ends: 0, replyDelays: [], fast: 0, slow: 0, ghosted: 0,
        apologyFirst: 0, upsetFirst: 0, apologySamples: [], turns: 0, turnLenSum: 0
      };
    });

    var months = {}, days = {}, heat = [];
    for (var d0 = 0; d0 < 7; d0++) heat.push(new Array(24).fill(0));
    var apologyMonths = {};

    M.forEach(function (m) {
      var p = P[m.who], dt = new Date(m.ts), h = dt.getHours();
      p.msgs++;
      p.hours[h]++;
      heat[dt.getDay()][h]++;
      if (h < 5) p.dawn++;
      var mk = monthKey(m.ts);
      if (!months[mk]) months[mk] = {}; inc(months[mk], m.who);
      var dk = dayKey(m.ts);
      if (!days[dk]) days[dk] = { total: 0 }; days[dk].total++; inc(days[dk], m.who);
      switch (m.kind) {
        case 'photo': p.photos += m.n; break;
        case 'emoticon': p.emoticons++; break;
        case 'video': p.videos++; break;
        case 'voice': p.voices++; break;
        case 'file': p.files++; break;
        case 'deleted': p.deleted++; break;
        case 'call': p.calls++; p.callSec += m.callSec || 0; break;
        case 'link': p.links++; break;
      }
      var f = m.f;
      if (m.kind === 'text' || m.kind === 'link') {
        var body = m.body || '';
        p.textMsgs++;
        p.chars += body.length;
        if (!p.longest || body.length > p.longest.len) p.longest = { len: body.length, text: body, ts: m.ts };
        var lc = body.match(/[ㅋㅎ]/g); if (lc) p.laughChars += lc.length;
        var ems = body.match(/\p{Extended_Pictographic}/gu);
        if (ems) ems.forEach(function (e) { inc(p.emojis, e); });
        body.split(/[\s.,!?~^…"'()[\]{}<>:;/\\|+=*&%$#@`-]+/).forEach(function (w) {
          if (w.length < 2 || w.length > 12) return;
          if (/^[ㄱ-ㅎㅏ-ㅣ]+$/.test(w) || /^\d+$/.test(w) || /^https?/.test(w)) return;
          if (/\p{Extended_Pictographic}/u.test(w)) return;
          var lw = w.toLowerCase();
          if (STOPSET[lw]) return;
          inc(p.words, lw);
        });
        NICKNAMES.forEach(function (n) { if (body.indexOf(n) >= 0) inc(p.nick, n); });
      }
      if (f.cry) p.cryMsgs++;
      if (f.apology) {
        p.apology++;
        inc(apologyMonths, mk + '|' + m.who);
        var lowb = String(m.body || '').toLowerCase();
        APOLOGY_TYPES.forEach(function (t) { if (t.re.test(lowb)) inc(p.apologyTypes, t.name); });
        p.apologySamples.push({ ts: m.ts, text: m.body });
      }
      if (f.aff) p.aff++;
      if (f.love) p.love++;
      if (f.neg) p.neg++;
      if (f.q) p.q++;
      if (f.night) p.night++;
      if (f.morning) p.morning++;
      f.topics.forEach(function (t) { inc(p.topics, t); });
    });

    // 텍스트 nick 정리: '자기야'가 있으면 '자기'를 중복으로 세지 않도록 긴 걸 우선
    who.forEach(function (w) {
      var nk = P[w].nick;
      if (nk['자기야'] && nk['자기']) nk['자기'] = Math.max(0, nk['자기'] - nk['자기야']);
      if (nk['애기야'] && nk['애기']) nk['애기'] = Math.max(0, nk['애기'] - nk['애기야']);
      if (nk['울애기'] && nk['애기']) nk['애기'] = Math.max(0, nk['애기'] - nk['울애기']);
    });

    /* --- 대화(세션)와 턴 --- */
    var sessions = [], cur = null;
    M.forEach(function (m, i) {
      if (!cur || m.ts - M[i - 1].ts > SESSION_GAP) {
        cur = { start: m.ts, end: m.ts, msgs: [], gapBefore: cur ? m.ts - cur.end : null };
        sessions.push(cur);
      }
      cur.msgs.push(m);
      cur.end = m.ts;
      m.s = sessions.length - 1;
    });

    var turns = [];
    M.forEach(function (m, i) {
      var t = turns[turns.length - 1];
      if (!t || t.who !== m.who || m.s !== t.s) {
        t = { who: m.who, start: m.ts, end: m.ts, msgs: [], s: m.s, first: !t || m.s !== t.s };
        turns.push(t);
      }
      t.msgs.push(m);
      t.end = m.ts;
    });
    turns.forEach(function (t, i) {
      t.of = orFlags(t.msgs);
      t.ofHead = orFlags(t.msgs, 5);
      t.label = primaryLabel(t.ofHead);
      var prev = turns[i - 1];
      t.prev = prev;
      t.delay = prev && prev.who !== t.who ? t.start - prev.end : null;
      // 늦은 답장: 상대가 낮(7시~23시)에 남긴 질문에 1~12시간 뒤에 답한 경우
      t.late = t.delay !== null && t.delay >= HOUR && t.delay <= REPLY_LIMIT && !!prev.of.q &&
        !prev.of.night && new Date(prev.end).getHours() >= 7;
      var next = turns[i + 1];
      t.resp = next && next.who !== t.who && next.start - t.end <= REPLY_LIMIT ? next : null;
      P[t.who].turns++;
      P[t.who].turnLenSum += t.msgs.length;
    });

    // 답장 속도
    turns.forEach(function (t) {
      if (t.delay !== null && t.delay <= REPLY_LIMIT) {
        var p = P[t.who];
        p.replyDelays.push(t.delay);
        if (t.delay <= 5 * MIN) p.fast++;
        if (t.delay >= 3 * HOUR) p.slow++;
      }
    });
    // 질문을 남겼는데 3시간 넘게 답이 없던 횟수 (답 안 한 쪽에 카운트)
    turns.forEach(function (t, i) {
      if (!t.of.q) return;
      var other = t.who === A ? B : A;
      var next = turns[i + 1]; // 턴은 말한 사람이 바뀔 때마다 끊기니까 다음 턴이 상대의 답이에요
      if (next && next.who === t.who) next = null;
      if (!next || next.start - t.end >= 3 * HOUR) P[other].ghosted++;
    });

    /* --- 세션 분류 --- */
    var TYPE_DEFS = [
      { id: 'fight', name: '다툼·서운함', desc: '서운함이나 짜증이 여러 번 오간 대화' },
      { id: 'comfort', name: '고민·위로', desc: '힘든 일을 털어놓고 다독인 대화' },
      { id: 'love', name: '애정 듬뿍', desc: '사랑해, 보고 싶어가 넘친 대화' },
      { id: 'plan', name: '약속·데이트 계획', desc: '언제 어디서 만날지 정한 대화' },
      { id: 'greet', name: '굿모닝·굿나잇 인사', desc: '짧게 인사만 나눈 대화' },
      { id: 'marathon', name: '폭풍 수다', desc: '한 번에 100마디 넘게 이어진 대화' },
      { id: 'daily', name: '일상 보고', desc: '밥, 출퇴근, 지금 뭐 하는지 나눈 대화' },
      { id: 'short', name: '짧은 연락', desc: '세 마디 안에 끝난 대화' },
      { id: 'chat', name: '잡담', desc: '그 밖의 수다' }
    ];
    var typeCount = {}, typeExamples = {};
    sessions.forEach(function (s) {
      var c = { neg: 0, apology: 0, aff: 0, comfort: 0, plan: 0, greet: 0, daily: 0, laugh: 0 };
      var byWho = {};
      s.msgs.forEach(function (m) {
        var f = m.f;
        if (f.neg) c.neg++;
        if (f.apology) c.apology++;
        if (f.aff) c.aff++;
        if (f.comfort) c.comfort++;
        if (f.laugh) c.laugh++;
        if (f.night || f.morning) c.greet++;
        if (f.topics.indexOf('date') >= 0) c.plan++;
        if (f.topics.indexOf('daily') >= 0 || f.topics.indexOf('food') >= 0 || f.topics.indexOf('work') >= 0) c.daily++;
        inc(byWho, m.who);
      });
      var len = s.msgs.length, type;
      if ((c.neg >= 2 && (c.neg / len >= 0.08 || c.apology >= 1)) || (c.neg >= 1 && c.apology >= 2)) type = 'fight';
      else if (c.comfort >= 2 && c.comfort / len >= 0.1) type = 'comfort';
      else if (c.aff >= 3 && c.aff / len >= 0.15) type = 'love';
      else if (c.plan >= 2 && c.plan / len >= 0.15) type = 'plan';
      else if (len <= 8 && c.greet >= 1) type = 'greet';
      else if (len >= 100) type = 'marathon';
      else if (len <= 3) type = 'short';
      else if (c.daily >= Math.max(2, 0.2 * len)) type = 'daily';
      else type = 'chat';
      s.type = type;
      s.c = c;
      s.byWho = byWho;
      inc(typeCount, type);
      if (!typeExamples[type]) typeExamples[type] = [];
      typeExamples[type].push(s);
      var starter = s.msgs[0].who, ender = s.msgs[len - 1].who;
      P[starter].starts++;
      P[ender].ends++;
    });
    var sessionTypes = TYPE_DEFS.map(function (t) {
      var ex = (typeExamples[t.id] || []).slice().sort(function (a, b) { return b.msgs.length - a.msgs.length; })[0];
      return { id: t.id, name: t.name, desc: t.desc, count: typeCount[t.id] || 0, example: ex ? { ts: ex.start, len: ex.msgs.length, text: snippet(ex.msgs, 50) } : null };
    }).filter(function (t) { return t.count > 0; }).sort(function (a, b) { return b.count - a.count; });

    /* --- 다툼 기록 --- */
    var fights = sessions.filter(function (s) { return s.type === 'fight'; }).map(function (s, idx) {
      var firstNeg = null, firstAp = null, apBy = {}, negBy = {}, lastNeg = null, makeup = null;
      s.msgs.forEach(function (m) {
        if (m.f.neg) { if (!firstNeg) firstNeg = m; lastNeg = m; inc(negBy, m.who); }
        if (m.f.apology) { if (!firstAp) firstAp = m; inc(apBy, m.who); }
      });
      if (lastNeg) {
        for (var k = lastNeg.i + 1; k < all.length; k++) {
          var mm = all[k];
          if (mm.f && (mm.f.aff || mm.f.laugh) && mm.ts - lastNeg.ts < 3 * DAY) { makeup = mm; break; }
          if (mm.ts - lastNeg.ts >= 3 * DAY) break;
        }
      }
      if (firstNeg) P[firstNeg.who].upsetFirst++;
      if (firstAp) P[firstAp.who].apologyFirst++;
      return {
        ts: s.start, len: s.msgs.length, duration: s.end - s.start,
        upsetBy: firstNeg ? firstNeg.who : null, apologyBy: firstAp ? firstAp.who : null,
        apBy: apBy, negBy: negBy,
        makeupAfter: makeup && lastNeg ? makeup.ts - lastNeg.ts : null, makeupBy: makeup ? makeup.who : null,
        quote: firstNeg ? snippet([firstNeg], 50) : snippet(s.msgs, 50),
        intensity: (s.c.neg + s.c.apology) / s.msgs.length
      };
    });

    /* --- 반응 패턴 --- */
    function baselineFor(Y) {
      var resp = turns.filter(function (t) { return t.who !== Y && t.resp; });
      var keyRate = {};
      ['forgive', 'apology', 'aff', 'praise', 'laugh', 'comfort', 'night', 'emo', 'neg'].forEach(function (k) {
        var c = 0;
        resp.forEach(function (t) { if (t.resp.ofHead[k]) c++; });
        keyRate[k] = resp.length ? c / resp.length : 0;
      });
      return { n: resp.length, delay: median(resp.map(function (t) { return t.resp.start - t.end; })), keyRate: keyRate };
    }
    var base = {}; base[A] = baselineFor(A); base[B] = baselineFor(B);

    var patterns = [];
    who.forEach(function (X) {
      var Y = X === A ? B : A;
      TRIGGERS.forEach(function (tr) {
        var items = turns.filter(function (t) { return t.who === X && tr.test(t); });
        if (items.length < 3) return;
        var responded = items.filter(function (t) { return t.resp; });
        var delays = responded.map(function (t) { return t.resp.start - t.end; });
        var labels = {};
        var keyHits = 0, example = null, keyExample = null;
        responded.forEach(function (t) {
          inc(labels, t.resp.label);
          var hit = tr.key ? !!t.resp.ofHead[tr.key] : false;
          if (hit) { keyHits++; keyExample = t; }
          example = t;
        });
        var ex = keyExample || example;
        var labelList = Object.keys(labels).map(function (k) {
          var name = LABELS.filter(function (l) { return l[0] === k; })[0][1];
          return { id: k, name: name, share: labels[k] / responded.length };
        }).sort(function (a, b) { return b.share - a.share; }).slice(0, 3);
        var med = median(delays);
        var b = base[Y];
        patterns.push({
          trigger: tr.id, from: X, to: Y, when: tr.when, n: items.length,
          responded: responded.length, responseRate: responded.length / items.length,
          delay: med, baseDelay: b.delay,
          key: tr.key, keyText: tr.keyText || null,
          keyRate: tr.key && responded.length ? keyHits / responded.length : null,
          baseKeyRate: tr.key ? b.keyRate[tr.key] : null,
          labels: labelList,
          example: ex ? { from: snippet(ex.msgs), to: snippet(ex.resp.msgs), ts: ex.end } : null,
          sentence: patternSentence(X, Y, tr, med, tr.key && responded.length ? keyHits / responded.length : null, b, responded.length / items.length)
        });
      });
    });

    function patternSentence(X, Y, tr, med, keyRate, b, rr) {
      var s = josa(X, '이', '가') + ' ' + tr.when + ', ' + josa(Y, '은', '는') + ' ';
      if (rr < 0.5) return s + '절반 넘게 12시간 안에 답하지 않았어요.';
      s += '보통 ' + fmtDuration(med) + ' 만에 답했';
      if (tr.key && keyRate !== null) {
        if (keyRate < 0.05) return s + '어요. ' + KEY_NAMES[tr.key] + ' 반응은 거의 없었어요.';
        return s + '고, ' + Math.round(keyRate * 100) + '%는 ' + tr.keyText;
      }
      if (b.delay && med !== null) {
        if (med <= b.delay * 0.6) return s + '어요. 평소(' + fmtDuration(b.delay) + ')보다 빨라요.';
        if (med >= b.delay * 1.6) return s + '어요. 평소(' + fmtDuration(b.delay) + ')보다 느려요.';
      }
      return s + '어요.';
    }

    /* --- 날짜/기간 --- */
    var dayKeys = Object.keys(days).sort();
    var streak = { len: 0, from: null, to: null }, run = 0, runFrom = null, prevD = null;
    dayKeys.forEach(function (k) {
      var d = new Date(k + 'T00:00:00');
      if (prevD && Math.round((d - prevD) / DAY) === 1) run++; else { run = 1; runFrom = k; }
      if (run > streak.len) streak = { len: run, from: runFrom, to: k };
      prevD = d;
    });
    var silence = { ms: 0, from: null, to: null };
    for (var si = 1; si < M.length; si++) {
      var g = M[si].ts - M[si - 1].ts;
      if (g > silence.ms) silence = { ms: g, from: M[si - 1].ts, to: M[si].ts };
    }
    var busiest = dayKeys.reduce(function (best, k) { return !best || days[k].total > days[best].total ? k : best; }, null);

    var monthKeys = [];
    if (M.length) {
      var d1 = new Date(M[0].ts), d2 = new Date(M[M.length - 1].ts);
      var y = d1.getFullYear(), mo = d1.getMonth();
      while (y < d2.getFullYear() || (y === d2.getFullYear() && mo <= d2.getMonth())) {
        monthKeys.push(y + '-' + pad(mo + 1));
        mo++; if (mo > 11) { mo = 0; y++; }
      }
    }
    var monthly = monthKeys.map(function (k) {
      var o = { month: k }; o[A] = (months[k] || {})[A] || 0; o[B] = (months[k] || {})[B] || 0;
      o.apA = apologyMonths[k + '|' + A] || 0; o.apB = apologyMonths[k + '|' + B] || 0;
      return o;
    });

    /* --- 사람별 정리 --- */
    function top(obj, n) {
      return Object.keys(obj).map(function (k) { return { key: k, count: obj[k] }; })
        .filter(function (x) { return x.count > 0; })
        .sort(function (a, b) { return b.count - a.count; }).slice(0, n);
    }
    var people = who.map(function (w) {
      var p = P[w];
      return {
        name: w, msgs: p.msgs, share: p.msgs / M.length, chars: p.chars,
        avgLen: p.textMsgs ? p.chars / p.textMsgs : 0,
        photos: p.photos, emoticons: p.emoticons, videos: p.videos, voices: p.voices, files: p.files, deleted: p.deleted, links: p.links,
        calls: p.calls, callSec: p.callSec, laughChars: p.laughChars, cryMsgs: p.cryMsgs,
        apology: p.apology, apologyPer1000: p.msgs ? p.apology / p.msgs * 1000 : 0,
        apologyTypes: top(p.apologyTypes, 5), apologyFirst: p.apologyFirst, upsetFirst: p.upsetFirst,
        apologySamples: p.apologySamples.slice(-3).reverse().map(function (s) { return { ts: s.ts, text: s.text.length > 50 ? s.text.slice(0, 49) + '…' : s.text }; }),
        aff: p.aff, affPer100: p.msgs ? p.aff / p.msgs * 100 : 0, love: p.love, neg: p.neg, q: p.q, night: p.night, morning: p.morning,
        topics: p.topics, words: top(p.words, 12), emojis: top(p.emojis, 6), nick: top(p.nick, 3),
        hours: p.hours, peakHour: p.hours.indexOf(Math.max.apply(null, p.hours)), dawnShare: p.msgs ? p.dawn / p.msgs : 0,
        longest: p.longest, starts: p.starts, ends: p.ends,
        replyMedian: median(p.replyDelays), replies: p.replyDelays.length,
        fastShare: p.replyDelays.length ? p.fast / p.replyDelays.length : 0, slow: p.slow, ghosted: p.ghosted,
        avgBurst: p.turns ? p.turnLenSum / p.turns : 0
      };
    });

    var topics = TOPICS.map(function (t) {
      var o = { id: t.id, name: t.name };
      people.forEach(function (p, i) { o[i ? 'b' : 'a'] = p.topics[t.id] || 0; o[i ? 'bShare' : 'aShare'] = p.msgs ? (p.topics[t.id] || 0) / p.msgs : 0; });
      o.total = o.a + o.b;
      return o;
    }).filter(function (t) { return t.total > 0; }).sort(function (a, b) { return b.total - a.total; });

    return {
      a: A, b: B, people: people,
      total: M.length, allTotal: all.length,
      first: M[0].ts, last: M[M.length - 1].ts,
      daysTalked: dayKeys.length, spanDays: Math.round((M[M.length - 1].ts - M[0].ts) / DAY) + 1,
      busiest: busiest ? { day: busiest, count: days[busiest].total } : null,
      streak: streak, silence: silence,
      monthly: monthly, heat: heat,
      sessions: sessions.length, sessionTypes: sessionTypes,
      fights: fights, topics: topics, patterns: patterns
    };
  }

  /* ------------------------------------------------------------------ */
  /* 6. 예시 대화 만들기 (가짜 커플 "민준"과 "서연")                         */
  /* ------------------------------------------------------------------ */

  function makeSample() {
    var seed = 20260601;
    function rnd() { seed |= 0; seed = seed + 0x6d2b79f5 | 0; var t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }
    function pick(a) { return a[Math.floor(rnd() * a.length)]; }
    var N = { A: '민준', B: '서연' };
    var DOW = ['일', '월', '화', '수', '목', '금', '토'];
    var out = ['서연 님과 카카오톡 대화', '저장한 날짜 : 2026. 11. 1. 오후 11:02', ''];
    function line(ts, who, text) {
      var d = new Date(ts), h = d.getHours(), ap = h < 12 ? '오전' : '오후', h12 = h % 12 === 0 ? 12 : h % 12;
      out.push(d.getFullYear() + '. ' + (d.getMonth() + 1) + '. ' + d.getDate() + '. ' + ap + ' ' + h12 + ':' + pad(d.getMinutes()) + ', ' + N[who] + ' : ' + text);
    }
    var scripts = {
      morning: [[['A', '굿모닝 ☀️'], ['B', '잘 잤어? ㅎㅎ'], ['A', '응 너는?'], ['B', '나 완전 꿀잠 잤어']],
        [['B', '일어났어?'], ['A', '방금 일어났어 ㅠㅠ 지각각'], ['B', '헐 빨리 가 ㅋㅋㅋ']],
        [['A', '좋은 아침 자기야'], ['B', '좋은 아침 ❤️'], ['B', '이모티콘']]],
      lunch: [[['B', '점심 뭐 먹어?'], ['A', '김치찌개 먹을 듯'], ['A', '사진'], ['B', '와 맛있겠다'], ['B', '나는 샐러드 ㅠㅠ 다이어트 중']],
        [['A', '배고파'], ['B', '나도 ㅋㅋ 오늘 회의 너무 길어'], ['A', '고생했어 맛있는 거 먹어']],
        [['B', '사진'], ['A', '오 파스타? 대박 맛있어 보인다'], ['B', '회사 앞에 새로 생겼어 ㅎㅎ 다음에 같이 가자']]],
      evening: [[['A', '퇴근했어?'], ['B', '이제 퇴근! 너무 피곤해'], ['A', '고생했어 ㅠㅠ 오늘 팀장님 또 그랬어?'], ['B', '응 진짜 스트레스 받아'], ['A', '힘내 내가 주말에 맛있는 거 사줄게'], ['B', '역시 너밖에 없다 ㅠㅠ']],
        [['B', '주말에 뭐 할까?'], ['A', '영화 보자! 예매할게'], ['B', '좋아 몇시에 만나?'], ['A', '토요일 2시 어때?'], ['B', '좋아 내가 데리러 갈게']],
        [['A', 'ㅋㅋㅋㅋㅋ 이거 봐 봐'], ['A', 'https://youtu.be/abc'], ['B', 'ㅋㅋㅋㅋㅋㅋ 미쳤다'], ['B', '이거 완전 너잖아'], ['A', '아니거든 ㅋㅋㅋ']],
        [['B', '오늘 엄마가 너 언제 오냐고 물어보셨어'], ['A', '헉 다음 달에 인사드리러 갈까?'], ['B', '좋아 엄마 좋아하실 듯 ㅎㅎ'], ['A', '긴장된다 ㅋㅋ']],
        [['A', '우리 결혼하면 어디 살까'], ['B', '나는 회사 근처면 좋겠어'], ['A', '전세 알아봐야겠다'], ['B', '벌써? ㅋㅋㅋ 귀여워']],
        [['B', '보고 싶다'], ['A', '나도 보고 싶어 ❤️'], ['B', '내일 볼까?'], ['A', '응 퇴근하고 데리러 갈게'], ['B', '사랑해'], ['A', '나도 사랑해 💕']],
        [['A', '오늘 너무 힘들었어 ㅠㅠ'], ['B', '왜 무슨 일 있었어?'], ['A', '프로젝트 마감인데 다 엎어졌어'], ['B', '헐 속상하겠다 ㅠㅠ'], ['B', '토닥토닥 힘내'], ['A', '고마워 너랑 얘기하니까 좀 낫다']],
        [['B', '넷플 뭐 볼까'], ['A', '그 드라마 새 시즌 나왔대'], ['B', '오 그거 보자 ㅎㅎ']]],
      night: [[['B', '잘 자 ❤️'], ['A', '너도 잘 자 사랑해']],
        [['A', '나 이제 잘게'], ['B', '응 좋은 꿈 꿔'], ['A', '이모티콘']],
        [['B', '졸려 ㅠㅠ 먼저 잘게'], ['A', '굿나잇 자기야']]],
      fight: [[['B', '왜 이렇게 연락이 없어?'], ['B', '나 진짜 서운해'], ['A', '미안해 ㅠㅠ 회의가 너무 길었어'], ['B', '그래도 한 마디는 할 수 있잖아'], ['A', '내가 잘못했어 다음부터 꼭 말할게'], ['B', '알았어 이번만 봐준다'], ['A', '사랑해 ❤️']],
        [['A', '아까 왜 그런 식으로 말했어?'], ['B', '내가 뭘'], ['A', '친구들 앞에서 좀 서운했어'], ['B', '아 그건 장난이었는데'], ['B', '미안 기분 나빴으면'], ['A', '괜찮아 나도 예민했나 봐']],
        [['B', '또 늦어? 아 진짜 짜증나'], ['A', '미안해 차가 너무 막혀'], ['A', '진짜 미안 10분이면 가'], ['B', '됐어 그냥 천천히 와'], ['A', '화 풀어 ㅠㅠ 맛있는 거 사줄게'], ['B', 'ㅋㅋ 알았어']]]
    };
    var t0 = new Date(2026, 5, 1).getTime();
    var lastDayHeader = '', lastTs = 0;
    function runScript(dayStart, hourFrom, hourTo, script, swap) {
      var ts = Math.max(dayStart + (hourFrom + rnd() * (hourTo - hourFrom)) * HOUR, lastTs + 70 * MIN);
      var prevWho = null;
      script.forEach(function (st) {
        var w = swap ? (st[0] === 'A' ? 'B' : 'A') : st[0];
        if (prevWho && prevWho !== w) ts += (rnd() < 0.08 ? 40 + rnd() * 90 : rnd() * (w === 'A' ? 6 : 12)) * MIN;
        else if (prevWho) ts += rnd() * 2 * MIN;
        var hdr = (function (d) { return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일 ' + DOW[d.getDay()] + '요일'; })(new Date(ts));
        if (hdr !== lastDayHeader) { out.push(''); out.push(hdr); lastDayHeader = hdr; }
        line(ts, w, st[1]);
        lastTs = ts;
        prevWho = w;
      });
    }
    for (var d = 0; d < 150; d++) {
      var ds = t0 + d * DAY;
      if (rnd() < 0.75) runScript(ds, 7, 9, pick(scripts.morning), rnd() < 0.4);
      if (rnd() < 0.6) runScript(ds, 12, 13, pick(scripts.lunch), rnd() < 0.3);
      if (d % 17 === 5 || (rnd() < 0.04)) runScript(ds, 18, 20, pick(scripts.fight), rnd() < 0.25);
      var ev = 1 + Math.floor(rnd() * 3);
      for (var e = 0; e < ev; e++) runScript(ds, 19 + e * 1.3, 20 + e * 1.3, pick(scripts.evening), rnd() < 0.45);
      if (rnd() < 0.8) runScript(ds, 23.2, 23.9, pick(scripts.night), rnd() < 0.4);
    }
    return out.join('\n');
  }

  return {
    decodeBytes: decodeBytes, unzipTexts: unzipTexts, readChatBytes: readChatBytes,
    parse: parse, analyze: analyze, makeSample: makeSample,
    josa: josa, fmtDuration: fmtDuration, dayKey: dayKey, TOPICS: TOPICS
  };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = KakaoCore;
