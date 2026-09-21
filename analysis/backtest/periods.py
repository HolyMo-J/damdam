import pandas as pd


# 가격제한폭이 ±30%로 넓어진 날. 그 전 데이터는 시장 구조가 달라 백테스트에서 제외한다
BACKTEST_START = "2015-06-15"


def load_daily_csv(path, start=BACKTEST_START):
    df = pd.read_csv(path)
    df = df[df["timestamp"].str[:10] >= start]
    df["timestamp"] = pd.to_datetime(df["timestamp"])
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
