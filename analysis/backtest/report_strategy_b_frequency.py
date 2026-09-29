# 전략 B 과거 신호 빈도 세기 (docs/review-tasks.md E묶음 11번). 수익률은 계산하지 않고 빈도만 본다.
# 입력: analysis/data/universe100/ 의 _universe.csv와 <종목코드>_daily.csv (수정주가 일봉).
#   수집: bot 폴더에서 .\gradlew.bat bootRun --args="--spring.profiles.active=export-universe 100 --duration=1d --exclude-caution=false --out-dir=../analysis/data/universe100"
# 실행: analysis 폴더에서 .venv/Scripts/python.exe -m backtest.report_strategy_b_frequency
#
# 읽는 법(한계):
#  - 종목군을 "오늘 기준" 거래대금 상위로 고정했으므로 과거로 갈수록 생존 편향이 있다 (그동안 오른 종목만 남음, 신호가 더 많이 잡히는 쪽).
#    반대로 실제 대상 종목군은 신호일 저녁 거래대금 순위로 만들어져서, 거래량이 급증한 종목(=신호 후보)이 그날 종목군에 들어온다.
#    고정 종목군은 이 효과를 못 담아 신호가 실제보다 적게 잡히는 쪽이다. 두 편향이 반대 방향이라 크기는 대략으로만 본다.
#    오늘 종목군과 가장 가까운 최근 구간의 값이 가장 덜 왜곡된다
#  - 신호 수는 거래 수의 상한이다 (종목당 한 번에 한 포지션, 1주 가격이 남은 노출 한도보다 크면 건너뜀)
#  - 봉인 구간(2024-06-13 이후)의 봉도 읽지만 2단계 전략의 성과를 보는 것이 아니라 일목 신호 개수만 센다
from collections import Counter
from pathlib import Path

import numpy as np
import pandas as pd

from backtest.periods import BACKTEST_START, load_daily_csv
from backtest.strategy_b_signals import MIN_CANDLES, signal_flags

DATA_DIR = Path(__file__).resolve().parents[1] / "data" / "universe100"
SIGNALS_CSV = DATA_DIR / "strategy_b_signals.csv"
HOLD_BARS = 5  # 청산 규칙 v0의 최대 보유 거래일. 같은 종목의 신호가 이 안에 또 나오면 이미 보유 중일 수 있다
PRICE_CAPS = (500_000, 100_000)  # 가상매매 총 노출 한도(50만원), 실전 초기 자금(10만원): 1주 가격이 이보다 크면 못 산다
CAL_GAP_LIMIT = 12  # 봉 사이 달력 일수. 명절 연휴가 10일 안팎이라 이보다 크면 결측을 의심한다


def load_all():
    stocks = {}
    for path in sorted(DATA_DIR.glob("*_daily.csv")):
        symbol = path.name.split("_")[0]
        with open(path, encoding="utf-8") as f:
            raw_rows = sum(1 for _ in f) - 1
        stocks[symbol] = (load_daily_csv(path), raw_rows)
    return stocks


