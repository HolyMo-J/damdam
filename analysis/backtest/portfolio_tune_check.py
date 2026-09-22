"""탐색(TUNE) 구간에서 포트폴리오 연속 손실 서킷 브레이커 효과를 확인한다 (자유 탐색, 확인/최종 구간 미사용).

주 후보 파라미터(drop=0.04, vmult=1.5, mkt_drop=0, tp=0.05, stop_atr=1.5)는 그대로 고정하고 바꾸지 않는다.
서킷 브레이커(연속 손실 3회, 5거래일 정지)만 켜고 껐을 때 탐색 구간 포트폴리오 낙폭/Calmar가 어떻게
바뀌는지만 본다. 이 파라미터(3회, 5일)는 성과를 보기 전에 정한 값이라 여기서 다시 조정하지 않는다.
실행: analysis/.venv/Scripts/python.exe -m backtest.portfolio_tune_check  (analysis 폴더에서)
"""
import json

from backtest.engine import TUNE, Params, attach_market, load_universe, run
from backtest.periods import BACKTEST_START, CONFIRM_START
from backtest.portfolio import cagr, calmar, equal_weight_curve, max_drawdown, strategy_curve, with_anchor
from backtest.search import RESULTS_DIR, SLIPPAGE

CIRCUIT_BREAKER = (3, 5)  # (연속 손실 횟수, 정지 거래일). 성과를 보기 전에 고정, 봇의 AutoSellGuard 값(3회)을 따름


def report(label, trades, universe, calendar, anchor_date):
    n = len(trades)
    mean_net = float(trades["net"].mean()) if n else float("nan")
    eq = with_anchor(strategy_curve(trades, universe, calendar), anchor_date)
    mdd, c = max_drawdown(eq), cagr(eq)
    cal = calmar(c, mdd)
    print(f"[{label}] n={n} mean_net={mean_net:+.3%} CAGR={c:+.2%} 최대낙폭={mdd:.2%} Calmar={cal:.2f}")
    return {"n": n, "mean_net": mean_net, "cagr": c, "max_drawdown": mdd, "calmar": cal}


def main():
    with open(RESULTS_DIR / "confirm_result_primary.json", encoding="utf-8") as f:
        params = json.load(f)["params"]
    universe = load_universe()
    p = Params(**params, slippage=SLIPPAGE)

    market = attach_market(universe).sort_index()
    calendar = market[(market.index >= BACKTEST_START) & (market.index < CONFIRM_START)].index
    anchor_date = calendar[0]  # 탐색 구간 자체가 백테스트 시작점이라 그 첫날을 앵커로 쓴다
    calendar = calendar[1:]

    without = run(universe, p, periods=(TUNE,))
    with_cb = run(universe, p, periods=(TUNE,), circuit_breaker=CIRCUIT_BREAKER, calendar=market.index)

    print(f"파라미터(주 후보, 고정): {params}, 서킷 브레이커: {CIRCUIT_BREAKER}")
    r1 = report("서킷 브레이커 없음", without, universe, calendar, anchor_date)
    r2 = report("서킷 브레이커 있음", with_cb, universe, calendar, anchor_date)

    bench_eq = with_anchor(equal_weight_curve(universe, calendar), anchor_date)
    bmdd, bcagr = max_drawdown(bench_eq), cagr(bench_eq)
    print(f"[30종목 동일가중] CAGR={bcagr:+.2%} 최대낙폭={bmdd:.2%} Calmar={calmar(bcagr, bmdd):.2f}")

    print(f"\n거래 건수 변화: {r1['n']} -> {r2['n']} ({r2['n'] - r1['n']:+d}, {(r2['n'] / r1['n'] - 1):+.1%})")
    print(f"Calmar 변화: {r1['calmar']:.2f} -> {r2['calmar']:.2f}")
    print(f"최대낙폭 변화: {r1['max_drawdown']:.2%} -> {r2['max_drawdown']:.2%}")


if __name__ == "__main__":
    main()
