# 완료한 것

완료된 작업 이력. 스테이지나 기능 단위로 정리한다. 진행중이거나 다음에 할 일은 docs/todo.md 참고.

## 1단계: 조회와 청산 봇

### 프로젝트 초기 설정
- 프로젝트 폴더 구조(bot, analysis, records, docs)와 보안 설정(.claude/settings.json, .gitignore, .env.example) 구성
- 깃허브 저장소(HolyMo-J/damdam) 연결, main 브랜치 push
- Java 21, Python, Git 설치 확인
- 토스증권 Open API 키 발급
- `bot/`에 Spring Boot 4.1.1 + Java 21 Gradle 프로젝트 초기화 (web, websocket 의존성)
- `.env` 파일을 Spring이 직접 읽도록 설정 (외부 라이브러리 없이 `spring.config.import` 사용)
- 조회 스크립트는 `query` 프로필일 때만 실행되게 분리해서, 기본 빌드나 테스트에서 실제 API가 자동 호출되지 않게 함
- `./gradlew build` 통과 확인 (실제 API 호출 없이)

### 토큰 관리
- 토큰 관리 서비스 (`TokenService`): `/oauth2/token` 호출로 액세스 토큰 발급, `bot/data/token.json`에 저장해 재사용, 소유자만 읽기/쓰기 가능하게 권한 제한

### 계좌·보유 종목 조회
- 계좌 조회 (`AccountService`, `AccountQueryRunner`): `/api/v1/accounts` 호출로 계좌 목록과 accountSeq 조회, 계좌번호는 뒷 4자리만 노출
- 허용 IP 등록, 계좌 조회 스크립트 실제 실행 확인 (토큰 발급, accountSeq 조회 성공)
- 보유 종목 조회 (`HoldingsService`, `HoldingsQueryRunner`): `/api/v1/holdings` 호출로 평가금액, 손익, 종목별 수량과 손익률 조회
- 계좌 목록을 프로세스 내에서 캐싱해서 중복 조회로 인한 429(호출 제한 초과) 방지

### 개발 환경 문제 해결
- 개발 PC 파워쉘 콘솔 한글 깨짐 수정 (콘솔 UTF-8 인코딩을 프로필에 등록, 실행 정책을 RemoteSigned로 변경)
- PowerShell 스크립트 파일을 BOM 있는 UTF-8로 저장 (BOM 없으면 옛 한글 코드페이지로 오인식), `Get-Content`로 로그 볼 때는 `-Encoding UTF8` 필요

### 체결 감지 웹소켓
- 체결 감지 웹소켓 클라이언트 (`orderevent` 패키지): `wss://openapi-ws.tossinvest.com/ws/v1` 연결, `personal:order` 구독, 60초 PING 유지, 끊기면 지수 백오프로 재연결 후 `GET /api/v1/orders?status=OPEN`으로 재동기화
- 진행중 주문 조회 (`orders` 패키지, `OrderService`): 웹소켓 재동기화용
- 가짜 체결 이벤트 JSON으로 웹소켓 메시지 처리 로직 단위 테스트 (`OrderEventWebSocketHandlerTest`, 실제 API 호출 없음)
- 웹소켓 주문 이벤트 수신을 백그라운드로 띄우고 끄는 스크립트 (`bot/scripts/start-listen.ps1`, `stop-listen.ps1`)
- 실제 소액 매수(CPNG 1주)로 실제 주문 이벤트 수신 확인: PENDING → REPLACING/REPLACED(호가 재조정) → FILL까지 정상 수신

### 매매 기록 CSV
- 매매 기록 CSV 저장 (`records` 패키지, `TradeRecordWriter`): 웹소켓으로 감지된 실제 체결(FILL, PARTIAL_FILL)만 `records/manual_trades.csv`에 한 줄씩 기록. 계좌 식별값 없음
  - 항목: 시각, 주문ID, 종목, 매수매도구분, 이벤트, 체결수량, 평균체결가, 체결금액, 수수료, 세금, 통화, 주문유형, 상태
  - 전략 신호나 청산 사유 등 아직 없는 개념의 컬럼은 만들지 않음. docs/records.md의 나머지 기록 종류(신호, 가상계좌, 실행상태)는 해당 기능이 생기는 단계에서 추가
  - 가짜 체결 이벤트로 CSV 한 줄이 정확히 남는지 단위 테스트 확인

### 시간 기준 청산 (알림 + 자동 매도)
- 시간 기준 청산 알림 (`liquidation` 패키지, v0): 최대 보유 5거래일(docs/strategy.md) 도달 종목을 로그로 알림
  - 보유 시작 시점은 최근 30일 주문 기록을 FIFO로 리플레이해서, 포지션이 0이었다가 다시 쌓인 지점의 첫 매수 체결일로 판단 (동일 종목 반복 매매 대응)
  - 30일 조회 범위 안에서 매수 기록을 못 찾으면 "그보다 오래 보유 중이라 기준을 이미 넘었을 가능성이 높음"으로 알림
  - 거래일은 월~금으로만 계산 (v0: 공휴일 미반영)
  - 평일 장 시작 직후(09:05 KST) 자동 실행 (`TimeExitScheduler`, listen 프로필), `query` 프로필로 즉시 확인도 가능
  - FIFO 판단 로직과 거래일 계산은 가짜 데이터로 단위 테스트 (`PositionEntryResolverTest`, `TradingDayCalculatorTest`)