def check_data(stocks):
    """읽은 데이터를 먼저 눈으로 확인한다 (범위, 건수, 최솟값과 최댓값, 한도에 걸린 값)."""
    print(f"== 데이터 확인 (사용 시작일 {BACKTEST_START} 이후로 자름) ==")
    universe_path = DATA_DIR / "_universe.csv"
    assert universe_path.exists(), f"{universe_path} 가 없다. 수집을 먼저 실행한다"
    universe = pd.read_csv(universe_path, dtype={"symbol": str})
    print(f"종목 목록 파일 {len(universe)}종목, 캔들 파일 {len(stocks)}개, 순위 기준 {universe['duration'].unique()}, "
          f"투자유의 제외 {universe['exclude_caution'].unique()}, 집계 시각 {universe['ranked_at'].unique()}")
    missing = set(universe["symbol"]) - set(stocks)
    extra = set(stocks) - set(universe["symbol"])
    print(f"목록에는 있는데 캔들 파일이 없음: {sorted(missing)}, 캔들 파일은 있는데 목록에 없음: {sorted(extra)}")
    assert not missing and not extra, "종목 목록과 캔들 파일이 다르다"

    firsts = [df["timestamp"].iloc[0] for df, _ in stocks.values()]
    lasts = [df["timestamp"].iloc[-1] for df, _ in stocks.values()]
    bars = [len(df) for df, _ in stocks.values()]
    print(f"첫 날짜 {min(firsts).date()} ~ {max(firsts).date()}, 마지막 날짜 {min(lasts).date()} ~ {max(lasts).date()}")
    print(f"봉 수 최소 {min(bars)} / 중앙값 {int(np.median(bars))} / 최대 {max(bars)}")
    stale = [s for s, (df, _) in stocks.items() if df['timestamp'].iloc[-1] < max(lasts) - pd.Timedelta(days=5)]
    print(f"마지막 날짜가 가장 늦은 날보다 5일 넘게 앞선 종목(거래정지나 수집 문제 의심): {stale}")
    capped = [s for s, (_, raw) in stocks.items() if raw == 10000]
    print(f"원본 CSV가 정확히 10000행인 종목 {len(capped)}개 (수집기 상한에 잘린 것, 2015-06-15 이전만 잘려 이 분석에는 영향 없음): {capped}")

    all_close = np.concatenate([df["close"].to_numpy() for df, _ in stocks.values()])
    all_vol = np.concatenate([df["volume"].to_numpy() for df, _ in stocks.values()])
    non_integer = sum(int((df[c] % 1 != 0).sum()) for df, _ in stocks.values() for c in ("open", "high", "low", "close"))
    print(f"종가 최소 {all_close.min():,.0f} / 최대 {all_close.max():,.0f}, 거래량 0인 봉 {int((all_vol == 0).sum())}개 / 전체 {len(all_vol)}개, "
          f"가격이 정수가 아닌 값 {non_integer}개")
    high_low_bad = sum(int((df["high"] < df["low"]).sum()) for df, _ in stocks.values())
    print(f"고가가 저가보다 낮은 봉 {high_low_bad}개")
    big_gaps = []
    for s, (df, _) in stocks.items():
        gaps = df["timestamp"].diff().dt.days.dropna()
        if len(gaps) and gaps.max() > CAL_GAP_LIMIT:
            big_gaps.append((s, int(gaps.max()), df["timestamp"].iloc[int(gaps.to_numpy().argmax()) + 1].date()))
    print(f"봉 사이 간격이 {CAL_GAP_LIMIT}일을 넘는 종목 {len(big_gaps)}개 (종목, 최대 일수, 그 봉 날짜): {big_gaps[:10]}")
    print()


def find_signals(stocks):
    rows = []
    eligible = Counter()  # 달마다 신호를 낼 수 있었던(78봉 이상 쌓인) 종목 수
    for symbol, (df, _) in stocks.items():
        crossed, surge, signal = signal_flags(df["high"], df["low"], df["close"], df["volume"])
        # 시각이 +09:00 자정이라 시간대를 떼도 한국 날짜가 그대로다 (월 단위로 묶을 때 시간대 경고를 피하려는 것)
        dates = df["timestamp"].dt.tz_localize(None)
        positions = np.flatnonzero(signal)
        months_with_eligible_bar = set(dates.iloc[MIN_CANDLES - 1:].dt.to_period("M")) if len(df) >= MIN_CANDLES else set()
        for month in months_with_eligible_bar:
            eligible[month] += 1
        previous = None
        for p in positions:
            rows.append({
                "symbol": symbol,
                "date": dates.iloc[p].date(),
                "close": df["close"].iloc[p],
                "volume": df["volume"].iloc[p],
                # 같은 종목 신호가 직전 신호 뒤 보유 기간 안에 또 났는가 (이미 들고 있었을 수 있어 새 매수가 못 되는 신호)
                "within_hold": previous is not None and p - previous <= HOLD_BARS + 1,
            })
            previous = p
    signals = pd.DataFrame(rows)
    signals["date"] = pd.to_datetime(signals["date"])
    signals["month"] = signals["date"].dt.to_period("M")
    return signals, eligible


