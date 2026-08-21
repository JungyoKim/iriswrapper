import { Bot, ChatContext } from '@syncate-core/node-iris';
import * as fs from 'fs';
import * as path from 'path';

// ─── 설정 ────────────────────────────────────────────
const IRIS_URL    = process.env.IRIS_URL    || '';
const TARGET_CHAT = process.env.TARGET_CHAT || '';

if (!IRIS_URL || !TARGET_CHAT) {
  console.error('[오류] IRIS_URL과 TARGET_CHAT 환경변수를 설정해주세요.');
  process.exit(1);
}
const CACHE_TTL   = 24 * 60 * 60 * 1000; // 24시간
const EDIT_POLL_MS = 500;
const DEL_POLL_MS  = 3000;

// ─── names.json 수동 이름 매핑 ──────────────────────
const NAMES_FILE = path.join(__dirname, '..', 'names.json');
function loadNames(): Record<string, string> {
  try { return JSON.parse(fs.readFileSync(NAMES_FILE, 'utf8')); } catch { return {}; }
}
let manualNames = loadNames();

// ─── 파일 영속 캐시 ─────────────────────────────────
interface CacheEntry {
  sender: string;
  content: string;
  userId: string;
  timestamp: number;
  imageUrls?: string[];
  fileNames?: string[];
  fileUrl?: string;   // 단순 파일 메시지의 CDN 다운로드 URL
}

const CACHE_FILE = path.join(__dirname, '..', 'msg_cache.json');
const msgCache = new Map<string, CacheEntry>();

function saveCache(): void {
  const cutoff = Date.now() - CACHE_TTL;
  const obj: Record<string, CacheEntry> = {};
  for (const [id, e] of msgCache) {
    if (e.timestamp >= cutoff) obj[id] = e;
  }
  try { fs.writeFileSync(CACHE_FILE, JSON.stringify(obj)); } catch {}
}

function loadCache(): void {
  try {
    const obj: Record<string, CacheEntry> = JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8'));
    const cutoff = Date.now() - CACHE_TTL;
    for (const [id, e] of Object.entries(obj)) {
      if (e.timestamp >= cutoff) msgCache.set(id, e);
    }
    console.log(`[캐시 복원] ${msgCache.size}개 항목 로드`);
  } catch {}
}

loadCache();

// 1시간마다 만료 캐시 정리
setInterval(() => {
  const cutoff = Date.now() - CACHE_TTL;
  for (const [id, e] of msgCache) {
    if (e.timestamp < cutoff) msgCache.delete(id);
  }
  saveCache();
  alertedDeletions.clear();
  alertedEdits.clear();
}, 60 * 60 * 1000);

process.on('SIGTERM', saveCache);
process.on('SIGINT', saveCache);

// ─── 미등록 유저 알림 중복 방지 ─────────────────────
const warnedUsers = new Set<string>();

// ─── 삭제/수정 중복 방지 ─────────────────────────────
const alertedDeletions = new Set<string>();

// ─── 수정 감지 (폴링 백업용) ────────────────────────
const modRevCache = new Map<string, number>();
let editReady = false;
const alertedEdits = new Set<string>();

// ─── 폴링 기준점 ────────────────────────────────────
let lastSeenId = 0;

