"""좌표 하강 탐색 (docs/strategy.md "탐색 설정"과 "탐색 실행 규칙").

탐색 구간만 쓴다. 엔진에 periods=(TUNE,)를 넘겨 확인 구간 거래는 시뮬레이션조차 하지 않는다.
실행: analysis/.venv/Scripts/python.exe -m backtest.search  (analysis 폴더에서)
산출물: analysis/results/search_attempts{VERSION}.csv, search_meta{VERSION}.json, candidates{VERSION}.json
"""
import hashlib
import json
import subprocess
from pathlib import Path

import numpy as np
import pandas as pd

from backtest import engine
from backtest.engine import MAX_POSITIONS, TUNE, Params, baseline, date_matched_control, load_universe, run
from backtest.periods import CONFIRM_START, FINAL_START

RESULTS_DIR = Path(__file__).resolve().parents[1] / "results"
VERSION = "_v2"  # v1(4개 좌표) 결과 파일은 그대로 보존하고 보강 탐색 결과는 접미사를 붙여 저장한다

GRID = {
    "drop": [0.03, 0.04, 0.05, 0.06, 0.08],
    "vmult": [1.0, 1.5, 2, 3],
    "mkt_drop": [0.0, 0.015, 0.02, 0.03, 0.04],  # 0은 시장 급락일 필터 끔
    "tp": [0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08],
    "stop_atr": [0.5, 1, 1.5, 2, 3],
}
ORDER = ["drop", "vmult", "mkt_drop", "tp", "stop_atr"]
START = {"drop": 0.05, "vmult": 1.5, "mkt_drop": 0.0, "tp": 0.03, "stop_atr": 1.5}
MAX_ROUNDS = 2
FLOOR = 375  # 탐색 구간 거래 건수 하한 (최종 구간 100건 x 여유 1.5 / 봉 수 비율 0.40)
SLIPPAGE = 0.001
ATTEMPT_LIMIT = 200
TIE_TOL = 1e-9
EXCLUDE_WINDOW = ("2020-02-20", "2020-04-30")  # 코로나 급락 시기. 이 창을 뺀 평균을 진단으로 함께 본다
MIN_BACKUP_DISTANCE = 3  # 차선책은 주 후보와 격자 칸 거리 합이 이만큼 이상 떨어져야 한다


