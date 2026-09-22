"""확인 구간 거래로 포트폴리오 지표를 계산한다 (docs/strategy.md 판단 기준, 최종 구간 사전 등록 규칙).

confirm_result_primary.json이 있고 통과했을 때만 실행한다. 같은 파라미터로 확인 구간 거래를 다시
생성해(run은 결정적이라 항상 같은 377건이 나온다) 아래 두 곡선을 만든다.
- 전략 포트폴리오: 동시 보유 5슬롯, 슬롯당 동일 금액(1/5)에서 시작해 복리로 재투자한다.
  슬롯 배정은 진입일 순으로 가장 먼저 비는 슬롯에 채우는 라운드로빈 방식이다(여러 슬롯이 동시에 비어
  있으면 인덱스가 낮은 쪽). 보유 중에는 종가로 평가하고, 청산일에는 실제 체결 순수익(net)으로 대체한다.
  슬롯이 비어 있는 동안은 현금으로 보고 잔액을 무이자로 이월한다.
- 30종목 동일가중: 엔진의 attach_market이 만드는 일별 동일가중 평균 수익률(mkt_drop 필터와 같은 정의)을
  확인 구간에서 그대로 누적한다. 매일 리밸런싱하는 것과 같다.
두 곡선 모두 최대 낙폭과 CAGR(연환산 수익률), Calmar(CAGR/최대낙폭)를 계산해 docs/strategy.md
"최종 구간 사전 등록 규칙"의 통과 조건을 판정한다.
최종 검증 구간은 로더가 돌려주지 않아 이 모듈도 볼 수 없다.
실행: analysis/.venv/Scripts/python.exe -m backtest.portfolio  (analysis 폴더에서)
"""
import json

import numpy as np
import pandas as pd

from backtest.engine import CONFIRM, MAX_POSITIONS, Params, attach_market, load_universe, run
from backtest.periods import CONFIRM_START, FINAL_START
from backtest.search import RESULTS_DIR, SLIPPAGE

MDD_LIMIT = 0.10
CALMAR_LIMIT = 1.0


def max_drawdown(equity):
    running_max = equity.cummax()
    return float(-(equity / running_max - 1).min())


def cagr(equity):
    total_return = equity.iloc[-1] / equity.iloc[0]
    days = (pd.Timestamp(equity.index[-1]) - pd.Timestamp(equity.index[0])).days
    if days <= 0 or total_return <= 0:
        return float("nan")
    return float(total_return ** (365.25 / days) - 1)


def calmar(cagr_value, mdd_value):
    if mdd_value <= 0:
        return float("inf")
    return cagr_value / mdd_value


def with_anchor(curve, anchor_date):
    """구간 시작 전날을 값 1.0인 기준점으로 붙인다.

    이게 없으면 곡선의 첫 값이 이미 첫날 수익률을 반영한 값이라(예: 0.9755),
    total_return = 끝값/첫값 계산에서 첫날 수익률이 분자·분모에서 상쇄돼 빠지고
    (damdam-reviewer 지적), 최대 낙폭의 러닝 최댓값도 1.0이 아니라 그 값부터 시작해
    구간 초반 낙폭을 과소평가할 수 있다.
    """
    return pd.concat([pd.Series([1.0], index=[anchor_date]), curve])


def equal_weight_curve(universe, calendar):
    """30종목 동일가중, 일별 리밸런싱 가정의 확인 구간 누적 곡선(앵커 없음, calendar 길이 그대로)."""
    market = attach_market(universe).sort_index()
    ret = market.reindex(calendar)
    assert ret.notna().all(), "동일가중 대조군 계산에 빠진 날짜가 있다"
    return (1 + ret).cumprod()


def strategy_curve(trades, universe, calendar, n_slots=MAX_POSITIONS):
    """5슬롯 라운드로빈 복리 포트폴리오의 확인 구간 일별 평가금액 곡선."""
    trades = trades.sort_values(["entry_date", "exit_date"]).reset_index(drop=True)
    slot_free_from = [""] * n_slots
    assigned = []
    for _, tr in trades.iterrows():
        free = [i for i in range(n_slots) if slot_free_from[i] < tr["entry_date"]]
        assert free, "동시 보유 상한(run이 이미 강제)을 넘는 거래가 있다"
        i = free[0]
        assigned.append(i)
        slot_free_from[i] = tr["exit_date"]
    trades = trades.assign(slot=assigned)

    slot_curves = []
    for slot in range(n_slots):
        series = pd.Series(np.nan, index=calendar, dtype=float)
        balance = 1.0 / n_slots
        for _, tr in trades[trades["slot"] == slot].sort_values("entry_date").iterrows():
            sd = universe[tr["symbol"]]
            ei, xi = int(tr["entry_idx"]), int(tr["exit_idx"])
            path_dates = sd.dates[ei:xi + 1]
            path_close = sd.close[ei:xi + 1]
            values = balance * (path_close / tr["entry"])
            values[-1] = balance * (1 + tr["net"])  # 청산일은 실제 체결 결과로 대체
            series.loc[path_dates] = values
            balance = balance * (1 + tr["net"])
        series = series.ffill().fillna(1.0 / n_slots)  # 포지션 없는 날(첫 거래 전 포함)은 현금, 무이자 이월
        slot_curves.append(series)
    return sum(slot_curves)


