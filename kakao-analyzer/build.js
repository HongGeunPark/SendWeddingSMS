// src/ 의 파일들을 합쳐서 하나짜리 index.html 을 만들어요.
//   node build.js            → index.html
//   node build.js --fragment out.html → <html>/<head>/<body> 와 CSP 없이 (미리보기 호스팅용)
const fs = require('fs');
const path = require('path');
const src = (f) => fs.readFileSync(path.join(__dirname, 'src', f), 'utf8');

let html = src('page.html')
  .replace('/*CSS*/', () => src('style.css').trim())
  .replace('/*CORE*/', () => src('core.js').trim())
  .replace('/*UI*/', () => src('ui.js').trim());

const i = process.argv.indexOf('--fragment');
if (i >= 0) {
  html = html
    .replace(/<!--CSP-->[\s\S]*?<!--\/CSP-->\n?/, '')
    .replace(/<!doctype html>\n|<html[^>]*>\n|<\/html>\n?|<head>\n|<\/head>\n|<body>\n|<\/body>\n/g, '')
    .replace(/<meta charset="utf-8">\n|<meta name="viewport"[^>]*>\n/g, '');
  fs.writeFileSync(process.argv[i + 1], html);
} else {
  html = html.replace(/<!--\/?CSP-->/g, '');
  fs.writeFileSync(path.join(__dirname, 'index.html'), html);
}
