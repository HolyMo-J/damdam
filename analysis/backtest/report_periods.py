# 30종목 각각의 탐색/확인/최종검증 구간 날짜 범위와 봉 수를 출력한다 (데이터 볼륨 점검용)
# 실행: analysis/.venv/Scripts/python.exe -m backtest.report_periods  (analysis 폴더에서)
import glob
import os

import pandas as pd

from backtest.periods import load_daily_csv, split_periods

if __name__ == "__main__":
    files = sorted(glob.glob("data/*_daily.csv"))
    rows = []
    for f in files:
        df = load_daily_csv(f)
        segments = split_periods(df)
        row = {"stock": os.path.basename(f).replace("_daily.csv", "")}
        for name, seg in segments.items():
            if len(seg) == 0:
                row[f"{name}_rows"] = 0
                row[f"{name}_range"] = "-"
                continue
            row[f"{name}_rows"] = len(seg)
            row[f"{name}_range"] = f"{seg['timestamp'].min().date()} ~ {seg['timestamp'].max().date()}"
        rows.append(row)

    pd.set_option("display.max_rows", None)
    pd.set_option("display.width", 200)
    print(pd.DataFrame(rows).to_string(index=False))
