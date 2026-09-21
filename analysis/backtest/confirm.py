"""확인 구간 실행 (docs/strategy.md "확인 구간 실패 시 절차"와 "탐색 실행 규칙").

탐색에서 뽑힌 숫자를 그대로 적용만 한다 (재조정 금지). 한 번 열면 다시 쓸 수 없는 구간이라 코드로 한 번만 돌게 막는다.
- 주 후보: analysis/results/confirm_result_primary.json이 이미 있으면 실행하지 않는다
- 차선책(--backup): 주 후보 결과가 기록돼 있고 실패였을 때만, 그리고 한 번만 실행한다
최종 검증 구간은 로더가 돌려주지 않아 이 스크립트도 볼 수 없다.
실행: analysis/.venv/Scripts/python.exe -m backtest.confirm [--backup]  (analysis 폴더에서)
"""
import json
import sys

import numpy as np

from backtest.engine import CONFIRM, Params, baseline, date_matched_control, load_universe, run
from backtest.periods import CONFIRM_START, FINAL_START
from backtest.report_primary import cluster_se
from backtest.search import MAX_POSITIONS, ORDER, RESULTS_DIR, SLIPPAGE, VERSION

MIN_TRADES = 100  # 확인 구간 최소 거래 건수 (docs/strategy.md 판단 기준)
MIN_MEAN_NET = 0.0046  # 거래당 평균 순수익이 왕복 비용(약 0.23%)의 2배 이상


def decide(n, mean_net):
    """사전 등록한 통과 조건: 100건 이상이고 평균 순수익이 양수이면서 0.46% 이상."""
    return bool(n >= MIN_TRADES and mean_net > 0 and mean_net >= MIN_MEAN_NET)


def main():
    use_backup = "--backup" in sys.argv
    kind = "backup" if use_backup else "primary"
    out_path = RESULTS_DIR / f"confirm_result_{kind}.json"
    primary_path = RESULTS_DIR / "confirm_result_primary.json"

    if out_path.exists():
        sys.exit(f"확인 구간을 이미 열었다 ({out_path.name}). 다시 실행하지 않는다.")
    if use_backup:
        if not primary_path.exists():
            sys.exit("주 후보 결과가 없다. 차선책은 주 후보가 실패로 기록된 뒤에만 실행한다.")
        if json.loads(primary_path.read_text(encoding="utf-8"))["passed"]:
            sys.exit("주 후보가 통과했다. 차선책은 실행하지 않는다.")

    candidates = json.loads((RESULTS_DIR / f"candidates{VERSION}.json").read_text(encoding="utf-8"))
    chosen = candidates[kind]
    if chosen is None:
        sys.exit(f"{kind} 후보가 없다.")
    values = {k: chosen[k] for k in ORDER}
    print(f"확인 구간 {CONFIRM_START} ~ {FINAL_START} 직전, {kind} 후보: {values}")

    universe = load_universe()
    assert max(sd.dates[-1] for sd in universe.values()) < FINAL_START
    p = Params(**values, slippage=SLIPPAGE)
    trades = run(universe, p, periods=(CONFIRM,))
    n = len(trades)
    assert n > 0, "확인 구간 거래가 없다"
    assert trades["entry_date"].min() >= CONFIRM_START and trades["entry_date"].max() < FINAL_START

    net = trades["net"]
    out = {
        "kind": kind, "params": values, "tune_mean_net": chosen["mean_net_tune"], "tune_n": chosen["n_tune"],
        "n": n, "n_candidates": trades.attrs["n_candidates"], "n_after_overlap": trades.attrs["n_after_overlap"],
        "entry_range": [trades["entry_date"].min(), trades["entry_date"].max()],
        "mean_net": float(net.mean()), "median_net": float(net.median()),
        "se_independent": float(net.std() / np.sqrt(n)), "se_cluster_by_week": cluster_se(trades),
        "win_rate": float((net > 0).mean()),
        "by_reason": trades.groupby("reason")["net"].agg(["count", "mean"]).round(5).to_dict("index"),
        "by_year": trades.assign(year=trades["entry_date"].str[:4]).groupby("year")["net"].agg(["count", "mean"]).round(5).to_dict("index"),
        "control_all_days": float(baseline(universe, p, periods=(CONFIRM,))["net"].mean()),
        "control_same_day": date_matched_control(universe, p, trades, periods=(CONFIRM,)),
        "distinct_entry_days": int(trades["entry_date"].nunique()),
        "max_positions": MAX_POSITIONS,
    }
    out["slippage_sensitivity"] = {}
    for slip in (0.0, 0.002):
        ps = Params(**values, slippage=slip)
        ts = run(universe, ps, periods=(CONFIRM,))
        out["slippage_sensitivity"][str(slip)] = {"n": len(ts), "mean_net": float(ts["net"].mean())}
    out["passed"] = decide(n, out["mean_net"])
    out["criteria"] = {"min_trades": MIN_TRADES, "min_mean_net": MIN_MEAN_NET}
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2, default=float), encoding="utf-8")

    print(f"n={n} (후보 {out['n_candidates']} -> 겹침 제거 {out['n_after_overlap']}), 진입 {out['entry_range'][0]} ~ {out['entry_range'][1]}")
    print(f"평균 순수익 {out['mean_net']:+.3%} (탐색 구간 {out['tune_mean_net']:+.3%}), 중앙값 {out['median_net']:+.3%}, 승률 {out['win_rate']:.1%}")
    print(f"표준오차 독립 {out['se_independent']:.3%} | 주 단위 군집 {out['se_cluster_by_week']:.3%}")
    print(f"대조군 전 종목 모든 날 {out['control_all_days']:+.3%} | 같은 날 {out['control_same_day']:+.3%}")
    print("청산 사유별:", {k: f"n={int(v['count'])} {v['mean']:+.2%}" for k, v in out["by_reason"].items()})
    print("연도별:", {k: f"n={int(v['count'])} {v['mean']:+.2%}" for k, v in out["by_year"].items()})
    print("슬리피지 민감도:", {k: f"n={v['n']} {v['mean_net']:+.3%}" for k, v in out["slippage_sensitivity"].items()})
    print(f"\n판정 (100건 이상, 평균 순수익 {MIN_MEAN_NET:.2%} 이상): {'통과' if out['passed'] else '실패'}")
    print(f"저장: {out_path.name}")


if __name__ == "__main__":
    main()
