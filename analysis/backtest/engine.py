"""과열 급락 반등 전략 백테스트 엔진 (docs/strategy.md 백테스트 탐색 방법론).

흐름: 종목별로 신호 후보를 뽑고, 각 후보의 거래 결과를 독립적으로 계산한 뒤,
날짜순으로 동시 보유 상한을 적용해 실제로 체결되는 거래만 고른다.
최종 검증 구간은 로더(periods.load_search_data)가 아예 돌려주지 않으므로 이 모듈은 볼 수 없다.
"""
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

from backtest.periods import CONFIRM_START, load_search_data

DATA_DIR = Path(__file__).resolve().parents[1] / "data"

COMMISSION = 0.00015  # 국내 매수, 매도 각각 (GET /api/v1/commissions 실제 조회값)
# 국내 매도세(증권거래세 + 농어촌특별세 합계) 연혁. 매매 체결일 기준이다. 2019-05-30 이후는 코스피와 코스닥 합계가 같다.
# 확인한 출처: 2019-05-30 인하(0.30%에서 0.25%)는 금융투자협회와 언론 보도, 2021년 이후는 두 곳의 정리가 일치.
# 확인하지 못한 것: 2015-06-15부터 2019-05-29까지 0.30%가 유지됐다는 점 (2019년 인하 직전이 0.30%라는 것만 확인)
SELL_TAX_SCHEDULE = [
    ("2015-06-15", 0.0030),
    ("2019-05-30", 0.0025),
    ("2021-01-01", 0.0023),
    ("2023-01-01", 0.0020),
    ("2024-01-01", 0.0018),
    ("2025-01-01", 0.0015),
    ("2026-01-01", 0.0020),
]
MAX_HOLD = 5  # 진입 봉 + 5번째 봉의 시가에 시간 청산 (봇의 5거래일 도달 시 09:05 매도에 대응)
VOL_WINDOW = 20
ATR_WINDOW = 14
RET_DAYS = 3
MAX_POSITIONS = 5

TUNE, CONFIRM = 0, 1


@dataclass(frozen=True)
class Params:
    drop: float  # 0.08이면 최근 3거래일 등락률 -8% 이하
    vmult: float  # 신호일 거래량 / 직전 20거래일 평균 거래량
    tp: float  # 0.03이면 진입가 +3% 익절
    stop_atr: float  # 진입가 - 이 배수 x ATR14 손절
    slippage: float = 0.0  # 진입과 손절, 시간 청산 매도에만 불리하게 적용 (익절 지정가 체결에는 적용 안 함)


@dataclass
class SymbolData:
    symbol: str
    dates: np.ndarray
    open: np.ndarray
    high: np.ndarray
    low: np.ndarray
    volume: np.ndarray
    ret3: np.ndarray
    vol_ratio: np.ndarray
    atr: np.ndarray
    eligible: np.ndarray  # 가격 조건과 무관한 신호 자격 (워밍업, 거래정지, 조정 이벤트)
    period: np.ndarray  # 봉별 구간 (TUNE 또는 CONFIRM)
    last_idx: dict  # 구간별 마지막 봉 인덱스


def sell_tax_rate(date):
    """date(YYYY-MM-DD) 시점에 적용되는 매도세율. 표의 시작일이 date 이하인 마지막 행을 쓴다."""
    rate = SELL_TAX_SCHEDULE[0][1]
    for start, r in SELL_TAX_SCHEDULE:
        if start <= date:
            rate = r
    return rate


