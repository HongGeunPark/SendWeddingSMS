// node test/run-tests.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const K = require('../src/core.js');

const sample = (f) => fs.readFileSync(path.join(__dirname, 'samples', f), 'utf8');
const hm = (m) => { const d = new Date(m.ts); return [d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes()]; };
let passed = 0;
async function test(name, fn) {
  try { await fn(); passed++; console.log('ok   ' + name); }
  catch (e) { console.error('FAIL ' + name + '\n     ' + e.message); process.exitCode = 1; }
}

(async () => {
  await test('안드로이드: 메시지, 여러 줄, 시스템 줄, 종류', () => {
    const p = K.parse(sample('android.txt'));
    assert.strictEqual(p.format, '모바일 (안드로이드·아이폰)');
    assert.strictEqual(p.messages.length, 10);
    assert.deepStrictEqual(p.participants.map((x) => x.name).sort(), ['지우', '현수']);
    const multi = p.messages[3];
    assert.strictEqual(multi.text, '12시 어때?\n여의도에서 보자\n점심 먹고 영화 보자');
    assert.deepStrictEqual(hm(multi), [2025, 3, 1, 9, 10]);
    assert.strictEqual(p.messages[4].kind, 'photo');
    assert.strictEqual(p.messages[5].kind, 'emoticon');
    assert.deepStrictEqual(hm(p.messages[6]), [2025, 3, 1, 23, 58]);
    assert.deepStrictEqual(hm(p.messages[7]), [2025, 3, 2, 0, 1]);
    assert.strictEqual(p.messages[8].kind, 'call');
    assert.strictEqual(p.messages[8].callSec, 83);
    assert.strictEqual(p.messages[9].kind, 'deleted');
  });

  await test('옛 안드로이드 (2019년 3월 1일 오전 9:05)', () => {
    const p = K.parse(sample('android-old.txt'));
    assert.strictEqual(p.messages.length, 2);
    assert.deepStrictEqual(hm(p.messages[1]), [2019, 3, 1, 12, 15]);
  });

  await test('아이폰: 오전 12시, 오후 12시, 24시간 표기', () => {
    const p = K.parse(sample('ios.txt'));
    assert.strictEqual(p.messages.length, 3);
    assert.deepStrictEqual(hm(p.messages[0]).slice(3), [0, 5]);
    assert.deepStrictEqual(hm(p.messages[1]).slice(3), [12, 5]);
    assert.deepStrictEqual(hm(p.messages[2]).slice(3), [15, 30]);
  });

  await test('PC: 날짜 줄, 여러 줄, 나갔습니다', () => {
    const p = K.parse(sample('pc.txt'));
    assert.strictEqual(p.format, 'PC');
    assert.strictEqual(p.messages.length, 3);
    assert.strictEqual(p.messages[1].text, '응 굿모닝\n두 줄짜리 메시지');
    assert.deepStrictEqual(hm(p.messages[2]), [2025, 3, 2, 13, 10]);
  });

  await test('맥 CSV: 따옴표 안 쉼표, 줄바꿈', () => {
    const p = K.parse(sample('mac.csv'));
    assert.strictEqual(p.format, 'Mac (CSV)');
    assert.strictEqual(p.messages.length, 2);
    assert.strictEqual(p.messages[0].text, '안녕, 잘 잤어?');
    assert.strictEqual(p.messages[1].text, '응 "완전" 꿀잠\n잤어');
  });

  await test('파일 바이트: BOM, zip(압축/무압축)', async () => {
    const text = sample('android.txt');
    const bom = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(text)]);
    assert.strictEqual(K.parse(await K.readChatBytes(new Uint8Array(bom))).messages.length, 10);
    for (const method of [0, 8]) {
      const zip = makeZip('KakaoTalkChats.txt', Buffer.from(text), method);
      const got = await K.readChatBytes(new Uint8Array(zip));
      assert.strictEqual(got, text);
    }
  });

  await test('분석: 예시 대화 전체가 돌아가요', () => {
    const p = K.parse(K.makeSample());
    const r = K.analyze(p, '민준', '서연');
    assert.ok(r.total > 1000);
    const [m, s] = r.people;
    assert.ok(m.apology > s.apology, '예시에서는 민준이 더 많이 사과해요');
    assert.ok(r.fights.length > 3);
    assert.ok(r.patterns.length > 10);
    assert.ok(r.sessionTypes.some((t) => t.id === 'fight'));
    assert.ok(r.topics.length > 5);
    r.patterns.forEach((pt) => assert.ok(/요\.?$/.test(pt.sentence), pt.sentence));
  });

  await test('분석: 사과 → 받아줌 패턴', () => {
    const lines = ['2025년 3월 1일 토요일'];
    for (let i = 0; i < 4; i++) {
      lines.push(`2025. 3. ${i + 1}. 오후 8:00, 현수 : 미안해 내가 잘못했어`);
      lines.push(`2025. 3. ${i + 1}. 오후 8:03, 지우 : 괜찮아 알았어`);
    }
    const r = K.analyze(K.parse(lines.join('\n')), '현수', '지우');
    const pt = r.patterns.find((x) => x.trigger === 'apology' && x.from === '현수');
    assert.ok(pt);
    assert.strictEqual(pt.n, 4);
    assert.strictEqual(pt.keyRate, 1);
    assert.strictEqual(pt.delay, 3 * 60000);
    assert.strictEqual(pt.sentence, '현수가 사과하면, 지우는 보통 3분 만에 답했고, 100%는 괜찮다고 받아 줬어요');
    assert.strictEqual(r.people[0].apology, 4);
  });

  await test('재판: 방어, 공감, 오해 신호', () => {
    const L = (d, t, w, m) => `2025. 3. ${d}. 오후 ${t}, ${w} : ${m}`;
    const lines = [
      L(1, '8:00', '지우', '나 오늘 진짜 서운했어'),
      L(1, '8:01', '현수', '내가 뭘 했다고 그래'),
      L(1, '8:02', '지우', '너는 맨날 이런 식이야 짜증나'),
      L(1, '8:03', '현수', '미안해 그랬구나'),
      L(2, '8:00', '현수', '오늘 좀 서운했어'),
      L(2, '8:02', '지우', '그랬구나 미안해'),
      L(3, '8:00', '현수', '너 오늘 좀 늦었네'),
      L(3, '8:01', '지우', '늦은 게 뭐 어때서 화났어?'),
      L(3, '8:02', '현수', '그런 뜻 아니야 걱정돼서 그랬어')
    ];
    const c = K.analyze(K.parse(lines.join('\n')), '지우', '현수').court;
    const [ji, hy] = c.people;
    assert.strictEqual(hy.charges.find((x) => x.kind === 'defend').count, 1, '현수가 "내가 뭘"로 방어');
    assert.strictEqual(ji.charges.find((x) => x.kind === 'blame').count, 1, '지우가 "맨날"로 단정');
    assert.ok(ji.merits.find((x) => x.kind === 'ack').count >= 1, '지우가 "그랬구나"로 공감');
    assert.strictEqual(c.misreadCount, 1);
    assert.strictEqual(c.misreads[0].misreadBy, '지우');
    assert.ok(/과실 비율|무죄|쌍방/.test(c.overall), c.overall);
  });

  await test('조사', () => {
    assert.strictEqual(K.josa('민준', '이', '가'), '민준이');
    assert.strictEqual(K.josa('서아', '이', '가'), '서아가');
    assert.strictEqual(K.josa('Tom', '은', '는'), 'Tom(은)는');
  });

  await test('index.html 이 src 와 같아요 (node build.js 를 돌렸는지)', () => {
    const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
    for (const f of ['core.js', 'ui.js', 'style.css']) {
      assert.ok(html.includes(fs.readFileSync(path.join(__dirname, '..', 'src', f), 'utf8').trim()), f + ' 가 index.html 에 반영되지 않았어요');
    }
  });

  console.log(`\n${passed}개 통과`);
})();

// 테스트용 최소 zip 만들기
function makeZip(name, data, method) {
  const nameBuf = Buffer.from(name);
  const body = method === 8 ? zlib.deflateRawSync(data) : data;
  const crc = zlib.crc32 ? zlib.crc32(data) : 0;
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
  local.writeUInt16LE(method, 8); local.writeUInt32LE(crc >>> 0, 14);
  local.writeUInt32LE(body.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(nameBuf.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8);
  central.writeUInt16LE(method, 10); central.writeUInt32LE(crc >>> 0, 16);
  central.writeUInt32LE(body.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(nameBuf.length, 28);
  central.writeUInt32LE(0, 42);
  const cdOffset = local.length + nameBuf.length + body.length;
  const cd = Buffer.concat([central, nameBuf]);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(cdOffset, 16);
  return Buffer.concat([local, nameBuf, body, cd, end]);
}
