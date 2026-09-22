"""탐색에서 뽑힌 주 후보의 참고 진단 (탐색 구간만 사용, 결과를 analysis/results/primary_reference{VERSION}.json에 저장).

보고만 하고 확인 구간에 넘길지를 바꾸지 않는다 (docs/strategy.md "탐색 실행 규칙").
실행: analysis/.venv/Scripts/python.exe -m backtest.report_primary  (analysis 폴더에서)
"""
import json

import numpy as np
import pandas as pd

from backtest.engine import TUNE, Params, date_matched_control, load_universe, run
from backtest.search import GRID, ORDER, RESULTS_DIR, SLIPPAGE, VERSION, grid_distance


def cluster_se(trades):
    """진입 주(ISO) 단위 군집 표준오차. 같은 주에 몰린 거래가 독립이 아니라는 점을 반영한다."""
    week = pd.to_datetime(trades["entry_date"]).dt.strftime("%G-%V")
    x = trades["net"] - trades["net"].mean()
    sums = x.groupby(week).sum()
    g, n = len(sums), len(trades)
    return float(np.sqrt((sums ** 2).sum() * g / (g - 1)) / n)


def main():
    primary = json.loads((RESULTS_DIR / f"candidates{VERSION}.json").read_text(encoding="utf-8"))["primary"]
    values = {k: primary[k] for k in ORDER}
    universe = load_universe()
    p = Params(**values, slippage=SLIPPAGE)
    trades = run(universe, p, periods=(TUNE,))
    out = {"params": values, "n": len(trades), "mean_net": float(trades["net"].mean())}

    out["se_independent"] = float(trades["net"].std() / np.sqrt(len(trades)))
    out["se_cluster_by_week"] = cluster_se(trades)

    attempts = pd.read_csv(RESULTS_DIR / f"search_attempts{VERSION}.csv").drop_duplicates("attempt_no")
    neigh = attempts[[grid_distance(r, values) == 1 for r in attempts.to_dict("records")]]
    out["neighbors"] = {
        "count": len(neigh),
        "mean_of_mean_net": float(neigh["mean_net"].mean()),
        "list": neigh[ORDER + ["n", "mean_net", "feasible"]].to_dict("records"),
    }

    by_reason = trades.groupby("reason")["net"].agg(["count", "mean", "median"])
    out["by_reason"] = by_reason.round(5).to_dict("index")

    # 동시 보유 상한을 풀었을 때 상한이 걸러낸 거래와 남긴 거래의 평균 (선착순 규칙이라 완전히 분리되지는 않는다)
    unlimited = run(universe, p, max_positions=999, periods=(TUNE,))
    kept = set(zip(trades["symbol"], trades["entry_date"]))
    dropped = unlimited[[(s, d) not in kept for s, d in zip(unlimited["symbol"], unlimited["entry_date"])]]
    out["cap_effect"] = {
        "n_unlimited": len(unlimited), "mean_unlimited": float(unlimited["net"].mean()),
        "n_dropped_by_cap": len(dropped), "mean_dropped_by_cap": float(dropped["net"].mean()) if len(dropped) else None,
        "mean_kept": float(trades["net"].mean()),
    }

    # 청산 규칙 영향을 없앤 비교: 익절과 손절을 사실상 끄고 진입 후 5봉 시가 청산만 한다
    time_only = Params(values["drop"], values["vmult"], tp=10.0, stop_atr=100.0, slippage=SLIPPAGE,
                       mkt_drop=values["mkt_drop"], trend_filter=values.get("trend_filter", 0.0))
    t_only = run(universe, time_only, periods=(TUNE,))
    out["time_exit_only"] = {
        "n": len(t_only), "mean_net": float(t_only["net"].mean()),
        "same_day_control": date_matched_control(universe, time_only, t_only, periods=(TUNE,)),
    }

    out["slippage_sensitivity"] = {}
    for slip in (0.0, SLIPPAGE, 0.002):
        ps = Params(**values, slippage=slip)
        ts = run(universe, ps, periods=(TUNE,))
        out["slippage_sensitivity"][str(slip)] = {
            "n": len(ts), "mean_net": float(ts["net"].mean()),
            "same_day_control": date_matched_control(universe, ps, ts, periods=(TUNE,)),
        }

    symbols = sorted(universe)
    out["symbol_halves"] = {}
    for name, group in {"even": set(symbols[0::2]), "odd": set(symbols[1::2])}.items():
        sub = trades[trades["symbol"].isin(group)]
        out["symbol_halves"][name] = {"n": len(sub), "mean_net": float(sub["net"].mean())}

    by_year = trades.assign(year=trades["entry_date"].str[:4]).groupby("year")["net"].agg(["count", "mean"])
    out["by_year"] = by_year.round(5).to_dict("index")

    (RESULTS_DIR / f"primary_reference{VERSION}.json").write_text(json.dumps(out, ensure_ascii=False, indent=2, default=float), encoding="utf-8")

    print(f"주 후보 {values}: n={out['n']} mean={out['mean_net']:+.3%}")
    print(f"표준오차: 독립 가정 {out['se_independent']:.3%} | 진입 주 단위 군집 {out['se_cluster_by_week']:.3%}")
    print(f"이웃 조합(격자 한 칸 차이) {out['neighbors']['count']}개 평균의 평균: {out['neighbors']['mean_of_mean_net']:+.3%} (주 후보 {out['mean_net']:+.3%})")
    print("청산 사유별 (건수, 평균, 중앙값):")
    for reason, r in out["by_reason"].items():
        print(f"  {reason:<9} n={int(r['count']):>3} mean={r['mean']:+.3%} median={r['median']:+.3%}")
    c = out["cap_effect"]
    print(f"상한 효과: 무제한 n={c['n_unlimited']} 평균 {c['mean_unlimited']:+.3%} | 상한이 뺀 {c['n_dropped_by_cap']}건 평균 {c['mean_dropped_by_cap']:+.3%} | 남긴 거래 평균 {c['mean_kept']:+.3%}")
    t = out["time_exit_only"]
    print(f"5봉 시가 청산만: n={t['n']} 평균 {t['mean_net']:+.3%} 같은 날 대조군 {t['same_day_control']:+.3%} (차이 {t['mean_net'] - t['same_day_control']:+.3%}p)")
    print("슬리피지 민감도:", {k: f"{v['mean_net']:+.3%}" for k, v in out["slippage_sensitivity"].items()})
    print("종목 절반:", {k: f"n={v['n']} {v['mean_net']:+.3%}" for k, v in out["symbol_halves"].items()})
    print("연도별:", {k: f"n={int(v['count'])} {v['mean']:+.2%}" for k, v in out["by_year"].items()})


if __name__ == "__main__":
    main()
