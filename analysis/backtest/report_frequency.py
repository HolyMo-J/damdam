# 신호 빈도 점검: 탐색 구간 거래 건수만 세고, 확인과 최종 구간 건수는 탐색 구간에서 비례로 추정한다.
# 확인 구간은 "찾은 숫자를 적용만" 하는 구간이고 최종 구간은 봉인이라, 튜닝 단계에서는 둘 다 세지 않는다.
# 실행: analysis/.venv/Scripts/python.exe -m backtest.report_frequency  (analysis 폴더에서)
import itertools

import pandas as pd

from backtest.engine import CONFIRM, TUNE, Params, load_universe, run
from backtest.periods import FINAL_START

# 공통 달력 경계에서 24종목의 봉 수 (report_periods 출력, 탐색 1383 / 확인 830 / 최종 554)
CONFIRM_PER_TUNE = 830 / 1383
FINAL_PER_TUNE = 554 / 1383
MARGIN = 1.5  # 최종/확인 구간 100건 기준에 곱하는 여유 계수
REQUIRED = 100 * MARGIN

DROPS = [0.03, 0.04, 0.05, 0.06, 0.08]
VMULTS = [1.0, 1.5, 2, 3]
MID_EXIT = dict(tp=0.03, stop_atr=1.5)  # 빈도는 주로 진입 조건이 정하므로 청산은 중간값으로 고정

if __name__ == "__main__":
    universe = load_universe()

    # 읽은 데이터를 먼저 확인한다: 종목 수, 날짜 범위, 봉 수, 최종 구간이 섞이지 않았는지
    firsts = [sd.dates[0] for sd in universe.values()]
    lasts = [sd.dates[-1] for sd in universe.values()]
    counts = [len(sd.dates) for sd in universe.values()]
    print(f"종목 {len(universe)}개, 첫 날짜 {min(firsts)} ~ {max(firsts)}, 마지막 날짜 {min(lasts)} ~ {max(lasts)}")
    print(f"봉 수 최소 {min(counts)} / 최대 {max(counts)}, 최종 구간 시작일 {FINAL_START} 이후 봉 없음: {max(lasts) < FINAL_START}")
    assert max(lasts) < FINAL_START

    rows = []
    for drop, vmult in itertools.product(DROPS, VMULTS):
        trades = run(universe, Params(drop=drop, vmult=vmult, **MID_EXIT))
        tune = trades[trades["period"] == TUNE] if len(trades) else trades
        n = len(tune)
        rows.append({
            "drop": f"-{drop:.0%}",
            "vmult": vmult,
            "tune_n": n,
            "est_confirm": round(n * CONFIRM_PER_TUNE),
            "est_final": round(n * FINAL_PER_TUNE),
            "ok": n * CONFIRM_PER_TUNE >= REQUIRED and n * FINAL_PER_TUNE >= REQUIRED,
            "days": tune["entry_date"].nunique() if n else 0,
            "top5_share": round(tune["entry_date"].value_counts().head(5).sum() / n, 3) if n else 0,
        })
    pd.set_option("display.width", 200)
    print(f"\n청산 {MID_EXIT}, 동시 보유 상한 5, 추정 건수가 각각 {REQUIRED:.0f} 이상이면 ok")
    print(pd.DataFrame(rows).to_string(index=False))