- 시간 청산 자동 매도 (`liquidation`, `orders` 패키지): 5거래일 기준 도달 시 실제로 시장가 매도 주문을 낸다
  - 기본값은 모의 실행(`damdam.orders.live-mode=false`). 실전 전환은 사용자가 `.env`에 `DAMDAM_LIVE_ORDERS=true`를 직접 추가해야만 켜짐
  - 관리 대상은 "이 기능을 처음 실행한 시각 이후에 새로 산 종목"만 (`ManagedScopeGate`). 그 전부터 갖고 있던 종목(사용자 요청으로 SLDP, SPCX, IRE 포함)은 추적 여부와 상관없이 알림만 남기고 자동 매도하지 않음
  - 1회 주문 금액 한도: 국내 10만원, 해외 $71 (약 10만원, 환율 1400원 기준). 초과 시 거부하고 알림만
  - 하루 최대 자동 매도 10회 (버그 폭주 방지용 안전장치, 평소엔 걸리지 않을 여유), 연속 손실 3회 시 자동 매도 정지 (`AutoSellGuard`, `bot/data/auto_sell_guard.json`에 상태 저장)
  - 주문 생성 API(`POST /api/v1/orders`) 실패는 예외로 앱을 죽이지 않고 로그로 남기고 다음 주기에 재시도
  - 모의 주문 동작과 안전장치(하루 횟수, 연속 손실)는 가짜 데이터로 단위 테스트 (`OrderPlacementServiceTest`, `AutoSellGuardTest`)
  - 실제 계좌로 실행 확인: 알림은 보유 3종목 중 1종목은 정확한 경과일, 2종목은 30일 초과로 정상 동작. 자동 매도는 모의 실행으로 전체 플로우 확인(기존 보유 종목 전부 정상적으로 제외됨)

### ATR 기반 익절/손절 (조건주문 OCO)
- 과거 캔들 데이터 조회와 ATR 계산 (`market` 패키지)
  - `MarketDataService`: `GET /api/v1/candles`(일봉, `interval=1d`) 호출로 최신순 캔들 목록 조회. 계좌 구분 없는 공개 시세 데이터라 계좌 헤더 불필요
  - `AverageTrueRange`: 진짜 변동폭(True Range)의 단순 평균으로 14거래일 ATR 계산 (`AtrService`). 순수 계산 로직이라 API 호출 없음
  - `AtrQueryRunner`(`query` 프로필): 보유 종목별로 현재가, 14일 ATR, 익절/손절 기준가를 로그로 출력. 실제 계좌로 실행해 값 확인 완료
- 조건주문 OCO 등록/수정/취소/조회 (`conditionalorder` 패키지)
  - `AtrOcoPricing`: 체결가 ± ATR로 익절/손절 감시가 계산. KR은 정수로, US는 공식 문서에 명시된 $1 기준 소수 자리수로 맞춤. 손절 지정가는 확실한 체결을 위해 감시가보다 한 스텝(KR 1원, US 0.0001~0.01) 낮게 건다
    - 호가 단위(tick size) 전체 구간표는 토스 공식 문서에 없어서(예시만 있음) 추측으로 보정하지 않음. 실제 호가 단위와 안 맞으면 API가 400으로 거부하며 올바른 tickSize/nearestPrices를 알려주므로, 그 로그를 보고 필요하면 손으로 조정
  - `ConditionalOrderService`: `POST /api/v1/conditional-orders`(등록), `POST .../modify`(수정, 종목당 1개 제한이라 추가 매수 시 새로 만들지 않고 기존 걸 수정), `DELETE .../{id}`(취소), `GET /api/v1/conditional-orders`(종목별 진행 중인 조건주문 조회)
  - 시간 청산 자동 매도와 동일하게 `damdam.orders.live-mode`로 실전/모의 전환. 기본값은 모의 실행
  - 개발 세션 중에는 CLAUDE.md 규칙에 따라 등록/수정/취소 API를 직접 호출하지 않음. 모의 실행(가짜 RestClient로 실제 API 미호출 확인) 단위 테스트와 가격 계산 단위 테스트로만 검증 (`ConditionalOrderServiceTest`, `AtrOcoPricingTest`)
- 매수 체결 시 자동 등록/수정 연결 (`AtrOcoManagementService`)
  - 기준가는 평단가(`averagePurchasePrice`). 처음 매수가만 쓰면 추가 매수를 못 반영하고, 최근 체결가만 쓰면 이전 매수분과 무관해져서 이상하다는 사용자 지적으로, 전체 보유분의 실제 손익분기점을 반영하는 평단가로 결정
  - 웹소켓에서 BUY 체결(FILL/PARTIAL_FILL) 감지 시: 현재 보유 수량 + 평단가 + 14일 ATR로 익절/손절가 계산 → 기존 조건주문 있으면 수정, 없으면 새로 등록
  - 조건주문 만료일은 시간 청산 기준(5거래일)을 넉넉히 덮도록 등록일+10일로 설정 (그 전에 시간 청산이 대신 정리함)
  - 시간 청산 자동 매도가 나가면 직후 남은 OCO를 취소 (`HoldingTimeExitService.attemptAutoSell`에서 호출)
  - OCO 갱신/정리 중 오류(네트워크, ATR 계산 실패 등)는 예외로 웹소켓 처리를 막지 않고 로그로만 남김
  - `OrderEventWebSocketHandlerTest`를 도달 불가능한 주소로 업데이트해서, BUY 체결 처리 경로에 OCO 동기화가 끼어도 여전히 예외 없이 통과하는지 확인
  - 실제 계좌 검증: 조회(GET)는 `query` 프로필로 실행 확인. `listen` 프로필로 실제 계좌에 연결한 상태에서 소액 실매수(CPNG 1주)로 전체 플로우 끝까지 확인 — 웹소켓 FILL 감지(0.4초 내) → 평단가/ATR 계산 → `[모의 조건주문 등록] CPNG 1주 OCO 익절 15.00 / 손절 14.09` 로그까지 에러 없이 정상 동작