class Searcher:
    def __init__(self, universe):
        self.universe = universe
        self.cache = {}  # 조합 -> 평가 결과 행
        self.baseline_cache = {}  # (tp, stop_atr) -> 대조군 1 평균
        self.log = []
        self.logged = set()  # 스윕 로그에 이미 행이 남은 조합. new_evaluation은 로그 첫 등장을 뜻한다

    @staticmethod
    def key(values):
        return tuple(values[k] for k in ORDER)

    def evaluate(self, values):
        key = self.key(values)
        if key in self.cache:
            return self.cache[key]
        assert len(self.cache) < ATTEMPT_LIMIT, "시도 횟수 상한을 넘었다"
        row = self._measure(values, attempt_no=len(self.cache) + 1)
        self.cache[key] = row
        return row

    def _measure(self, values, attempt_no, slippage=SLIPPAGE):
        p = Params(**values, slippage=slippage)
        trades = run(self.universe, p, periods=(TUNE,))
        row = {"attempt_no": attempt_no, **values, "slippage": slippage}
        n = len(trades)
        row["n"] = n
        row["n_candidates"] = trades.attrs["n_candidates"]
        row["n_after_overlap"] = trades.attrs["n_after_overlap"]
        row["floor_margin"] = n / FLOOR
        row["feasible"] = n >= FLOOR
        if n == 0:
            return row
        net = trades["net"]
        per_day = trades["entry_date"].value_counts()
        outside = trades[(trades["entry_date"] < EXCLUDE_WINDOW[0]) | (trades["entry_date"] > EXCLUDE_WINDOW[1])]
        reasons = trades["reason"].value_counts(normalize=True)
        row.update(
            mean_net=net.mean(),
            median_net=net.median(),
            std_net=net.std(),
            se_net=net.std() / np.sqrt(n),
            win_rate=(net > 0).mean(),
            mean_r=trades["r"].mean(),
            both_touch_share=trades["both_touch"].mean(),
            tp_boundary_share=trades["tp_boundary"].mean(),
            mean_hold_bars=trades["hold_bars"].mean(),
            distinct_entry_days=len(per_day),
            max_entries_per_day=int(per_day.max()),
            days_at_cap=int((per_day == MAX_POSITIONS).sum()),
            n_outside_window=len(outside),
            mean_net_outside_window=outside["net"].mean() if len(outside) else np.nan,
            trade_hash=hashlib.sha1("|".join(sorted(trades["symbol"] + ":" + trades["entry_date"])).encode()).hexdigest()[:12],
            by_year_n=json.dumps(trades["entry_date"].str[:4].value_counts().sort_index().to_dict()),
        )
        for reason in ("tp", "tp_gap", "stop", "stop_gap", "time"):
            row[f"share_{reason}"] = float(reasons.get(reason, 0.0))
        row["control_all_days"] = self._baseline_mean(values, slippage)
        row["control_same_day"] = date_matched_control(self.universe, p, trades, periods=(TUNE,))
        return row

    def _baseline_mean(self, values, slippage):
        key = (values["tp"], values["stop_atr"], slippage)
        if key not in self.baseline_cache:
            p = Params(**values, slippage=slippage)
            trades = baseline(self.universe, p, periods=(TUNE,))
            self.baseline_cache[key] = trades["net"].mean() if len(trades) else np.nan
        return self.baseline_cache[key]

    def sweep(self, current, coord, round_no):
        """한 좌표를 격자 전체로 훑어 하한을 채우는 값 중 목적함수 최대를 고른다."""
        results = []
        for v in GRID[coord]:
            values = {**current, coord: v}
            # 시작점은 스윕 전에 미리 평가되므로, 캐시가 아니라 로그 기준으로 새 평가를 센다
            is_new = self.key(values) not in self.logged
            self.logged.add(self.key(values))
            results.append((v, self.evaluate(values), is_new))
        feasible = [(v, row) for v, row, _ in results if row["feasible"]]
        if not feasible:
            chosen = current[coord]  # 하한을 채우는 값이 없으면 현재 값을 유지
        else:
            best = max(row["mean_net"] for _, row in feasible)
            tied = [(v, row) for v, row in feasible if best - row["mean_net"] <= TIE_TOL]
            if any(v == current[coord] for v, _ in tied):
                chosen = current[coord]
            else:
                chosen = max(tied, key=lambda x: x[1]["n"])[0]
        for v, row, is_new in results:
            self.log.append({"round": round_no, "coordinate": coord, "swept_value": v,
                             "new_evaluation": is_new, "selected": v == chosen, **row})
            mark = "*" if v == chosen else " "
            mean = f"{row['mean_net']:+.3%}" if row["n"] else "  n/a"
            print(f" {mark} R{round_no} {coord}={v:<5} n={row['n']:>4} mean={mean} feasible={row['feasible']} new={is_new}")
        return chosen

    def descend(self):
        current = dict(START)
        start_row = self.evaluate(current)
        assert start_row["feasible"], f"시작점이 건수 하한을 채우지 못한다: n={start_row['n']}"
        rounds_run = 0
        for round_no in range(1, MAX_ROUNDS + 1):
            changed = False
            for coord in ORDER:
                chosen = self.sweep(current, coord, round_no)
                if chosen != current[coord]:
                    changed = True
                    current[coord] = chosen
            rounds_run = round_no
            if not changed:
                break
        return current, rounds_run


def grid_distance(a, b):
    return sum(abs(GRID[k].index(a[k]) - GRID[k].index(b[k])) for k in ORDER)


def pick_backup(searcher, primary):
    pool = [row for row in searcher.cache.values()
            if row["feasible"] and grid_distance(row, primary) >= MIN_BACKUP_DISTANCE]
    return max(pool, key=lambda r: r["mean_net"]) if pool else None


def git_state():
    root = Path(__file__).resolve().parents[2]
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True).stdout.strip()
        dirty = bool(subprocess.run(["git", "status", "--porcelain", "analysis/backtest"], cwd=root,
                                    capture_output=True, text=True).stdout.strip())
    except OSError:
        commit, dirty = "unknown", None
    return commit, dirty


