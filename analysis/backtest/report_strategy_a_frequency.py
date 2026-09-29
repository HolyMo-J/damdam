# 전략 A 과거 신호 빈도 세기 (docs/review-tasks.md E묶음 11번의 이어서). 수익률은 계산하지 않고 빈도만 본다.
# 입력: analysis/data/universe100/ 의 _universe.csv, <종목코드>_daily.csv(수정주가 일봉), <종목코드>_investor.csv(기관 순매수 거래량),
#   _investor_summary.csv. 매매동향 수집: bot 폴더에서 .\gradlew.bat bootRun --args="--spring.profiles.active=export-investor"
# 실행: analysis 폴더에서 .venv/Scripts/python.exe -m backtest.report_strategy_a_frequency
#
# 읽는 법(한계): 전략 B 리포트(report_strategy_b_frequency.py)와 같다. 종목군을 "오늘 기준"으로 고정해서 생존 편향(신호를 늘리는 쪽)이 있고,
# 실제 대상 종목군은 신호일 저녁 순위로 만들어져 거래량이 급증한 종목이 들어오는 효과를 고정 종목군은 못 담는다(줄이는 쪽).
# 전략 A는 추가로 기관 순매수 거래량(KRX와 NXT 통합, 명세)과 일봉 거래량이 같은 범위의 거래를 센 값인지 확인하지 못했다 (docs/strategy.md "전략 A").
# 범위가 다르면 3% 비율이 그만큼 어긋나므로, 아래 데이터 확인에서 순매수 절댓값이 거래량을 넘는 날이 있는지 본다
from collections import Counter

import numpy as np
import pandas as pd

from backtest.periods import BACKTEST_START, load_daily_csv
from backtest.report_strategy_b_frequency import DATA_DIR, HOLD_BARS, PRICE_CAPS, months_to, summarize_window
from backtest.strategy_a_signals import CONSECUTIVE_DAYS, signal_flags

SIGNALS_CSV = DATA_DIR / "strategy_a_signals.csv"
B_SIGNALS_CSV = DATA_DIR / "strategy_b_signals.csv"


def load_all():
    universe = pd.read_csv(DATA_DIR / "_universe.csv", dtype={"symbol": str})
    stocks = {}
    for symbol in universe["symbol"]:
        candles = load_daily_csv(DATA_DIR / f"{symbol}_daily.csv")
        flows = pd.read_csv(DATA_DIR / f"{symbol}_investor.csv", parse_dates=["date"])
        assert not flows["date"].duplicated().any(), f"매매동향 날짜가 중복됨: {symbol}"
        stocks[symbol] = (candles, flows)
    return universe, stocks


def aligned(candles, flows):
    """일봉 날짜마다 그날의 기관 순매수 거래량을 붙인다 (기록이 없으면 NaN)."""
    dates = candles["timestamp"].dt.tz_localize(None).dt.normalize()
    net_by_date = flows.set_index("date")["institution_net_buy_volume"]
    return dates, dates.map(net_by_date).to_numpy(dtype="float64")


