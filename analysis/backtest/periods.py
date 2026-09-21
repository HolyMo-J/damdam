import pandas as pd


# 가격제한폭이 ±30%로 넓어진 날. 그 전 데이터는 시장 구조가 달라 백테스트에서 제외한다
BACKTEST_START = "2015-06-15"

# 30종목 공통 달력 경계 (2026-09-21 결정). 종목별 비율로 나누면 상장이 늦은 종목의 탐색 구간이
# 다른 종목의 확인/최종 구간과 달력상 겹쳐 봉인이 새기 때문에 모든 종목에 같은 날짜를 쓴다.
# 값은 2,767봉을 가진 24종목의 50/30/20% 경계와 같다. 상장이 늦은 종목은 앞 구간이 짧거나 비어도 그대로 둔다
CONFIRM_START = "2021-01-28"
FINAL_START = "2024-06-13"


def _dates(df):
    return df["timestamp"].dt.strftime("%Y-%m-%d")


def load_daily_csv(path, start=BACKTEST_START):
    df = pd.read_csv(path)
    df = df[df["timestamp"].str[:10] >= start].copy()
    df["timestamp"] = pd.to_datetime(df["timestamp"])
    df = df.sort_values("timestamp").reset_index(drop=True)
    assert not df["timestamp"].duplicated().any(), f"중복 날짜가 있음: {path}"
    return df


# 날짜 기준 3분할 (docs/strategy.md 백테스트 탐색 방법론). 날짜 범위와 봉 수를 보는 리포트용이다
def split_periods(df):
    d = _dates(df)
    return {
        "tune": df[d < CONFIRM_START],
        "confirm": df[(d >= CONFIRM_START) & (d < FINAL_START)],
        "final": df[d >= FINAL_START],
    }


# 백테스트 엔진이 쓰는 로더. 탐색과 확인 구간까지만 돌려주고 최종 검증 구간 행은 즉시 버린다.
# 파일은 통째로 읽히지만 호출자에게는 최종 구간이 전달되지 않는다
def load_search_data(path):
    df = load_daily_csv(path)
    return df[_dates(df) < FINAL_START].reset_index(drop=True)
