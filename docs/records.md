# 매매 기록 문서

## 기본 원칙
- 형식: CSV (엑셀과 파이썬 분석용). 분석 결과 요약만 md로 별도 작성
- 모든 기록에 전략 버전 번호를 남긴다
- 기록 파일에는 계좌 식별값을 넣지 않는다
- 기록 폴더는 .gitignore에 포함한다

## 기록 파일
1. 신호 기록: 조건에 걸린 모든 종목과 이후 며칠 가격. 전략 자체의 성질 확인용 (저녁 일괄 원장은 대기 신호만 `signals` CSV에 남긴다. 판정 상태와 근거 값까지 담는 `SignalScan` 전체 행의 기록은 아직 없고 구현 (3)에서 정한다)
2. 가상 계좌 기록: 전략별 총 노출 한도(가상 50만원) 안에서 실제로 샀을 거래만 (동시 보유 종목 수 한도는 없음). 소액 실전 성과 예상용
   - 3단계 가상매매(docs/strategy.md 참고)는 전략 A, B를 동시에 검증하므로 신호 기록과 가상 계좌 기록 모두 전략별로 파일을 분리한다. 직접 매매 기록과도 분리한다 (같은 파일에 strategy_name 컬럼으로만 구분하지 않는다). 저녁 일괄 방식의 실제 파일 목록과 열은 아래 하위 항목
   - 저녁 일괄 가상매매(`PaperLedger`)의 실제 파일 (2026-09-30, 제안 폴더 `records/paper/`(git 제외), 실제 경로는 (3)이 원장에 넘기며 정한다. 슬리피지나 전략 버전을 바꿔 돌리는 민감도 실험은 다른 폴더를 써야 한다): 전략별 `trades_{전략}.csv`(청산 완료 거래), `skips_{전략}.csv`(진입 못 한 신호와 사유, 봉이 안 와 포기한 신호는 `NO_BAR`), `signals_{전략}.csv`(대기 신호의 순위, 신호일 ATR, 신호일 종가. 슬리피지만 바꿔 다시 돌릴 입력), `abandoned_{전략}.csv`(봉이 끝내 오지 않아 포기한 보유 포지션. 거래가 아니라 청산되지 않은 포지션이고 통계에는 마지막 종가로 청산한 것으로 포함한다, docs/strategy.md "가상매매 통과 기준"), 전략 공용 `bars.csv`(정산에 쓴 일봉 OHLCV)와 `runs.csv`(실행 이력). 상태 JSON(제안 경로 `bot/data/paper/state_{전략}.json`)은 기록이 아니라 봇의 작동 데이터이고, 같은 폴더에 직전 정산 완료 상태 사본 `.bak`과 슬리피지와 전략 버전을 기록한 `ledger_config.json`이 생긴다
   - 열 (앞쪽 열이 중복 판정 키): `trades` = strategy, symbol, signal_date(키 여기까지), entry_date, exit_date, rank, quantity, entry_price, exit_price, exit_reason(TAKE_PROFIT, STOP_LOSS, TIME_EXIT), net_profit, net_return, gap_exit, exit_on_entry_day, entry_clamped_to_high, sell_failed, entry_gap_rate, execution_mode, entry_price_source, slippage_rate, strategy_version. `skips` = strategy, symbol, signal_date(키), reason(ZERO_VOLUME, INVALID_EXIT_PRICES, PRICE_EXCEEDS_LIMIT, EXPOSURE_LIMIT, WEEKLY_HALT, ALREADY_HELD, NO_BAR), rank, settle_date, atr, signal_close, execution_mode, slippage_rate, strategy_version. `signals` = strategy, symbol, signal_date(키), rank, atr, signal_close. `abandoned` = strategy, symbol, signal_date(키), entry_date, abandoned_on, rank, quantity, entry_price, take_profit_price, stop_trigger, atr, bars_processed, last_bar_date, execution_mode, slippage_rate, strategy_version. `bars` = date, symbol(키), open, high, low, close, volume. `runs` = run_at, strategy, settle_date(키), status(SETTLED, INCOMPLETE, 그 밖에 호출자가 `recordRun`으로 남기는 값, 예: SIGNAL_GAP), new_trades, skipped_signals, positions_after, unsettled_symbols, abandoned_symbols, note
   - 규칙: 같은 키에 같은 행이면 무시하고 다른 내용이면 예외다 (잠정치가 확정치로 바뀌었거나 설정을 바꿔 재실행한 경우 조용히 넘기지 않는다). `runs.csv`만은 실행 시각이 키라서 재실행하면 줄이 늘어나므로 분석은 정산일별 마지막 `SETTLED` 행을 쓴다. 한 신호가 `trades`와 `skips`에 동시에 있으면 안 되므로(크래시와 결측 복귀가 겹칠 때만 가능) 분석에서 두 파일의 (전략, 종목, 신호일) 교집합이 비었는지 점검한다
   - 사람이 열 때 유의: 엑셀로 열어 저장하면 종목코드 앞자리 0이 사라지고(예: 앞자리가 0인 6자리 코드가 5자리로 줄어듦) 한국어 Windows의 "CSV(쉼표로 분리)" 저장은 CP949라서 다음 기록이 실패한다. 엑셀로 열지 말고 읽기 전용 복사본으로 열어 본다. 실패는 안전한 방향이다 (헤더 검증으로 조용한 손상은 막는다)
