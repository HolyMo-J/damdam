# 진행중 / 다음 할 일

항상 최신 상태로 덮어쓴다. 작업을 시작하거나 다음 할 일을 정할 때 먼저 이 문서를 읽는다. 완료된 작업 이력은 docs/done.md 참고.

## 확인 필요
- 시간 청산 자동 매도는 아직 모의 실행만 확인했고, 사용자가 실제 실전 전환(.env에 DAMDAM_LIVE_ORDERS=true 추가) 전에 며칠 더 모의 실행 로그를 지켜보는 걸 권장
- 호가 단위(tick size) 불일치 시 자동 보정이 없음. 실전 전환 후 등록/수정이 400으로 거부되면 로그의 tickSize/nearestPrices를 보고 대응 필요

## 다음 할 일 (2단계: 과열 급락 반등 전략 백테스트)
- Python + pandas 분석 환경 구성 (analysis 폴더에 venv, requirements.txt)
- 권리락/배당락/액면분할을 API로 걸러낼 수 있는지 확인 (docs/strategy.md "확인 필요")
- 현재 국내 주식 매매 수수료율 확인
- 여러 종목(대형주/중형주 후보군)으로 캔들 데이터 수집 (`export-candles` 프로필, `CandleHistoryExporter`)
- 진입 조건(하락폭, 거래량 배수 등 구체적 숫자), 청산 조건을 코드로 구현해 백테스트
- 코스피200 보유 수익률과 비교, 기간을 앞뒤로 나눠 검증 (docs/strategy.md 2단계 항목 참고)