def build_symbol(symbol, df, event_dates=()):
    """일봉 DataFrame에서 지표를 계산한다. 모든 지표는 그 봉과 그 이전 데이터만 쓴다."""
    n = len(df)
    dates = df["timestamp"].dt.strftime("%Y-%m-%d")
    close, high, low = df["close"], df["high"], df["low"]
    volume = df["volume"].astype(float)

    prev_close = close.shift()
    true_range = pd.concat([high - low, (high - prev_close).abs(), (low - prev_close).abs()], axis=1).max(axis=1)
    atr = true_range.rolling(ATR_WINDOW, min_periods=ATR_WINDOW).mean()

    # 거래량 평균은 직전 20봉(신호일 제외)이고, 거래량 0인 날은 평균에서 뺀다. 아래 자격 조건이
    # 신호일 포함 21봉 안에 0인 날이 있으면 신호를 제외하므로 평균은 항상 양수 거래량 20개로 계산된다
    vol_avg = volume.where(volume > 0).shift().rolling(VOL_WINDOW, min_periods=VOL_WINDOW).mean()
    window = VOL_WINDOW + 1  # t-20 .. t
    zero_recent = (volume == 0).astype(int).rolling(window, min_periods=1).sum() > 0
    event_recent = dates.isin(set(event_dates)).astype(int).rolling(window, min_periods=1).sum() > 0

    eligible = (
        (np.arange(n) >= VOL_WINDOW)
        & ~zero_recent.to_numpy()
        & ~event_recent.to_numpy()
        & (atr > 0).to_numpy()
        & vol_avg.notna().to_numpy()
    )
    period = np.where((dates < CONFIRM_START).to_numpy(), TUNE, CONFIRM)
    last_idx = {p: int(np.flatnonzero(period == p).max()) for p in (TUNE, CONFIRM) if (period == p).any()}
    return SymbolData(
        symbol=symbol,
        dates=dates.to_numpy(),
        open=df["open"].to_numpy(float),
        high=high.to_numpy(float),
        low=low.to_numpy(float),
        volume=volume.to_numpy(),
        ret3=(close / close.shift(RET_DAYS) - 1).to_numpy(),
        vol_ratio=(volume / vol_avg).to_numpy(),
        atr=atr.to_numpy(),
        eligible=eligible,
        period=period,
        last_idx=last_idx,
    )


def load_universe():
    events = pd.read_csv(DATA_DIR / "corporate_events.csv", dtype={"symbol": str})
    universe = {}
    for path in sorted(DATA_DIR.glob("*_daily.csv")):
        if "unadjusted" in path.name:
            continue
        symbol = path.name.split("_")[0]
        event_dates = events.loc[events["symbol"] == symbol, "date"]
        universe[symbol] = build_symbol(symbol, load_search_data(path), event_dates)
    return universe


def simulate(sd, t, p):
    """신호일 t의 다음 봉 시가에 진입한 거래 하나의 결과. 진입 불가이거나 구간을 넘으면 None.

    구간을 넘는지는 경로나 결과와 무관하게 진입 봉 + 5가 구간 마지막 봉을 넘는지로만 판단한다.
    (익절이나 손절로 일찍 끝날 거래만 남기면 빨리 끝난 거래로 표본이 치우친다)
    """
    e = t + 1
    if e >= len(sd.dates) or sd.volume[e] == 0 or sd.high[e] == sd.low[e]:
        return None  # 다음 봉이 거래정지이거나 가격이 한 점에 고정돼 진입할 수 없음
    period = int(sd.period[e])
    limit = sd.last_idx[period]
    if e + MAX_HOLD > limit:
        return None

    entry = sd.open[e] * (1 + p.slippage)
    stop = entry - p.stop_atr * sd.atr[t]
    tp = entry * (1 + p.tp)
    sell_slip = 1 - p.slippage
    both_touch = False
    tp_boundary = False  # 고가가 익절가와 정확히 같아 엄격한 기준으로는 체결로 보지 않은 봉이 있었음

    exit_idx = exit_price = reason = None
    for k in range(e, e + MAX_HOLD + 1):
        if k == e + MAX_HOLD:
            # 시간 청산일이 거래정지이면 거래가 재개된 첫 봉의 시가에 판다
            j = k
            while j <= limit and sd.volume[j] == 0:
                j += 1
            if j > limit:
                return None
            exit_idx, exit_price, reason = j, sd.open[j] * sell_slip, "time"
            break
        if sd.volume[k] == 0:
            continue  # 정지 봉은 가격이 고정돼 있어 손절과 익절이 닿을 수 없다
        o, h, l = sd.open[k], sd.high[k], sd.low[k]
        if k > e:  # 진입 봉의 시가는 진입가 자체라 갭 검사를 하지 않는다
            if o <= stop:
                exit_idx, exit_price, reason = k, o * sell_slip, "stop_gap"
                break
            if o > tp:
                exit_idx, exit_price, reason = k, o, "tp_gap"
                break
        touched_stop = l <= stop
        touched_tp = h > tp
        if h == tp:
            tp_boundary = True
        if touched_stop:  # 같은 날 둘 다 닿으면 보수적으로 손절이 먼저
            both_touch = touched_tp
            exit_idx, exit_price, reason = k, stop * sell_slip, "stop"
            break
        if touched_tp:
            exit_idx, exit_price, reason = k, tp, "tp"
            break

    exit_date = sd.dates[exit_idx]
    net = exit_price * (1 - COMMISSION - sell_tax_rate(exit_date)) / (entry * (1 + COMMISSION)) - 1
    return {
        "symbol": sd.symbol,
        "signal_date": sd.dates[t],
        "entry_date": sd.dates[e],
        "exit_date": exit_date,
        "period": period,
        "ret3": sd.ret3[t],
        "entry": entry,
        "exit": exit_price,
        "reason": reason,
        "both_touch": both_touch,
        "tp_boundary": tp_boundary,
        "net": net,
        "r": (exit_price - entry) / (entry - stop),
    }