3. 직접 매매 기록: 사용자가 손으로 한 거래. 같은 형식의 별도 파일 (`records/manual_trades.csv`, `TradeRecordWriter`). strategy_name, exit_reason 컬럼이 있지만 4단계 소액 실전 전까지는 계속 수동 매매만 담긴다 (2026-09-28 D묶음 항목, 그 파일은 삭제됨. 지금의 review-tasks.md 8번과 다른 항목이다)
4. 실행 상태 기록: 봇이 주기적으로 남기는 동작 로그. 기록 공백과 신호 없음을 구분 (저녁 일괄 가상매매는 위 `runs.csv`)
5. 실측 조사 로그: 3단계 실측 조회 러너(`paper-probe` 프로필)가 남기는 텍스트 로그 (`records/probe/paper-probe.log`, git 제외). 순위 집계 시각, 일봉과 기관 매매동향의 값과 갱신 시각을 회차마다 이어 붙이고 회차 간 변화를 기록한다. 매매 기록이 아니라 명세로 알 수 없는 사실(확정 시각 등)을 실측하는 용도다
6. 신호 조회 로그: 신호 조회 러너(`signal-scan` 프로필)가 남기는 텍스트 로그 (`records/probe/signal-scan.log`, git 제외). 신호일마다 신호가 난 종목과 근거 값, 판정 불가 건수를 이어 붙인다. 가상 체결 기록이 아니다
7. 수동 신호 관찰 기록: `records/observations.csv` (git 제외). 구현 (3)이 생기기 전에 신호 조회 결과를 한 줄에 신호 1건으로 옮기고 다음 날 시가를 채워 넣는 임시 기록이다. 구현 (3)이 만드는 `signals` CSV로 대체될 예정이다

## 기록 항목
- 시각, 종목, 매수 매도 구분, 수량, 전략 버전
- 신호 기록에 남길 값 (3단계 신호 판정 `SignalScan`이 이미 담고 있는 것, 2026-09-29): 신호일, 판정을 돌린 시각(`scannedAt`), 종목의 거래대금 순위, 전략별 판정 상태(`EVALUATED`, `INSUFFICIENT_CANDLES`, `DATA_ERROR`, `FETCH_FAILED`)와 신호 여부, 신호 근거 값(전략 B는 종가와 구름 상단과 거래량, 전략 A는 순매수 합계와 거래량 합계와 비율), 전략 A의 신호일 매매동향 기록의 갱신 시각(`institutionRecordUpdatedAt`). 판정 상태를 함께 남겨야 "신호 없음"과 "조회 실패로 판정 못 함"을 나중에 구분할 수 있고, 판정 시각과 갱신 시각은 잠정치로 신호가 났는지 사후에 확인하는 근거가 된다
- 신호 가격, 주문 가정 가격, 체결 가격, 체결 여부
- 가상 계좌 기록의 실행 방식 컬럼 (2026-09-30, 두 방식의 기록을 나중에 비교하려는 것): `execution_mode`(`daily_batch` 또는 `realtime`), `entry_price_source`(`open_plus_slippage` 또는 `ask1`), `slippage_rate`, 신호일, 진입 봉 날짜. 저녁 일괄은 정산에 쓴 일봉(시가, 고가, 저가, 종가, 거래량)을 `bars.csv`에, 대기 신호의 순위와 신호일 ATR과 신호일 종가를 `signals_{전략}.csv`에 남기고 거래 행과는 (종목, 날짜) 키로 연결해서, 같은 Java 코드를 슬리피지만 바꿔 전체 흐름으로 다시 실행할 수 있게 한다 (기록만으로 계산을 따로 재현하는 방식은 노출 한도 판정이 연쇄로 바뀌어 쓰지 않는다). 저녁 일괄 거래 행에는 플래그와 보정값도 남긴다: `gap_exit`(익절 또는 손절이 갭으로 체결), `exit_on_entry_day`(진입일 봉에서 바로 청산), `entry_clamped_to_high`(진입가를 고가로 제한), `price_adjusted`와 보정 비율(수정주가 보정, **아직 없음**: 보정을 구현할 때 열을 추가한다), 신호일 종가 대비 시가 갭 비율, 진입 못 한 신호의 사유(거래량 0, 진입가 - ATR이 0 이하, 총 노출 한도, 주간 손실 한도 정지, 1주 가격 초과, 이미 보유, 봉이 끝내 안 옴). PC가 꺼져 하루를 건너뛴 경우는 신호가 없는 날과 구분해 "공백"으로 남긴다 (실행 상태 기록)
- 청산 사유: 익절, 시간 청산, 손절, 매도 실패 등
- 수수료와 세금 반영 손익
- 대안 청산 규칙별 가정 손익은 봇에 실시간으로 구현하지 않고, 체결가와 시각 등 필요한 값만 남겨 나중에 일봉 데이터로 다시 계산한다 (docs/strategy.md의 대안 규칙 비교 기록 참고, 2026-09-28)
- 시장 상황: 코스피 지수 흐름, 거래 시각 (장 초반 여부 분석용)
- 종목 그룹: 대형주, 중형주