def evaluate_gate(strat_cagr, strat_mdd, strat_calmar, bench_cagr, bench_mdd, bench_calmar, n, mean_net):
    """docs/strategy.md "최종 구간 사전 등록 규칙"과 "판단 기준"의 30종목 동일가중 비교를 판정한다."""
    if strat_cagr >= bench_cagr:
        comparison_passed = True
        comparison_reason = "비용 반영 연환산 수익률이 30종목 동일가중 이상"
    else:
        comparison_passed = strat_mdd <= bench_mdd / 2 and strat_calmar > bench_calmar
        comparison_reason = "수익률은 낮지만 낙폭이 절반 이하이고 Calmar가 더 높음" if comparison_passed \
            else "수익률이 낮고 낙폭/Calmar 조건도 못 채움"
    separate_ok = n >= 100 and mean_net >= 0.0046 and strat_mdd <= MDD_LIMIT and strat_calmar >= CALMAR_LIMIT
    return bool(comparison_passed and separate_ok), comparison_reason, separate_ok


def main():
    primary_path = RESULTS_DIR / "confirm_result_primary.json"
    if not primary_path.exists():
        raise SystemExit("confirm_result_primary.json이 없다. 확인 구간을 먼저 연다.")
    confirm_result = json.loads(primary_path.read_text(encoding="utf-8"))
    if not confirm_result["passed"]:
        raise SystemExit("확인 구간이 통과하지 못했다. 포트폴리오 지표를 계산하지 않는다.")

    out_path = RESULTS_DIR / "portfolio_gate_result.json"
    if out_path.exists():
        raise SystemExit(f"이미 계산됐다 ({out_path.name}). 다시 실행하지 않는다.")

    universe = load_universe()
    assert max(sd.dates[-1] for sd in universe.values()) < FINAL_START
    p = Params(**confirm_result["params"], slippage=SLIPPAGE)
    trades = run(universe, p, periods=(CONFIRM,))
    n = len(trades)
    mean_net = float(trades["net"].mean())
    assert n == confirm_result["n"], f"확인 구간 거래 재현이 다르다: {n} != {confirm_result['n']}"
    assert abs(mean_net - confirm_result["mean_net"]) < 1e-9, "확인 구간 거래 재현의 평균 순수익이 다르다"
    print(f"거래 재현 확인: n={n}, mean_net={mean_net:+.3%}, 진입일 {trades['entry_date'].min()} ~ {trades['entry_date'].max()}")

    market = attach_market(universe).sort_index()
    calendar = market[(market.index >= CONFIRM_START) & (market.index < FINAL_START)].index
    anchor_date = market.index[market.index < CONFIRM_START][-1]
    print(f"확인 구간 달력: {calendar.min()} ~ {calendar.max()}, {len(calendar)}개 거래일, 앵커 {anchor_date}=1.0")

    strat_equity = with_anchor(strategy_curve(trades, universe, calendar), anchor_date)
    bench_equity = with_anchor(equal_weight_curve(universe, calendar), anchor_date)
    print(f"전략 포트폴리오: 시작 {strat_equity.iloc[0]:.4f} 끝 {strat_equity.iloc[-1]:.4f} "
          f"최솟값 {strat_equity.min():.4f} 최댓값 {strat_equity.max():.4f}")
    print(f"30종목 동일가중: 시작 {bench_equity.iloc[0]:.4f} 끝 {bench_equity.iloc[-1]:.4f} "
          f"최솟값 {bench_equity.min():.4f} 최댓값 {bench_equity.max():.4f}")

    strat_mdd, bench_mdd = max_drawdown(strat_equity), max_drawdown(bench_equity)
    strat_cagr, bench_cagr = cagr(strat_equity), cagr(bench_equity)
    strat_calmar, bench_calmar = calmar(strat_cagr, strat_mdd), calmar(bench_cagr, bench_mdd)

    passed, comparison_reason, separate_ok = evaluate_gate(
        strat_cagr, strat_mdd, strat_calmar, bench_cagr, bench_mdd, bench_calmar,
        n, mean_net,
    )

    out = {
        "n": n, "mean_net": mean_net,
        "period": [calendar.min(), calendar.max()],
        "strategy": {"cagr": strat_cagr, "max_drawdown": strat_mdd, "calmar": strat_calmar,
                     "start": float(strat_equity.iloc[0]), "end": float(strat_equity.iloc[-1])},
        "equal_weight_30": {"cagr": bench_cagr, "max_drawdown": bench_mdd, "calmar": bench_calmar,
                             "start": float(bench_equity.iloc[0]), "end": float(bench_equity.iloc[-1])},
        "comparison_passed_reason": comparison_reason,
        "separate_criteria_ok": separate_ok,
        "passed": passed,
        "assumptions": {
            "slot_mark_to_market": "종가 기준, 청산일은 실제 체결 순수익으로 대체",
            "reinvestment": "복리, 슬롯별 라운드로빈 배정",
            "idle_cash_return": 0.0,
            "kospi200_comparison": "지수 데이터 확보 방법 미정으로 이번 구현에서는 생략 (docs/todo.md 확인 필요 항목)",
        },
    }
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2, default=float), encoding="utf-8")

    print(f"\n전략: CAGR {strat_cagr:+.2%}, 최대 낙폭 {strat_mdd:.2%}, Calmar {strat_calmar:.2f}")
    print(f"30종목 동일가중: CAGR {bench_cagr:+.2%}, 최대 낙폭 {bench_mdd:.2%}, Calmar {bench_calmar:.2f}")
    print(f"비교 판정: {comparison_reason}")
    print(f"별도 조건(건수/기대값/낙폭10%/Calmar1.0) 충족: {separate_ok}")
    print(f"\n최종 구간 사전 등록 게이트: {'통과' if passed else '실패'}")
    print(f"저장: {out_path.name}")


if __name__ == "__main__":
    main()
