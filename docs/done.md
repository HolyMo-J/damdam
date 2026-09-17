# 완료한 것

완료된 작업 이력. 스테이지나 기능 단위로 정리한다. 진행중이거나 다음에 할 일은 docs/todo.md 참고.

## 1단계: 조회와 청산 봇
- 프로젝트 폴더 구조(bot, analysis, records, docs)와 보안 설정(.claude/settings.json, .gitignore, .env.example) 구성
- 깃허브 저장소(HolyMo-J/damdam) 연결, main 브랜치 push
- Java 21, Python, Git 설치 확인
- 토스증권 Open API 키 발급
- `bot/`에 Spring Boot 4.1.1 + Java 21 Gradle 프로젝트 초기화 (web, websocket 의존성)
- 토큰 관리 서비스 (`TokenService`): `/oauth2/token` 호출로 액세스 토큰 발급, `bot/data/token.json`에 저장해 재사용, 소유자만 읽기/쓰기 가능하게 권한 제한
- 계좌 조회 (`AccountService`, `AccountQueryRunner`): `/api/v1/accounts` 호출로 계좌 목록과 accountSeq 조회, 계좌번호는 뒷 4자리만 노출
- `.env` 파일을 Spring이 직접 읽도록 설정 (외부 라이브러리 없이 `spring.config.import` 사용)
- 조회 스크립트는 `query` 프로필일 때만 실행되게 분리해서, 기본 빌드나 테스트에서 실제 API가 자동 호출되지 않게 함
- `./gradlew build` 통과 확인 (실제 API 호출 없이)
- 허용 IP 등록, 계좌 조회 스크립트 실제 실행 확인 (토큰 발급, accountSeq 조회 성공)
- 보유 종목 조회 (`HoldingsService`, `HoldingsQueryRunner`): `/api/v1/holdings` 호출로 평가금액, 손익, 종목별 수량과 손익률 조회
- 계좌 목록을 프로세스 내에서 캐싱해서 중복 조회로 인한 429(호출 제한 초과) 방지
- 개발 PC 파워쉘 콘솔 한글 깨짐 수정 (콘솔 UTF-8 인코딩을 프로필에 등록, 실행 정책을 RemoteSigned로 변경)
- 체결 감지 웹소켓 클라이언트 (`orderevent` 패키지): `wss://openapi-ws.tossinvest.com/ws/v1` 연결, `personal:order` 구독, 60초 PING 유지, 끊기면 지수 백오프로 재연결 후 `GET /api/v1/orders?status=OPEN`으로 재동기화
- 진행중 주문 조회 (`orders` 패키지, `OrderService`): 웹소켓 재동기화용
- 가짜 체결 이벤트 JSON으로 웹소켓 메시지 처리 로직 단위 테스트 (`OrderEventWebSocketHandlerTest`, 실제 API 호출 없음)
- 웹소켓 주문 이벤트 수신을 백그라운드로 띄우고 끄는 스크립트 (`bot/scripts/start-listen.ps1`, `stop-listen.ps1`), 실제 실행 확인 완료
- 개발 PC 한글 깨짐 추가 수정: PowerShell 스크립트 파일을 BOM 있는 UTF-8로 저장 (BOM 없으면 옛 한글 코드페이지로 오인식), `Get-Content`로 로그 볼 때는 `-Encoding UTF8` 필요
- 시간 기준 청산 알림 (`liquidation` 패키지, v0): 최대 보유 5거래일(docs/strategy.md) 도달 종목을 로그로 알림. 자동 매도는 하지 않음
  - 보유 시작 시점은 최근 30일 주문 기록을 FIFO로 리플레이해서, 포지션이 0이었다가 다시 쌓인 지점의 첫 매수 체결일로 판단 (동일 종목 반복 매매 대응)
  - 30일 조회 범위 안에서 매수 기록을 못 찾으면 "그보다 오래 보유 중이라 기준을 이미 넘었을 가능성이 높음"으로 알림
  - 거래일은 월~금으로만 계산 (v0: 공휴일 미반영)
  - 평일 장 시작 직후(09:05 KST) 자동 실행 (`TimeExitScheduler`, listen 프로필), `query` 프로필로 즉시 확인도 가능
  - 실제 계좌로 실행 확인: 보유 3종목 중 1종목은 정확한 경과일, 2종목은 30일 초과로 알림 정상 동작
  - FIFO 판단 로직과 거래일 계산은 가짜 데이터로 단위 테스트 (`PositionEntryResolverTest`, `TradingDayCalculatorTest`)
- 매매 기록 CSV 저장 (`records` 패키지, `TradeRecordWriter`): 웹소켓으로 감지된 실제 체결(FILL, PARTIAL_FILL)만 `records/manual_trades.csv`에 한 줄씩 기록. 계좌 식별값 없음
  - 항목: 시각, 주문ID, 종목, 매수매도구분, 이벤트, 체결수량, 평균체결가, 체결금액, 수수료, 세금, 통화, 주문유형, 상태
  - 전략 신호나 청산 사유 등 아직 없는 개념의 컬럼은 만들지 않음. docs/records.md의 나머지 기록 종류(신호, 가상계좌, 실행상태)는 해당 기능이 생기는 단계에서 추가
  - 가짜 체결 이벤트로 CSV 한 줄이 정확히 남는지 단위 테스트 확인
- 시간 청산 자동 매도 (`liquidation`, `orders` 패키지): 5거래일 기준 도달 시 실제로 시장가 매도 주문을 낸다
  - 기본값은 모의 실행(`damdam.orders.live-mode=false`). 실전 전환은 사용자가 `.env`에 `DAMDAM_LIVE_ORDERS=true`를 직접 추가해야만 켜짐
  - 관리 대상은 "이 기능을 처음 실행한 시각 이후에 새로 산 종목"만 (`ManagedScopeGate`). 그 전부터 갖고 있던 종목(사용자 요청으로 SLDP, SPCX, IRE 포함)은 추적 여부와 상관없이 알림만 남기고 자동 매도하지 않음
  - 1회 주문 금액 한도: 국내 10만원, 해외 $71 (약 10만원, 환율 1400원 기준). 초과 시 거부하고 알림만
  - 하루 최대 자동 매도 10회 (버그 폭주 방지용 안전장치, 평소엔 걸리지 않을 여유), 연속 손실 3회 시 자동 매도 정지 (`AutoSellGuard`, `bot/data/auto_sell_guard.json`에 상태 저장)
  - 주문 생성 API(`POST /api/v1/orders`) 실패는 예외로 앱을 죽이지 않고 로그로 남기고 다음 주기에 재시도
  - 모의 실행으로 실제 계좌 전체 플로우 확인 완료 (기존 보유 종목 전부 정상적으로 제외됨)
  - 모의 주문 동작과 안전장치(하루 횟수, 연속 손실)는 가짜 데이터로 단위 테스트 (`OrderPlacementServiceTest`, `AutoSellGuardTest`)
  - ATR 기반 익절/손절(조건주문 OCO)은 과거 시세(캔들) 데이터 연동이 필요해서 별도 작업으로 남겨둠
