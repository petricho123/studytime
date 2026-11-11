'use strict';

(() => {
  const key = 'three-day-study-web-v1';
  const empty = { records: [], active: null, state: 'STOPPED' };
  const owner = crypto.randomUUID();
  let wakeLock;
  let wakePending = false;
  let clockOpen = false;
  const time = new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  const midnight = day => new Date(day + 'T00:00:00');
  const nextDay = date => new Date(date.getFullYear(), date.getMonth(), date.getDate() + 1);
  const parse = raw => raw === null ? empty : JSON.parse(raw);
  const read = () => parse(localStorage.getItem(key));
  const save = data => localStorage.setItem(key, JSON.stringify(data));
  const report = error => StudyUI.message(error.message);

  function total(records, from, until) {
    let sum = 0;
    let covered = +from;
    for (const { start, end } of records) {
      const left = Math.max(start, covered);
      const right = Math.min(end, +until);
      if (right > left) {
        sum += right - left;
        covered = right;
      }
    }
    return sum;
  }

  function dayRecords(records, day) {
    const startOfDay = midnight(day);
    const endOfDay = +nextDay(startOfDay);
    return records.flatMap(record => {
      const start = Math.max(record.start, +startOfDay);
      const end = Math.min(record.end, endOfDay);
      if (end <= start) return [];
      const endLabel = record.live && end < endOfDay ? '공부 중' : end === endOfDay ? '24:00' : time.format(end);
      return [{ ...record, label: time.format(start) + ' – ' + endLabel, elapsed: end - start }];
    }).reverse();
  }

  function snapshot(date, month) {
    const now = Date.now();
    const today = Planner.dateKey();
    let data;
    try { data = read(); }
    catch { data = { ...empty, loadFailed: true }; }
    const records = [...data.records];
    if (data.active) records.push({ id: 'live', start: data.active.start, end: now, live: true });
    records.sort((a, b) => a.start - b.start);
    const monthStart = midnight(month + '-01');
    const monthEnd = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 1);
    const days = [];
    for (let day = monthStart; day < monthEnd; day = nextDay(day)) {
      days.push(total(records, day, nextDay(day)));
    }
    const dayTotal = day => total(records, midnight(day), nextDay(midnight(day)));
    StudyUI.receive({
      today, date, month, state: data.state, days, loadFailed: data.loadFailed,
      todayTotal: dayTotal(today), selectedTotal: dayTotal(date), monthTotal: total(records, monthStart, monthEnd),
      todayRecords: dayRecords(records, today), selectedRecords: dayRecords(records, date)
    });
  }

  function finish(data, state, now) {
    const start = data.active?.start;
    const records = start < now ? [...data.records, { id: crypto.randomUUID(), start, end: now }] : data.records;
    return { records, active: null, state };
  }

  function editable() {
    const data = read();
    if (data.active) throw Error('측정을 일시정지한 후 수정·가져오기를 해 주세요.');
    return data;
  }

  function validateRecord(start, end) {
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end <= start || end > Date.now()) {
      throw Error('공부 기록의 시작·종료 시각을 확인해 주세요.');
    }
  }

  function importData(text) {
    const previous = editable();
    const input = JSON.parse(text);
    const full = input.type === 'three-day-study-backup';
    const study = full ? input.study : input;
    if ((full && input.version !== 1) || (study.version != null && study.version !== 1) ||
        !Array.isArray(study.records) || study.records.length > 100000) {
      throw Error('지원하지 않는 공부 기록 파일입니다.');
    }
    if (full) PlannerUI.validate(input.planner);
    const incoming = study.records.map(({ start, end }) => {
      validateRecord(start, end);
      return { id: crypto.randomUUID(), start, end };
    });
    save({ ...previous, records: full ? incoming : [...previous.records, ...incoming] });
    if (full && !PlannerUI.restore(input.planner)) {
      save(previous);
      throw Error('플래너를 복원하지 못했어요.');
    }
    StudyUI.imported();
  }

  function exportData(planner) {
    const data = finish(read(), 'MANUAL_PAUSED', Date.now());
    const backup = { type: 'three-day-study-backup', version: 1, planner: JSON.parse(planner),
      study: { version: 1, state: data.state, records: data.records } };
    const url = URL.createObjectURL(new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'three-day-study-' + Planner.dateKey() + '.json';
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  const picker = document.createElement('input');
  picker.type = 'file';
  picker.accept = '.json,application/json';
  picker.hidden = true;
  document.body.append(picker);
  picker.addEventListener('change', async () => {
    const file = picker.files[0];
    picker.value = '';
    if (!file) return;
    try {
      if (file.size > 4 * 1024 * 1024) throw Error('4MB 이하의 백업 파일만 지원합니다.');
      StudyUI.prepareImport(JSON.parse(await file.text()));
    } catch (error) { report(error); }
  });

  async function keepScreenOn() {
    if (!clockOpen || document.hidden || wakeLock || wakePending || !navigator.wakeLock) return;
    wakePending = true;
    try {
      const lock = await navigator.wakeLock.request('screen');
      if (!clockOpen || document.hidden) {
        await lock.release();
        return;
      }
      wakeLock = lock;
      lock.addEventListener('release', () => {
        if (wakeLock === lock) wakeLock = undefined;
      }, { once: true });
    } catch {
      // The clock can still render when the system declines the wake lock.
    } finally { wakePending = false; }
  }

  function setDeskClockMode(enabled) {
    clockOpen = enabled;
    if (enabled) {
      keepScreenOn();
    } else if (wakeLock) {
      const lock = wakeLock;
      wakeLock = undefined;
      lock.release().catch(() => {});
    }
  }

  const dialog = document.querySelector('#desk-clock');
  const fullscreen = document.querySelector('#desk-clock-fullscreen');
  fullscreen.hidden = !document.documentElement.requestFullscreen;
  fullscreen.addEventListener('click', async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await document.documentElement.requestFullscreen();
    } catch { fullscreen.focus(); }
  });
  document.addEventListener('fullscreenchange', () => {
    fullscreen.textContent = document.fullscreenElement ? '전체 화면 종료' : '전체 화면';
  });
  dialog.addEventListener('close', () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  });

  const safe = operation => (...args) => {
    try { return operation(...args); }
    catch (error) { report(error); }
  };
  window.StudyBridge = {
    snapshot(date, month) {
      // The shared UI may request the first snapshot before it finishes loading.
      queueMicrotask(() => window.StudyUI && snapshot(date, month));
    },
    action: safe(action => {
      const data = read();
      if (action === 'start') {
        if (!data.active) save({ ...data, active: { start: Date.now(), owner }, state: 'RUNNING' });
      } else {
        save(finish(data, action === 'pause' ? 'MANUAL_PAUSED' : 'STOPPED', Date.now()));
      }
      StudyUI.request();
    }),
    editRecord(id, start, end) {
      try {
        const data = editable();
        validateRecord(start, end);
        if (id && !data.records.some(record => record.id === id)) throw Error('기록을 찾을 수 없어요.');
        const replacement = { id: id || crypto.randomUUID(), start, end };
        save({ ...data, records: [...data.records.filter(record => record.id !== id), replacement] });
        StudyUI.recordSaved();
      } catch (error) { StudyUI.recordError(error.message); }
    },
    deleteRecord: safe(id => {
      const data = editable();
      save({ ...data, records: data.records.filter(record => record.id !== id) });
      StudyUI.recordSaved();
    }),
    chooseImport: safe(() => { editable(); picker.click(); }),
    importData: safe(importData),
    exportData: safe(exportData),
    setDeskClockMode
  };

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
      keepScreenOn();
      StudyUI.request();
    }
  });
  window.addEventListener('storage', event => {
    if (event.key === key) StudyUI.request();
  });
  window.addEventListener('pagehide', safe(() => {
    const data = read();
    if (data.active?.owner === owner) save(finish(data, 'MANUAL_PAUSED', Date.now()));
  }));
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(() => {});
})();