def signal_indices(sd, p):
    mask = sd.eligible & (sd.ret3 <= -p.drop) & (sd.vol_ratio >= p.vmult)
    return np.flatnonzero(mask)


def run(universe, p, max_positions=MAX_POSITIONS):
    """신호 후보를 날짜순으로 훑어 동시 보유 상한을 적용하고 실제로 체결되는 거래를 돌려준다.

    같은 종목은 이전 거래의 청산일 이후에만 다시 진입한다. 청산일과 같은 날의 재진입도 막는다(보수적).
    상한을 넘는 날에는 그날 신호 중 3일 하락폭이 큰 순으로 채운다.
    """
    candidates = []
    for sd in universe.values():
        for t in signal_indices(sd, p):
            trade = simulate(sd, t, p)
            if trade is not None:
                candidates.append(trade)
    candidates.sort(key=lambda tr: (tr["entry_date"], tr["ret3"]))

    accepted, exit_dates, last_exit = [], [], {}
    for tr in candidates:
        d = tr["entry_date"]
        if last_exit.get(tr["symbol"], "") >= d:
            continue
        if sum(1 for x in exit_dates if x >= d) >= max_positions:
            continue
        accepted.append(tr)
        exit_dates.append(tr["exit_date"])
        last_exit[tr["symbol"]] = tr["exit_date"]
    return pd.DataFrame(accepted)


def baseline(universe, p):
    """대조군: 가격과 거래량 조건 없이 자격을 갖춘 모든 날에 진입하고 같은 청산 규칙을 쓴 결과.

    신호 조건이 만드는 엣지와 종목군 자체의 상승 드리프트를 구분하기 위한 것이다.
    """
    trades = []
    for sd in universe.values():
        for t in np.flatnonzero(sd.eligible):
            trade = simulate(sd, t, p)
            if trade is not None:
                trades.append(trade)
    return pd.DataFrame(trades)


def summarize(trades):
    n = len(trades)
    if n == 0:
        return {"n": 0}
    per_day = trades["entry_date"].value_counts()
    return {
        "n": n,
        "mean_net": trades["net"].mean(),
        "median_net": trades["net"].median(),
        "win_rate": (trades["net"] > 0).mean(),
        "mean_r": trades["r"].mean(),
        "both_touch_share": trades["both_touch"].mean(),
        "tp_boundary_share": trades["tp_boundary"].mean(),
        "reasons": trades["reason"].value_counts().to_dict(),
        "distinct_entry_days": len(per_day),
        "top5_day_share": per_day.head(5).sum() / n,
    }
