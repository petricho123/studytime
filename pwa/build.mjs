import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const source = new URL('../app/src/main/assets/', import.meta.url);
const web = new URL('./', import.meta.url);
const output = process.argv[2] || 'dist';
const files = new Map();

for (const name of ['index.html', 'core.js', 'app.js', 'study.js', 'style.css']) {
  files.set(name, await readFile(new URL(name, source), 'utf8'));
}
for (const name of ['study-browser.js', 'web.css', 'manifest.webmanifest', 'icon.svg']) {
  files.set(name, await readFile(new URL(name, web), 'utf8'));
}

for (const name of ['icon-192.png', 'icon-512.png']) {
  files.set(name, await readFile(new URL(name, web)));
}

const html = files.get('index.html')
  .replace('<link rel="stylesheet" href="style.css">', '<link rel="manifest" href="./manifest.webmanifest"><link rel="icon" href="./icon.svg"><link rel="apple-touch-icon" href="./icon-192.png"><meta name="theme-color" content="#f7fbff"><link rel="stylesheet" href="style.css"><link rel="stylesheet" href="web.css">')
  .replace('<script src="study.js"', '<script src="study-browser.js" defer></script><script src="study.js"')
  .replace('앱 안의 메뉴 이동·화면 잠금은 계속 기록<br>홈 화면·다른 앱으로 이동하면 자동 일시정지', '다른 창으로 전환해도 계속 기록돼요. 측정을 끝내려면 종료를 누르세요.')
  .replace(/<h2 class="section-title">공부시간과 알림<\/h2><button id="notification-settings"[^]*?<h2 class="section-title">백업과 복원/, '<h2 class="section-title">백업과 복원')
  .replace(/<p class="help">플래너는 날짜별로[^]*?<\/p>/, '<p class="help">기록은 이 웹 앱에 저장돼요. 안드로이드 앱에서 저장한 JSON 백업을 가져올 수 있어요.</p>')
  .replace('작심삼일 · 공부의 시간 2.1.0', '작심삼일 · 공부의 시간')
  .replace('<button id="close-desk-clock" type="button">닫기</button>', '<div class="desk-clock-controls"><button id="desk-clock-fullscreen" type="button">전체 화면</button><button id="close-desk-clock" type="button">닫기</button></div>');
files.set('index.html', html);
files.set('study.js', files.get('study.js')
  .replace("q('#notification-settings').onclick=()=>bridge('openSettings','notification');q('#battery-settings').onclick=()=>bridge('openSettings','battery');", ''));

const hash = createHash('sha256');
for (const [name, content] of files) hash.update(name).update(content);
const worker = await readFile(new URL('sw.js', web), 'utf8');
hash.update(worker);
files.set('sw.js', worker.replace('__CACHE_VERSION__', hash.digest('hex').slice(0, 16))
  .replace('__APP_FILES__', JSON.stringify(['./', ...Array.from(files.keys(), name => './' + name)])));

await mkdir(output, { recursive: true });
await Promise.all(Array.from(files, ([name, content]) => writeFile(join(output, name), content)));
