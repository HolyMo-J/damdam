"""trend_filter(v3) 진단: damdam-reviewer 검토(2026-09-23)에서 지적한 두 가지를 재현 가능하게 저장한다.

1. 격자를 느슨/타이트하게 바꿨을 때 trend_filter가 표본 하한(375)을 채우는지와 평균 순수익이 어떻게 변하는지.
2. 비승자 평균 개선이 종목 선택 효과인지, 특정 연도(레짐)에 신호가 더 많이 남는 효과인지 연도별로 분해.

탐색 구간(TUNE)만 쓴다. 결과는 analysis/results/trend_filter_diagnostic.json에 저장한다.
실행: analysis/.venv/Scripts/python.exe -m backtest.report_trend_filter  (analysis 폴더에서)
"""
import json

import numpy as np
import pandas as pd

from backtest.engine import TUNE, Params, load_universe, run
from backtest.search import RESULTS_DIR, SLIPPAGE, classify_winners

PRIMARY = {"drop": 0.04, "vmult": 1.5, "mkt_drop": 0.0, "tp": 0.05, "stop_atr": 1.5}  # search_v3 주 후보(trend_filter 제외)
LOOSE = {"drop": 0.03, "vmult": 1.0, "mkt_drop": 0.0, "tp": 0.05, "stop_atr": 1.5}  # 격자에서 가장 느슨한 하락폭/거래량
TIGHT = {"drop": 0.08, "vmult": 1.0, "mkt_drop": 0.0, "tp": 0.05, "stop_atr": 1.5}  # 격자에서 가장 좁은 하락폭


def by_year(trades):
    if len(trades) == 0:
        return {}
    g = trades.assign(year=trades["entry_date"].str[:4]).groupby("year")["net"].agg(["count", "mean"])
    return {y: {"n": int(r["count"]), "mean_net": float(r["mean"])} for y, r in g.iterrows()}


def measure(universe, winners, base_values, trend_filter):
    p = Params(**base_values, slippage=SLIPPAGE, trend_filter=trend_filter)
    trades = run(universe, p, periods=(TUNE,))
    n = len(trades)
    out = {"trend_filter": trend_filter, "n": n, "feasible_375": n >= 375}
    if n == 0:
        return out
    net = trades["net"]
    is_winner = trades["symbol"].isin(winners)
    out.update(
        mean_net=float(net.mean()),
        se_net=float(net.std() / np.sqrt(n)),
        winner_n=int(is_winner.sum()),
        winner_share_n=float(is_winner.mean()),
        by_year=by_year(trades),
    )
    return out


def main():
    universe = load_universe()
    winners = classify_winners(universe)
    print(f"승자 종목(확인 구간 끝까지만 봄, {sorted(winners)})")

    out = {"grids": {}}
    for name, base in (("primary", PRIMARY), ("loose", LOOSE), ("tight", TIGHT)):
        rows = [measure(universe, winners, base, tf) for tf in (0, 60, 120, 200)]
        out["grids"][name] = {"base_values": base, "by_trend_filter": rows}
        print(f"\n[{name}] {base}")
        for r in rows:
            if r["n"] == 0:
                print(f"  trend_filter={r['trend_filter']:<3} n=0")
                continue
            print(f"  trend_filter={r['trend_filter']:<3} n={r['n']:>4} feasible375={r['feasible_375']} "
                  f"mean={r['mean_net']:+.3%} se={r['se_net']:.3%} winner_share={r['winner_share_n']:.1%}")

    # 레짐(연도) 효과 대 종목 선택 효과 분리: primary 격자에서 trend_filter=0 대비 60의 연도별 잔존율과 연도별 평균
    base_rows = {r["trend_filter"]: r for r in out["grids"]["primary"]["by_trend_filter"]}
    baseline_years = base_rows[0]["by_year"]
    filtered_years = base_rows[60]["by_year"]
    retention = {}
    for year, b in baseline_years.items():
        f = filtered_years.get(year, {"n": 0, "mean_net": None})
        retention[year] = {
            "baseline_n": b["n"], "baseline_mean": b["mean_net"],
            "filtered_n": f["n"], "filtered_mean": f["mean_net"],
            "retention_rate": f["n"] / b["n"] if b["n"] else None,
        }
    out["regime_vs_selection"] = retention
    print("\n[연도별 잔존율과 평균] (primary 격자, trend_filter=0 대비 60)")
    for year, r in sorted(retention.items()):
        fm = f"{r['filtered_mean']:+.3%}" if r["filtered_mean"] is not None else "n/a"
        print(f"  {year}: 잔존율 {r['retention_rate']:.1%} (n {r['baseline_n']}->{r['filtered_n']}) "
              f"평균 {r['baseline_mean']:+.3%} -> {fm}")

    (RESULTS_DIR / "trend_filter_diagnostic.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    print(f"\n저장: {RESULTS_DIR / 'trend_filter_diagnostic.json'}")


if __name__ == "__main__":
    main()