// ─── attachment에서 이미지 URL 추출 ──────────────────
const IMAGE_CDN = /https?:\/\/[^\s"']+(?:kakaocdn\.net|daumcdn\.net|kakaoenterprise\.com)[^\s"']*/;

function extractImageUrls(attachment: any): string[] | undefined {
  const raw = typeof attachment === 'string' ? (() => { try { return JSON.parse(attachment); } catch { return null; } })() : attachment;
  if (!raw) return undefined;

  const isCDN = (s: any) => typeof s === 'string' && IMAGE_CDN.test(s);
  const IS_IMG = /\.(jpe?g|png|gif|webp|bmp|heic?|heif)([?#]|$)/i;

  // 1) 최상위 url 필드: 이미지면 반환, 파일(pdf 등)이면 이미지 없음으로 종료
  if (isCDN(raw.url)) {
    return IS_IMG.test(raw.url) ? [raw.url] : undefined; // 파일은 extractFileUrl에서 처리
  }

  // 2) 다중 사진: 최상위 imageUrls 배열
  if (Array.isArray(raw.imageUrls)) {
    const urls = raw.imageUrls.filter(isCDN);
    if (urls.length > 0) return urls;
  }

  // 3) 공지형(os[]): t:5(사진) / t:6(동영상) 의 th 썸네일
  if (Array.isArray(raw.os)) {
    const osUrls = raw.os
      .filter((o: any) => (o.t === 5 || o.t === 6) && isCDN(o.th))
      .map((o: any) => o.th);
    if (osUrls.length > 0) return osUrls;
  }

  // 4) 기타(샵검색 등): 재귀 탐색
  const found = new Set<string>();
  function walk(val: any) {
    if (typeof val === 'string') { if (IMAGE_CDN.test(val)) found.add(val); }
    else if (Array.isArray(val)) { val.forEach(walk); }
    else if (val && typeof val === 'object') { Object.values(val).forEach(walk); }
  }
  walk(raw);
  return found.size > 0 ? [...found] : undefined;
}

// ─── 공지 내부 서브타입 레이블 ───────────────────────
function getNoticeSubLabel(att: any): string {
  const types = (att?.os ?? []).map((o: any) => o.t);
  if (types.includes(9))  return '[투표]';
  if (types.includes(12)) return '[퀴즈]';
  if (types.includes(7))  return '[파일]';
  if (types.includes(6))  return '[동영상]';
  if (types.includes(5))  return '[사진]';
  return '';
}

// ─── 단순 파일 메시지 CDN 다운로드 URL 추출 ──────────
function extractFileUrl(att: any): string | undefined {
  const raw = typeof att === 'string' ? (() => { try { return JSON.parse(att); } catch { return null; } })() : att;
  if (!raw) return undefined;
  const IS_IMG = /\.(jpe?g|png|gif|webp|bmp|heic?|heif)([?#]|$)/i;
  const url = raw.url;
  if (typeof url === 'string' && IMAGE_CDN.test(url) && !IS_IMG.test(url)) return url;
  return undefined;
}

// ─── 공지 os[] 파일명 추출 ───────────────────────────
function extractFileNames(att: any): string[] | undefined {
  const raw = typeof att === 'string' ? (() => { try { return JSON.parse(att); } catch { return null; } })() : att;
  const files: string[] = (raw?.os ?? [])
    .filter((item: any) => item.t === 7 && item.tt)
    .map((item: any) => item.tt);
  return files.length > 0 ? files : undefined;
}

// ─── 투표/퀴즈 선택지 추출 ───────────────────────────
function extractVoteOptions(att: any): string[] | null {
  for (const item of att?.os ?? []) {
    // 투표 (t:9): its[].tt
    if (item.t === 9 && Array.isArray(item.its)) {
      return item.its.map((it: any) => it.tt).filter(Boolean);
    }
    // 퀴즈 (t:12): qds[0].its[].tt
    if (item.t === 12 && Array.isArray(item.qds)) {
      return (item.qds[0]?.its ?? []).map((it: any) => it.tt).filter(Boolean);
    }
  }
  // type=14/16398 단순 구조: os[0].its[].tt
  const first = att?.os?.[0];
  if (Array.isArray(first?.its)) {
    return first.its.map((it: any) => it.tt).filter(Boolean);
  }
  return null;
}

// ─── Bot 인스턴스 ────────────────────────────────────
const bot = new Bot('KakaoBot', IRIS_URL);

// ─── 메시지 수신 (WebSocket) ─────────────────────────
bot.on('chat', async (context: ChatContext) => {
  try {
    const roomId = context.room.id.toString();
    if (roomId !== TARGET_CHAT) return;

    const msgId = context.raw?._id?.toString() ?? context.message.getIdAsString();

    // feedType 확인 (삭제/수정 피드)
    if (context.message.isFeedMessage()) {
      const feed = context.message.msg as any;
      const feedType = Number(feed.feedType);

      if (feedType === 14) {
        // 삭제 감지
        const logId = feed.logId?.toString();
        if (logId) {
          console.log(`[WS 삭제 감지] logId=${logId}`);
          handleDeletion(logId);
        }
        return;
      }

      if (feedType === 25) {
        // 수정 감지
        const logId = feed.logId?.toString();
        const targetRev = feed.targetRevision?.toString() ?? '?';
        if (logId) {
          console.log(`[WS 수정 감지] logId=${logId}, rev=${targetRev}`);
          handleEditByLogId(logId, targetRev);
        }
        return;
      }

      return; // 기타 피드 무시
    }

    // 일반 메시지 → 캐시
    const raw = context.raw as any;

    const msgContent = typeof context.message.msg === 'string' ? context.message.msg : '';
    if (!msgContent) return;

    let enrichedContent = msgContent;
    const msgType = Number(raw?.type);

    if (raw?.attachment) {
      try {
        const att = typeof raw.attachment === 'string' ? JSON.parse(raw.attachment) : raw.attachment;

        if (msgType === 24) {
          // 공지 내부 서브타입 레이블 삽입 ([공지] → [공지][투표] 등)
          const subLabel = getNoticeSubLabel(att);
          const prefix = msgContent.match(/^(\[[^\]]+\])/)?.[1] ?? '';
          if (prefix === '[공지]' && subLabel) {
            enrichedContent = `[공지]${subLabel}${msgContent.slice('[공지]'.length)}`;
          }

          // 선택지 추출
          const options = extractVoteOptions(att);
          if (options && options.length > 0) {
            enrichedContent = `${enrichedContent}\n선택지: ${options.join(' | ')}`;
          }

          // DEBUG: its 내부 구조 + 이모티콘 파악
          const osInfo = (att.os ?? []).map((o: any) => ({
            t: o.t,
            keys: Object.keys(o),
            its_sample: Array.isArray(o.its) ? o.its.slice(0, 2) : undefined,
            jct: o.jct,
          }));
          const hasEmot = (att.os ?? []).some((o: any) => o.t === 13 || o.emot || o.emoticon);
          console.log(`[DEBUG type=24] msg=${JSON.stringify(msgContent).slice(0, 60)} hasEmot=${hasEmot} jct=${JSON.stringify(att.jct)?.slice(0, 150)} os=${JSON.stringify(osInfo)}`);
        } else if ([14, 16398].includes(msgType)) {
          // 독립 투표/퀴즈 DEBUG
          console.log(`[DEBUG type=${msgType}] msg=${JSON.stringify(msgContent).slice(0, 60)} att=${JSON.stringify(att).slice(0, 300)}`);

          const options = extractVoteOptions(att);
          if (options && options.length > 0) {
            enrichedContent = `${enrichedContent}\n선택지: ${options.join(' | ')}`;
          }
        }
      } catch {}
    }

    const userId = context.sender.id.toString();

    if (!manualNames[userId] && !warnedUsers.has(userId)) {
      warnedUsers.add(userId);
      console.log(`[새 유저] UID: ${userId} — names.json에 이름을 추가해주세요`);
    }

    // attachment에서 이미지 URL, 파일명, 파일 다운로드 URL 추출
    const imageUrls = raw?.attachment ? extractImageUrls(raw.attachment) : undefined;
    const fileNames = raw?.attachment ? extractFileNames(raw.attachment) : undefined;
    const fileUrl   = raw?.attachment ? extractFileUrl(raw.attachment)   : undefined;

    msgCache.set(msgId, {
      sender: manualNames[userId] || '',
      content: enrichedContent,
      userId,
      timestamp: Date.now(),
      ...(imageUrls ? { imageUrls } : {}),
      ...(fileNames ? { fileNames } : {}),
      ...(fileUrl   ? { fileUrl }   : {}),
    });

    const extras = [
      imageUrls ? `[이미지 ${imageUrls.length}장]` : '',
      fileNames ? `[파일 ${fileNames.length}개]` : '',
    ].filter(Boolean).join(' ');
    console.log(`[캐시] _id=${msgId} ${manualNames[userId] || '(이름없음)'}: ${enrichedContent.slice(0, 40)}${extras ? ` ${extras}` : ''}`);
    saveCache();
  } catch (err: any) {
    console.error('[chat 핸들러 오류]', err.message);
  }
});

// ─── 초기화 ──────────────────────────────────────────
async function init(): Promise<void> {
  // lastSeenId 설정
  try {
    const rows = await bot.api.query(
      `SELECT MAX(_id) as maxId FROM chat_logs WHERE chat_id = ${TARGET_CHAT}`
    );
    lastSeenId = Number(rows[0]?.maxId ?? 0);
    console.log(`[초기화] 기준 _id: ${lastSeenId}`);
  } catch (err: any) {
    console.error('[초기화 오류]', err.message);
  }

  // DB에서 최근 500개 메시지로 캐시 보충
  try {
    const rows = await bot.api.query(
      `SELECT _id, user_id, message, v FROM chat_logs ` +
      `WHERE chat_id = ${TARGET_CHAT} AND message IS NOT NULL AND message != '' ` +
      `ORDER BY _id DESC LIMIT 500`
    );
    let filled = 0;
    for (const row of rows) {
      const id = String(row._id);
      if (msgCache.has(id)) continue;
      if (!row.message) continue;
      const userId = String(row.user_id ?? '');
      msgCache.set(id, {
        sender: manualNames[userId] || '',
        content: row.message,
        userId,
        timestamp: Date.now(),
      });
      filled++;
    }
    if (filled > 0) {
      console.log(`[초기화] DB에서 ${filled}개 메시지 캐시 보충`);
      saveCache();
    }
  } catch (err: any) {
    console.error('[캐시 보충 오류]', err.message);
  }

  // 폴링 시작
  setInterval(checkDeletions, DEL_POLL_MS);
  setInterval(checkEdits, EDIT_POLL_MS);
}

// ─── 삭제 처리 ──────────────────────────────────────
async function handleDeletion(logId: string): Promise<void> {
  if (alertedDeletions.has(logId)) return;
  alertedDeletions.add(logId);

  let originalId: string | null = null;
  let userId: string | null = null;
  let dbContent: string | null = null;
  let imageUrls: string[] = [];
  let fileNames: string[] = [];
  let fileUrl: string | null = null;
  let emoticonInfo: string | null = null;
  let isNotice = false;
  let hasVideo = false;

  try {
    const rows = await bot.api.query(
      `SELECT _id, user_id, message, attachment, type, v FROM chat_logs WHERE id = ${logId} LIMIT 1`
    );
    if (rows[0]) {
      originalId = String(rows[0]._id ?? '');
      userId     = String(rows[0].user_id ?? '');
      dbContent  = rows[0].message ?? null;

      if (rows[0].attachment) {
        try {
          const att = typeof rows[0].attachment === 'string'
            ? JSON.parse(rows[0].attachment) : rows[0].attachment;
          // 이모티콘
          if (att.emoticonItemPath) {
            const match = att.emoticonItemPath.match(/^(\d+)\.emot_(\d+)/);
            emoticonInfo = match
              ? `팩 ${match[1]} #${parseInt(match[2])}`
              : att.emoticonItemPath;
          }
          // 공지/동영상 여부: os[] 배열 분석
          const os: any[] = att.os ?? [];
          isNotice = os.some((o: any) => o.t === 3);
          hasVideo = os.some((o: any) => o.t === 6);
          // 이미지 URL (제네릭)
          imageUrls = extractImageUrls(att) ?? [];
          // 공지 첨부 파일명
          fileNames = extractFileNames(att) ?? [];
          // 단순 파일 메시지 CDN URL
          fileUrl = extractFileUrl(att) ?? null;
        } catch {}
      }
    }
  } catch (err: any) {
    console.error('[원본 조회 오류]', err.message);
  }

  const cached = originalId ? msgCache.get(originalId) : null;
  const sender  = cached?.sender || (userId ? manualNames[userId] : null) || (userId ? `(UID: ${userId})` : '알 수 없음');
  const content = cached?.content ?? dbContent ?? '(내용 불명)';

  // 캐시에 이미지/파일 정보가 있으면 우선 사용
  if (imageUrls.length === 0 && cached?.imageUrls?.length) imageUrls = cached.imageUrls;
  if (fileNames.length === 0 && cached?.fileNames?.length) fileNames = cached.fileNames;
  if (!fileUrl && cached?.fileUrl) fileUrl = cached.fileUrl;

  if (emoticonInfo) {
    const hasText = content && content !== '(내용 불명)';
    const textPart = hasText ? ` (${content})` : '';
    console.log(`[삭제 알림] ${sender}: [이모티콘] ${emoticonInfo}${textPart}`);
    await sendNotification(`🗑️ 삭제된 메시지\n👤 ${sender}: [이모티콘] ${emoticonInfo}${textPart}`);
  } else {
    // content에서 KakaoTalk 자동 태그 제거 → 순수 텍스트
    const cleanText = content.replace(/^(\[(?:공지|사진|투표|퀴즈|파일|동영상)\]\s*)+/g, '').trim();
    const GENERIC_LABEL = /^(사진( \d+장)?|동영상|사진이 등록되었습니다\.|동영상이 등록되었습니다\.)$/;
    const hasRealText = cleanText.length > 0 && !GENERIC_LABEL.test(cleanText);

    // 레이블 조립: [공지] + [사진/동영상] + 순수텍스트
    const labels: string[] = [];
    if (isNotice) labels.push('[공지]');
    if (imageUrls.length > 0) {
      labels.push(hasVideo ? '[동영상]' : `[사진 ${imageUrls.length}장]`);
    }

    const parts: string[] = [];
    let header = labels.join('');
    if (hasRealText) header += header ? ` ${cleanText}` : cleanText;
    parts.push(header || content || '(내용 불명)');

    if (imageUrls.length > 0) {
      const linkLabel = hasVideo ? '미리보기' : '사진';
      const links = imageUrls.map((url, i) => `🔗 ${imageUrls.length > 1 ? `${linkLabel}${i + 1}` : linkLabel}: ${url}`).join('\n');
      parts.push(links);
    }
    if (fileNames.length > 0) {
      parts.push(`📎 파일: ${fileNames.join(', ')}`);
    }
    if (fileUrl) {
      parts.push(`📎 다운로드: ${fileUrl}`);
    }
    const body = parts.join('\n') || '(내용 불명)';
    console.log(`[삭제 알림] ${sender}: ${body.split('\n')[0]}`);
    await sendNotification(`🗑️ 삭제된 메시지\n👤 ${sender}: ${body}`);
  }
}

// ─── 수정 처리 (WebSocket feedType:25) ───────────────
async function handleEditByLogId(logId: string, targetRev: string): Promise<void> {
  let originalId: string | null = null;
  let userId: string | null = null;
  let newContent: string | null = null;

  try {
    const rows = await bot.api.query(
      `SELECT _id, user_id, message, v FROM chat_logs WHERE id = ${logId} LIMIT 1`
    );
    if (rows[0]) {
      originalId = String(rows[0]._id ?? '');
      userId     = String(rows[0].user_id ?? '');
      newContent = rows[0].message ?? '(내용 불명)';
    }
  } catch (err: any) {
    console.error('[원본 조회 오류]', err.message);
  }

  if (!originalId) return;

  const key = `${originalId}:${targetRev}`;
  if (alertedEdits.has(key)) return;
  alertedEdits.add(key);

  const sender = (userId ? manualNames[userId] : null) || (userId ? `(UID: ${userId})` : '알 수 없음');

  const cached = msgCache.get(originalId);
  const oldContent = cached?.content ?? '(이전 내용 불명 — 캐시 없음)';

  if (cached) {
    msgCache.set(originalId, { ...cached, content: newContent ?? '', timestamp: Date.now() });
    saveCache();
  }

  console.log(`[수정 알림] ${sender}: "${oldContent}" → "${newContent}"`);
  await sendNotification(`✏️ 수정된👌👈메시지 🥵\n👤 ${sender}\n이전: ${oldContent}\n이후: ${newContent}`);
}

// ─── 수정 처리 (폴링 modifyRevision) ────────────────
async function handleEdit(msgId: string, row: any): Promise<void> {
  const userId = String(row.user_id ?? '');
  const sender = manualNames[userId] || (userId ? `(UID: ${userId})` : '알 수 없음');

  const newContent = row.message ?? '(내용 불명)';
  const cached = msgCache.get(msgId);
  const oldContent = cached?.content ?? '(이전 내용 불명 — 캐시 없음)';

  if (cached) {
    msgCache.set(msgId, { ...cached, content: newContent, timestamp: Date.now() });
    saveCache();
  }

  console.log(`[수정 알림] ${sender}: "${oldContent}" → "${newContent}"`);
  await sendNotification(`✏️ 수정된👌👈메시지 🥵\n👤 ${sender}\n이전: ${oldContent}\n이후: ${newContent}`);
}

// ─── 삭제 감지 폴링 (백업) ──────────────────────────
async function checkDeletions(): Promise<void> {
  try {
    const rows = await bot.api.query(
      `SELECT _id, message, v FROM chat_logs ` +
      `WHERE chat_id = ${TARGET_CHAT} ` +
      `  AND _id > ${lastSeenId} ` +
      `  AND v LIKE '%SYNCDLMSG%' ` +
      `ORDER BY _id ASC LIMIT 20`
    );

    const maxRows = await bot.api.query(
      `SELECT MAX(_id) as maxId FROM chat_logs WHERE chat_id = ${TARGET_CHAT}`
    );
    const newMax = Number(maxRows[0]?.maxId ?? lastSeenId);
    if (newMax > lastSeenId) lastSeenId = newMax;

    for (const row of rows) {
      const msg = String(row.message ?? '');
      if (!/"feedType"\s*:\s*14/.test(msg)) continue;

      const logIdMatch = msg.match(/"logId"\s*:\s*(\d+)/);
      if (!logIdMatch) continue;

      console.log(`[폴링 삭제 감지] _id=${row._id}, logId=${logIdMatch[1]}`);
      await handleDeletion(logIdMatch[1]);
    }
  } catch (err: any) {
    if (err.code !== 'ECONNREFUSED') console.error('[삭제 폴링 오류]', err.message);
  }
}

// ─── 수정 감지 폴링 (백업) ──────────────────────────
async function checkEdits(): Promise<void> {
  try {
    const rows = await bot.api.query(
      `SELECT _id, user_id, message, v FROM chat_logs ` +
      `WHERE chat_id = ${TARGET_CHAT} ` +
      `ORDER BY _id DESC LIMIT 200`
    );

    for (const row of rows) {
      const id = String(row._id);
      const vStr = typeof row.v === 'string' ? row.v : JSON.stringify(row.v ?? '');
      const modRev = Number(vStr.match(/"modifyRevision"\s*:\s*(\d+)/)?.[1] ?? 0);
      const prevRev = modRevCache.get(id);

      // 웹훅 누락 메시지 캐시 보충
      if (!msgCache.has(id) && row.message) {
        const userId = String(row.user_id ?? '');
        msgCache.set(id, {
          sender: manualNames[userId] || '',
          content: row.message,
          userId,
          timestamp: Date.now(),
        });
      }

      if (editReady && prevRev !== undefined && modRev > prevRev) {
        const key = `${id}:${modRev}`;
        if (!alertedEdits.has(key)) {
          alertedEdits.add(key);
          await handleEdit(id, row);
        }
      }

      modRevCache.set(id, modRev);
    }

    if (modRevCache.size > 2000) {
      const keys = [...modRevCache.keys()];
      keys.slice(0, 500).forEach(k => modRevCache.delete(k));
    }

    editReady = true;
  } catch (err: any) {
    if (err.code !== 'ECONNREFUSED') console.error('[수정 폴링 오류]', err.message);
  }
}

// ─── 알림 전송 ──────────────────────────────────────
async function sendNotification(text: string): Promise<void> {
  try {
    await bot.api.reply(TARGET_CHAT, text);
  } catch (err: any) {
    console.error('[전송 오류]', err.message);
  }
}

// ─── 메인 ────────────────────────────────────────────
async function main(): Promise<void> {
  console.log(`[봇] node-iris WebSocket 모드 시작`);
  console.log(`[봇] Iris: ${IRIS_URL}`);
  console.log(`[봇] 감시 채팅방: ${TARGET_CHAT}`);

  await init();
  await bot.run();
}

main().catch(err => {
  console.error('[치명적 오류]', err);
  process.exit(1);
});
