import pandas as pd

from backtest.detect_events import DATA_DIR, STEP_THRESHOLD, detect_all
from backtest.periods import BACKTEST_START

# 단계별로 몇 건이 잡히는지 봐서 임계값을 정하기 위한 리포트. 실행: analysis 폴더에서 python -m backtest.report_events
pd.set_option("display.width", 200)

events = detect_all(threshold=0.001)
events["abs_step"] = events["step"].abs()
print(f"임계값별 이벤트 건수 ({BACKTEST_START} 이후만):")
recent = events[events["date"] >= BACKTEST_START]
for t in (0.003, 0.005, 0.01, 0.02, 0.05, 0.2):
    print(f"  |비율 변화| > {t:.1%}: {(recent['abs_step'] > t).sum()}건")

print(f"\n{BACKTEST_START} 이후 |비율 변화| > 0.5% 전체 목록:")
print(recent[recent["abs_step"] > 0.005].to_string(index=False))

out = DATA_DIR / "corporate_events.csv"
events[(events["abs_step"] > STEP_THRESHOLD) & (events["date"] >= BACKTEST_START)].drop(columns="abs_step").to_csv(
    out, index=False
)
print(f"\n{STEP_THRESHOLD:.1%} 초과 이벤트를 {out.name}에 저장 ({BACKTEST_START} 이후)")
