import pandas as pd


def load_daily_csv(path):
    df = pd.read_csv(path, parse_dates=["timestamp"])
    return df.sort_values("timestamp").reset_index(drop=True)


# 종목마다 자기 데이터 기간 안에서 시간순 3분할 (docs/strategy.md 백테스트 탐색 방법론, 2026-09-20 확정)
def split_periods(df, tune_ratio=0.5, confirm_ratio=0.3):
    n = len(df)
    tune_end = int(n * tune_ratio)
    confirm_end = int(n * (tune_ratio + confirm_ratio))
    return {
        "tune": df.iloc[:tune_end],
        "confirm": df.iloc[tune_end:confirm_end],
        "final": df.iloc[confirm_end:],
    }