def check_data(universe, stocks):
    print(f"== 데이터 확인 (일봉은 {BACKTEST_START} 이후로 자름) ==")
    summary = pd.read_csv(DATA_DIR / "_investor_summary.csv", dtype={"symbol": str})
    print(f"수집 요약 {len(summary)}종목, 멈춘 이유 {summary['stop_reason'].value_counts().to_dict()}")
    bad = summary[summary["stop_reason"] != "API_END"]
    print(f"끝까지 받지 못한 종목(API_END가 아닌 것): {bad['symbol'].tolist()}")
    assert bad.empty, "끝까지 받지 못한 종목이 있다. 수집을 다시 한다"
    assert set(summary["symbol"]) == set(universe["symbol"]), "종목 목록과 수집 요약이 다르다"

    oldest = pd.to_datetime(summary["oldest"])
    newest = pd.to_datetime(summary["newest"])
    print(f"매매동향 첫 날짜 {oldest.min().date()} ~ {oldest.max().date()}, 마지막 날짜 {newest.min().date()} ~ {newest.max().date()}, "
          f"기록 수 최소 {int(summary['records'].min())} / 중앙값 {int(summary['records'].median())} / 최대 {int(summary['records'].max())}")
    at_start = int((oldest == oldest.min()).sum())
    print(f"가장 이른 시작일({oldest.min().date()})인 종목 {at_start}개, 그보다 늦게 시작한 종목 {len(summary) - at_start}개 "
          "(늦게 시작한 종목이 상장이 늦은 것인지는 아래 일봉 시작일과 비교한다)")

    problems = []
    over_volume = 0
    zero_days = 0
    total_records = 0
    missing_records = 0
    late_start = []
    for symbol, (candles, flows) in stocks.items():
        dates, net = aligned(candles, flows)
        volume = candles["volume"].to_numpy(dtype="float64")
        first_flow = flows["date"].min()
        in_range = (dates >= first_flow).to_numpy()
        # 기록이 있는 기간 안의 일봉 날짜 중 매매동향 기록이 없는 날
        missing_records += int(np.isnan(net[in_range]).sum())
        # 일봉이 시작한 뒤의 매매동향 날짜 중 일봉이 없는 날 (일봉 시작 전은 상장 전이라 볼 수 없다)
        no_candle = {d for d in set(flows["date"]) - set(dates) if d >= dates.min()}
        if no_candle:
            problems.append((symbol, len(no_candle)))
        total_records += len(flows)
        zero_days += int((flows["institution_net_buy_volume"] == 0).sum())
        valid = ~np.isnan(net)
        over_volume += int((np.abs(net[valid]) > volume[valid]).sum())
        if first_flow > dates.min() + pd.Timedelta(days=10) and first_flow > pd.Timestamp("2019-04-05"):
            late_start.append((symbol, str(first_flow.date()), str(dates.min().date())))
    print(f"매매동향 기록 {total_records}건 (순매수가 정확히 0인 기록 {zero_days}건), 기록 기간 안에서 일봉은 있는데 기록이 없는 날 {missing_records}개")
    print(f"매매동향에는 있는데 일봉에 없는 날이 있는 종목(일봉 시작 이전 제외): {problems[:5]}")
    print(f"기관 순매수의 절댓값이 그날 일봉 거래량을 넘는 날 {over_volume}개 (범위가 다르면 이런 날이 생긴다, 0이어야 정상)")
    print(f"매매동향 시작일이 2019-04-05보다 늦고 일봉 시작일보다도 10일 넘게 늦은 종목: {late_start[:10]}")
    print()


def find_signals(stocks):
    rows = []
    eligible = Counter()  # 달마다 신호를 판정할 수 있었던(최근 3일 기록이 모두 있는) 종목 수
    evaluated_days = 0
    for symbol, (candles, flows) in stocks.items():
        dates, net = aligned(candles, flows)
        has_records, consecutive, ratio_met, signal = signal_flags(candles["volume"], net)
        evaluated_days += int(has_records.sum())
        for month in set(dates[has_records].dt.to_period("M")):
            eligible[month] += 1
        previous = None
        for p in np.flatnonzero(signal):
            rows.append({
                "symbol": symbol,
                "date": dates.iloc[p].date(),
                "close": candles["close"].iloc[p],
                "volume": candles["volume"].iloc[p],
                "within_hold": previous is not None and p - previous <= HOLD_BARS + 1,
            })
            previous = p
    signals = pd.DataFrame(rows)
    signals["date"] = pd.to_datetime(signals["date"])
    signals["month"] = signals["date"].dt.to_period("M")
    return signals, eligible, evaluated_days


