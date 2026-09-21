from pathlib import Path

import pandas as pd

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
# 정수 반올림 노이즈(2015년 이후 대형주는 0.05% 이하)를 넘는 비율 변화만 조정 이벤트로 본다.
# 30종목 실측에서 0.5%~1% 구간에 1건뿐이라 0.5%로 정했다
STEP_THRESHOLD = 0.005


def detect_events(symbol: str, threshold: float = STEP_THRESHOLD) -> pd.DataFrame:
    """원본 종가 / 수정 종가 비율이 하루 사이에 threshold 이상 바뀐 날짜를 찾는다.

    비율이 바뀐 날이 토스가 조정(액면분할, 증자 등)을 반영한 경계일이다.
    """
    adjusted = pd.read_csv(DATA_DIR / f"{symbol}_daily.csv")
    unadjusted = pd.read_csv(DATA_DIR / f"{symbol}_daily_unadjusted.csv")
    for df in (adjusted, unadjusted):
        df["date"] = df["timestamp"].str[:10]

    merged = adjusted.merge(unadjusted, on="date", suffixes=("_adj", "_raw"))
    merged["ratio"] = merged["close_raw"] / merged["close_adj"]
    merged["ratio_prev"] = merged["ratio"].shift()
    merged["step"] = merged["ratio"] / merged["ratio_prev"] - 1
    events = merged[merged["step"].abs() > threshold].copy()
    events["raw_return"] = (merged["close_raw"] / merged["close_raw"].shift() - 1)[events.index]
    events.insert(0, "symbol", symbol)
    return events[["symbol", "date", "ratio_prev", "ratio", "step", "raw_return"]]


def detect_all(threshold: float = STEP_THRESHOLD) -> pd.DataFrame:
    symbols = sorted(p.name.split("_")[0] for p in DATA_DIR.glob("*_daily_unadjusted.csv"))
    frames = [detect_events(s, threshold) for s in symbols]
    return pd.concat(frames, ignore_index=True)
