# KakaoBot

KakaoTalk 채팅방의 삭제/수정 메시지를 감지하여 알림을 보내는 봇입니다.
[Iris](https://kbotdocs.dev/reference/iris/get-started) 서버 기반, [node-iris](https://github.com/SYNCATE-CORE/node-iris) WebSocket 연결을 사용합니다.

## 기능

- **삭제 감지**: 메시지 삭제 시 원본 내용과 발신자 표시
- **수정 감지**: 메시지 수정 시 이전/이후 내용 비교 표시
- **24시간 캐시**: 파일 영속화로 재배포 후에도 캐시 유지
- **자동 재연결**: WebSocket 끊김 시 exponential backoff로 자동 복구
- **이름 매핑**: `names.json` 수동 매핑 + DB friends 조회

## 아키텍처

```
Iris 서버 (Android/redroid)
    │
    ├── WebSocket (ws://) ──→ 실시간 메시지 수신 + 캐시
    ├── REST API (/query) ──→ DB 조회 (원본 메시지, 이름)
    └── REST API (/reply) ──→ 알림 발신
```

### 감지 방식

| 이벤트 | 1차 감지 (실시간) | 2차 감지 (백업) |
|--------|-------------------|-----------------|
| 삭제 | WebSocket feedType:14 | 폴링 SYNCDLMSG (3초) |
| 수정 | WebSocket feedType:25 | 폴링 modifyRevision (0.5초) |

### 캐시 전략

1. **WebSocket 수신**: 모든 메시지 즉시 캐시
2. **폴링 보충**: 0.5초마다 최근 200개 중 캐시 누락분 채움
3. **시작 시 DB 로드**: 최근 500개 미리 캐시
4. **파일 영속화**: `msg_cache.json`에 저장, 재시작 시 복원
5. **TTL**: 24시간 경과 항목 자동 삭제 (1시간 간격 정리)

## 설정

### 환경변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `IRIS_URL` | Iris 서버 주소 (IP:PORT) | — |
| `TARGET_CHAT` | 감시할 채팅방 ID | — |

### 이름 매핑 (`names.json`)

DB friends에 없는 사용자의 이름을 수동으로 매핑합니다:

```json
{
  "233101765": "홍길동",
  "243062824": "김철수"
}
```

## 배포

### Docker (Dokploy)

```bash
# 환경변수 설정
IRIS_URL=<호스트>:<포트>
TARGET_CHAT=<채팅방 ID>
```

GitHub 연결 후 자동 배포됩니다.

### 로컬 실행

```bash
npm install
npm run build
npm start
```

## 유틸리티

### 메시지 내보내기

```bash
# 전체 메시지를 messages.txt로 저장
node export_messages.js

# 메시지 내용으로 검색
node export_messages.js "검색어"
```

## 알림 형식

```
🗑️ 삭제된 메시지
👤 홍길동: 삭제된 원본 내용

✏️ 수정된 메시지
👤 홍길동
이전: 수정 전 내용
이후: 수정 후 내용
```

## 제한사항

- **[공지] 삭제/수정**: 공지판에서 내리는 동작은 chat_logs에 피드가 생성되지 않아 감지 불가
- **삭제된 메시지 복원**: 봇 실행 전에 삭제된 메시지는 DB에서 내용이 이미 지워져 복원 불가
- **수정 이전 내용**: 봇 캐시에 없는 메시지의 수정 전 내용은 표시 불가 (DB에 암호화됨)