def report_reference(searcher, primary_values):
    """참고 지표: 보고만 하고 확인 구간에 넘길지를 바꾸지 않는다."""
    print("\n[참고] 슬리피지 민감도 (탐색 구간)")
    for slip in (0.0, SLIPPAGE, 0.002):
        r = searcher._measure(primary_values, attempt_no=0, slippage=slip)
        print(f"  slippage={slip:.3%} n={r['n']} mean={r['mean_net']:+.3%} 대조군(같은 날)={r['control_same_day']:+.3%}")

    p = Params(**primary_values, slippage=SLIPPAGE)
    trades = run(searcher.universe, p, periods=(TUNE,))
    symbols = sorted(searcher.universe)
    halves = {"짝수 인덱스": set(symbols[0::2]), "홀수 인덱스": set(symbols[1::2])}
    print("\n[참고] 종목 15/15 분할 (뽑힌 조합의 거래를 나눠 계산, 표준오차가 커서 판정에 쓰지 않음)")
    for name, group in halves.items():
        sub = trades[trades["symbol"].isin(group)]
        print(f"  {name}: n={len(sub)} mean={sub['net'].mean():+.3%} se={sub['net'].std() / np.sqrt(len(sub)):.3%}")

    print("\n[참고] 연도별 (진입일 기준)")
    by_year = trades.assign(year=trades["entry_date"].str[:4]).groupby("year")["net"].agg(["count", "mean"])
    for year, r in by_year.iterrows():
        print(f"  {year}: n={int(r['count'])} mean={r['mean']:+.3%}")


def main():
    universe = load_universe()
    firsts = [sd.dates[0] for sd in universe.values()]
    lasts = [sd.dates[-1] for sd in universe.values()]
    print(f"종목 {len(universe)}개, 날짜 {min(firsts)} ~ {max(lasts)}, 최종 구간 시작일 {FINAL_START} 이후 봉 없음: {max(lasts) < FINAL_START}")
    assert max(lasts) < FINAL_START

    searcher = Searcher(universe)
    primary_values, rounds_run = searcher.descend()
    primary = searcher.cache[searcher.key(primary_values)]
    backup = pick_backup(searcher, primary_values)
    n_eval = len(searcher.cache)
    # 첫 탐색(v1, 시장 필터 없음)에서 이미 평가한 조합까지 합친 누적 시도 수
    v1 = pd.read_csv(RESULTS_DIR / "search_attempts.csv").drop_duplicates("attempt_no")
    v1_keys = set(zip(v1["drop"], v1["vmult"], [0.0] * len(v1), v1["tp"], v1["stop_atr"]))
    cumulative = len(v1_keys | set(searcher.cache))

    print(f"\n이번 탐색에서 평가한 조합 {n_eval}개, 첫 탐색과 합친 누적 {cumulative}개 (상한 {ATTEMPT_LIMIT}), "
          f"좌표 하강 {rounds_run}바퀴, 스윕 행 {len(searcher.log)}개")
    print("주 후보:", {k: primary[k] for k in ORDER}, f"n={primary['n']} mean={primary['mean_net']:+.3%} se={primary['se_net']:.3%}")
    print(f"  대조군(전 종목 모든 날)={primary['control_all_days']:+.3%} 대조군(같은 날)={primary['control_same_day']:+.3%}")
    print(f"  2020-02-20~04-30 제외 평균={primary['mean_net_outside_window']:+.3%} (n={primary['n_outside_window']})")
    if backup is not None:
        print("차선책:", {k: backup[k] for k in ORDER}, f"n={backup['n']} mean={backup['mean_net']:+.3%}")
    else:
        print("차선책: 조건을 만족하는 조합이 없다")
    report_reference(searcher, primary_values)

    RESULTS_DIR.mkdir(exist_ok=True)
    pd.DataFrame(searcher.log).to_csv(RESULTS_DIR / f"search_attempts{VERSION}.csv", index=False)
    commit, dirty = git_state()
    meta = {
        "engine_commit": commit, "backtest_dir_dirty": dirty, "grid": GRID, "order": ORDER, "start": START,
        "max_rounds": MAX_ROUNDS, "rounds_run": rounds_run, "floor": FLOOR, "slippage": SLIPPAGE,
        "tie_tol": TIE_TOL, "max_positions": MAX_POSITIONS, "confirm_start": CONFIRM_START,
        "final_start": FINAL_START, "sell_tax_schedule": engine.SELL_TAX_SCHEDULE, "commission": engine.COMMISSION,
        "exclude_window": EXCLUDE_WINDOW, "new_evaluations": n_eval, "cumulative_evaluations": cumulative,
        "attempt_limit": ATTEMPT_LIMIT,
    }
    (RESULTS_DIR / f"search_meta{VERSION}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    candidates = {
        "primary": {**{k: primary[k] for k in ORDER}, "n_tune": int(primary["n"]), "mean_net_tune": primary["mean_net"]},
        "backup": None if backup is None else {**{k: backup[k] for k in ORDER}, "n_tune": int(backup["n"]),
                                                "mean_net_tune": backup["mean_net"]},
        "new_evaluations": n_eval, "rounds_run": rounds_run,
    }
    (RESULTS_DIR / f"candidates{VERSION}.json").write_text(json.dumps(candidates, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n저장: {RESULTS_DIR}")


if __name__ == "__main__":
    main()