def main():
    universe, stocks = load_all()
    check_data(universe, stocks)

    signals, eligible, evaluated_days = find_signals(stocks)
    signals.to_csv(SIGNALS_CSV, index=False)
    print("== 신호 (수익률은 보지 않는다) ==")
    print(f"판정한 (종목, 날짜) {evaluated_days}개 중 신호 {len(signals)}건 ({len(signals) / evaluated_days:.3%}), "
          f"날짜 {signals['date'].min().date()} ~ {signals['date'].max().date()}, 신호 나온 종목 {signals['symbol'].nunique()}개 (목록 파일 {SIGNALS_CSV})")
    print(f"종가(수정주가) 최소 {signals['close'].min():,.0f} / 최대 {signals['close'].max():,.0f}")
    print(f"직전 신호 뒤 보유 기간({HOLD_BARS}거래일) 안에 같은 종목에서 또 난 신호 {int(signals['within_hold'].sum())}건 "
          "(전략 A는 3일 조건이 겹쳐서 연속 신호가 잦다. 종목당 한 번에 한 포지션이라 이런 신호는 새 매수가 못 된다)")
    print()

    last_date = max(c["timestamp"].iloc[-1] for c, _ in stocks.values()).tz_localize(None)
    last_full_month = last_date.to_period("M") - 1
    first_month = min(eligible)
    months_all = pd.period_range(first_month, last_full_month, freq="M")
    monthly = signals.groupby("month").size().to_dict()
    n_stocks = len(stocks)

    def cap_mean_factory(cap):
        capped = signals[signals["close"] <= cap].groupby("month").size().to_dict()
        return lambda months: float(np.mean([capped.get(m, 0) for m in months]))

    per_cap = [(f"{cap:,}원", cap_mean_factory(cap)) for cap in PRICE_CAPS]

    print(f"== 창별 월 신호 수 (마지막 달 {last_date.to_period('M')}은 일부만 있어 뺐다, 판정 가능한 첫 달 {first_month}부터) ==")
    windows = [("전체", months_all)]
    for n in (36, 12, 6):
        if len(months_all) >= n:
            windows.append((f"최근 {n}개월", months_all[-n:]))
    results = {name: summarize_window(name, months, monthly, eligible, n_stocks, per_cap) for name, months in windows}
    print()

    print("== 50건에 닿는 데 걸리는 개월 수 (신호 수 기준이라 실제 거래로는 이보다 더 걸린다) ==")
    for name, (mean, median, p25) in results.items():
        print(f"[{name}] 월 평균 기준 {months_to(50, mean)}개월 / 중앙값 기준 {months_to(50, median)}개월 / 하위 25% 월 수준이 계속되면 {months_to(50, p25)}개월")
    print()

    print("== 연도별 신호 수와 신호를 판정할 수 있던 종목 수(연중 평균) ==")
    for year, group in signals.groupby(signals["date"].dt.year):
        months = [m for m in months_all if m.year == year]
        active = np.mean([eligible.get(m, 0) for m in months]) if months else float("nan")
        print(f"{year}: 신호 {len(group)}건, 월 {len(group) / max(len(months), 1):.1f}건 (집계한 달 {len(months)}개), 종목 평균 {active:.0f}개")
    print()

    print("== 몰림 ==")
    by_day = signals.groupby("date").size().sort_values(ascending=False)
    print(f"신호가 난 날 {len(by_day)}일, 상위 5일 {dict((d.date().isoformat(), int(c)) for d, c in by_day.head(5).items())} (전체의 {by_day.head(5).sum() / len(signals):.1%})")
    by_stock = signals.groupby("symbol").size().sort_values(ascending=False)
    print(f"상위 10종목이 전체의 {by_stock.head(10).sum() / len(signals):.1%}, 종목당 신호 수 최소 {int(by_stock.min())} / 중앙값 {int(by_stock.median())} / 최대 {int(by_stock.max())}")
    print()

    if B_SIGNALS_CSV.exists():
        b = pd.read_csv(B_SIGNALS_CSV, dtype={"symbol": str}, parse_dates=["date"])
        b = b[b["date"] >= signals["date"].min()]
        merged = signals.merge(b[["symbol", "date"]], on=["symbol", "date"])
        print("== 전략 B와 겹침 (같은 종목 같은 날 두 전략 신호) ==")
        print(f"A 신호 {len(signals)}건, 같은 기간 B 신호 {len(b)}건, 같은 종목 같은 날 겹침 {len(merged)}건 "
              f"(가상매매는 전략별로 따로 사므로 겹쳐도 각각 센다, 독립성 참고용)")


if __name__ == "__main__":
    main()