## 가상 체결 규칙
- 즉시 매매 가정: 현재가가 아니라 호가 기준. 매수는 매도 1호가, 매도는 매수 1호가
- 지정가 가정: 가격에 닿기만 하면 미체결. 그 가격을 넘어서야 체결로 인정
- 손절은 감시가(트리거)와 실제 주문가(감시가보다 한 호가 낮은 지정가, `AtrOcoPricing` 참고)가 별개다. 갭 하락으로 시가가 이 주문가보다 낮게 열리면, 실제 지정가 매도 주문은 시가에 체결되지 않고 가격이 주문가까지 다시 올라와야 체결된다. 저녁 일괄 방식에서는 그날 고가가 주문가 이상이면 주문가에 체결이고, 고가가 주문가 미만일 때만 하한가/VI/거래정지와 같이 매도 실패로 기록한다. 실패한 포지션은 다음 날 손절을 다시 판정하지 않고 5번째 봉 시가 시간 청산에 고정한다 (2026-09-30 독립 검토를 검증해 고침, 근거와 "트리거 후 지정가 유효기간은 확인 못 함"은 docs/strategy.md "실행 방식" 참고. 이전 2026-09-28, docs/todo.md "손절 체결 가정의 차이" 항목. 2단계 백테스트는 이 구분 없이 갭 하락 손절을 시가 체결로 단순화해서 계산하므로, 심한 갭 하락 구간에서는 실제보다 손절 보호를 낙관적으로 가정하고 있다는 한계가 있다)
- 하한가, VI 발동, 거래정지 중에는 매도 실패로 기록
- 가상 체결과 실제 체결의 차이는 실주문 전환 후 비교해 백테스트 신뢰도 판단에 사용
- 실행 방식별 근사 (2026-09-30, docs/strategy.md 3단계 "실행 방식" 참고): 위 규칙은 실시간 방식(`realtime`) 기준이다. 저녁 일괄 방식(`daily_batch`)은 호가를 알 수 없어 다음 거래일 시가 x (1 + 슬리피지 0.2%)를 매수가로, 5번째 봉 시가 x (1 - 0.2%)를 시간 청산가로 근사한다. 익절은 고가가 익절가를 엄격히 넘어야 체결이고(갭 상승이어도 익절가에 체결로 계산), 같은 날 익절가와 손절 감시가를 둘 다 지나면 손절 우선이며, 손절은 주문가에 체결하고 갭 하락일에 고가가 주문가 미만이면 위 규칙대로 매도 실패로 기록한다. 5번째 봉에는 익절과 손절을 판정하지 않고 시가에서 시간 청산한다. 하한가와 VI는 일봉으로 판별하기 어려워 거래량 0인 정지일만 매도 실패로 본다