def summarize_window(name, months, monthly, eligible, n_stocks, per_cap):
    counts = np.array([monthly.get(m, 0) for m in months], dtype=float)
    active = np.array([eligible.get(m, 0) for m in months], dtype=float)
    per_stock_month = counts.sum() / active.sum() if active.sum() else float("nan")
    mean, median, p25 = counts.mean(), np.median(counts), np.percentile(counts, 25)
    print(f"[{name}] {months[0]} ~ {months[-1]} ({len(months)}개월), 신호 {int(counts.sum())}건, 월 평균 {mean:.1f} / 중앙값 {median:.1f} / "
          f"하위 25% {p25:.1f} / 최소 {int(counts.min())} / 최대 {int(counts.max())}, 신호를 낼 수 있던 종목 평균 {active.mean():.0f}개, "
          f"종목-월당 {per_stock_month:.3f}건 (지금 종목 수 {n_stocks}개로 환산 월 {per_stock_month * n_stocks:.1f}건)")
    for label, cap_mean in per_cap:
        print(f"      1주 종가 {label} 이하인 신호만: 월 평균 {cap_mean(months):.1f}건")
    return mean, median, p25


def months_to(target, rate):
    return f"{target / rate:.1f}" if rate > 0 else "계산 불가"


def main():
    stocks = load_all()
    check_data(stocks)

    signals, eligible = find_signals(stocks)
    signals.to_csv(SIGNALS_CSV, index=False)
    print(f"== 신호 (수익률은 보지 않는다) ==")
    print(f"신호 {len(signals)}건, 날짜 {signals['date'].min().date()} ~ {signals['date'].max().date()}, 신호 나온 종목 {signals['symbol'].nunique()}개 "
          f"(신호 목록 파일 {SIGNALS_CSV})")
    print(f"종가(수정주가) 최소 {signals['close'].min():,.0f} / 최대 {signals['close'].max():,.0f}, 거래량 최소 {signals['volume'].min():,.0f}")
    print(f"직전 신호 뒤 보유 기간({HOLD_BARS}거래일) 안에 같은 종목에서 또 난 신호 {int(signals['within_hold'].sum())}건")
    print()

    last_date = max(df["timestamp"].iloc[-1] for df, _ in stocks.values()).tz_localize(None)
    last_full_month = last_date.to_period("M") - 1  # 마지막 달은 일부만 있어 뺀다
    first_month = signals["month"].min()
    months_all = pd.period_range(first_month, last_full_month, freq="M")
    monthly = signals.groupby("month").size().to_dict()
    n_stocks = len(stocks)

    def cap_mean_factory(cap):
        capped = signals[signals["close"] <= cap].groupby("month").size().to_dict()
        return lambda months: float(np.mean([capped.get(m, 0) for m in months]))

    per_cap = [(f"{cap:,}원", cap_mean_factory(cap)) for cap in PRICE_CAPS]

    print(f"== 창별 월 신호 수 (마지막 달 {last_date.to_period('M')}은 일부만 있어 뺐다, 첫 신호 달 {first_month}부터) ==")
    windows = [("전체", months_all)]
    for n in (36, 12, 6):
        if len(months_all) >= n:
            windows.append((f"최근 {n}개월", months_all[-n:]))
    results = {}
    for name, months in windows:
        results[name] = summarize_window(name, months, monthly, eligible, n_stocks, per_cap)
    print()

    print("== 50건에 닿는 데 걸리는 개월 수 (신호 수 기준이라 실제 거래로는 이보다 더 걸린다) ==")
    for name, (mean, median, p25) in results.items():
        print(f"[{name}] 월 평균 기준 {months_to(50, mean)}개월 / 중앙값 기준 {months_to(50, median)}개월 / 하위 25% 월 수준이 계속되면 {months_to(50, p25)}개월")
    print()

    print("== 연도별 신호 수와 신호를 낼 수 있던 종목 수(연중 평균) ==")
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


if __name__ == "__main__":
    main()
