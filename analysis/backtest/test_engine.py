# 합성 일봉으로 엔진의 체결 경계를 확인한다. 실행: analysis 폴더에서 .venv/Scripts/python.exe -m unittest backtest.test_engine
import unittest

import numpy as np
import pandas as pd

from backtest.engine import (
    COMMISSION, CONFIRM, TUNE, Params, attach_market, build_symbol, date_matched_control, run, sell_tax_rate,
    signal_indices, simulate,
)

T = 25  # 신호일 인덱스, 진입은 26번 봉
E = T + 1
N = 60


def make_bars(overrides=None, n=N):
    """가격 100 근처에서 고가 101, 저가 99, 거래량 1000으로 평평한 일봉. overrides로 봉 하나씩 덮어쓴다."""
    dates = pd.bdate_range("2016-01-04", periods=n)
    df = pd.DataFrame({"timestamp": dates, "open": 100.0, "high": 101.0, "low": 99.0, "close": 100.0, "volume": 1000.0})
    for idx, values in (overrides or {}).items():
        for col, v in values.items():
            df.loc[idx, col] = v
    return df


def sim(overrides=None, params=None, n=N):
    sd = build_symbol("TEST", make_bars(overrides, n))
    p = params or Params(drop=0.08, vmult=3, tp=0.03, stop_atr=1.0)
    return sd, simulate(sd, T, p)


class SimulateTest(unittest.TestCase):
    # ATR은 (101-99)=2이므로 진입가 100 기준 손절 98, 익절 103

    def test_익절은_지정가에_체결된다(self):
        _, tr = sim({E + 1: {"high": 104.0}})
        self.assertEqual(tr["reason"], "tp")
        self.assertEqual(tr["exit"], 103.0)
        self.assertEqual(tr["exit_date"], "2016-02-10")

    def test_같은_날_손절과_익절이_닿으면_손절이_먼저다(self):
        _, tr = sim({E + 1: {"high": 104.0, "low": 97.0}})
        self.assertEqual(tr["reason"], "stop")
        self.assertEqual(tr["exit"], 98.0)
        self.assertTrue(tr["both_touch"])

    def test_시가가_손절가_아래로_갭하면_시가에_체결된다(self):
        _, tr = sim({E + 1: {"open": 95.0, "high": 96.0, "low": 94.0}})
        self.assertEqual(tr["reason"], "stop_gap")
        self.assertEqual(tr["exit"], 95.0)

    def test_시가가_익절가_위로_갭하면_시가에_체결된다(self):
        _, tr = sim({E + 1: {"open": 106.0, "high": 107.0, "low": 105.0}})
        self.assertEqual(tr["reason"], "tp_gap")
        self.assertEqual(tr["exit"], 106.0)

    def test_고가가_익절가와_정확히_같으면_체결로_보지_않고_표시한다(self):
        _, tr = sim({E + 1: {"high": 103.0}})
        self.assertEqual(tr["reason"], "time")
        self.assertTrue(tr["tp_boundary"])

    def test_진입_봉_자체의_고가와_저가도_검사한다(self):
        _, tr = sim({E: {"low": 97.0}})
        self.assertEqual(tr["reason"], "stop")
        self.assertEqual(tr["exit_date"], "2016-02-09")

    def test_시간_청산은_진입_봉_다섯_번째_뒤_봉의_시가다(self):
        _, tr = sim({E + 5: {"open": 99.5, "high": 200.0}})  # 그날 고가는 시가 청산 뒤라 무시된다
        self.assertEqual(tr["reason"], "time")
        self.assertEqual(tr["exit"], 99.5)
        self.assertEqual(tr["exit_date"], "2016-02-16")

    def test_시간_청산일이_거래정지면_재개_첫_봉의_시가에_판다(self):
        halted = {"open": 100.0, "high": 100.0, "low": 100.0, "close": 100.0, "volume": 0.0}
        _, tr = sim({E + 5: halted, E + 6: {"open": 90.0, "high": 91.0, "low": 89.0}})
        self.assertEqual(tr["exit"], 90.0)
        self.assertEqual(tr["exit_date"], "2016-02-17")

    def test_다음_봉이_거래정지이면_진입하지_않는다(self):
        self.assertIsNone(sim({E: {"volume": 0.0}})[1])

    def test_구간_끝을_넘는_거래는_결과와_무관하게_버린다(self):
        # 익절로 일찍 끝날 거래여도 진입 봉 + 5가 데이터 끝을 넘으면 버린다
        _, tr = sim({E + 1: {"high": 104.0}}, n=E + 5)
        self.assertIsNone(tr)

    def test_비용은_수수료_양쪽과_청산일_매도세를_반영한다(self):
        _, tr = sim()  # 2016년 청산이라 매도세 0.30%
        expected = 100 * (1 - COMMISSION - 0.0030) / (100 * (1 + COMMISSION)) - 1
        self.assertAlmostEqual(tr["net"], expected)
        self.assertAlmostEqual(tr["net"], -0.0033, places=4)

    def test_매도세율은_시행일_기준으로_바뀐다(self):
        self.assertEqual(sell_tax_rate("2015-06-15"), 0.0030)
        self.assertEqual(sell_tax_rate("2019-05-29"), 0.0030)
        self.assertEqual(sell_tax_rate("2019-05-30"), 0.0025)
        self.assertEqual(sell_tax_rate("2020-12-31"), 0.0025)
        self.assertEqual(sell_tax_rate("2021-01-01"), 0.0023)
        self.assertEqual(sell_tax_rate("2024-06-12"), 0.0018)


class SignalTest(unittest.TestCase):
    def crash(self, extra=None, event_dates=()):
        # 신호일 T에 3거래일 전 종가 100에서 90으로 하락하고 거래량 5배
        overrides = {T: {"close": 90.0, "volume": 5000.0}}
        overrides.update(extra or {})
        return build_symbol("TEST", make_bars(overrides), event_dates)

    def test_하락폭과_거래량_조건을_모두_채우면_신호가_나온다(self):
        sd = self.crash()
        self.assertEqual(list(signal_indices(sd, Params(0.08, 3, 0.03, 1.0))), [T])
        self.assertEqual(len(signal_indices(sd, Params(0.15, 3, 0.03, 1.0))), 0)
        self.assertEqual(len(signal_indices(sd, Params(0.08, 6, 0.03, 1.0))), 0)

    def test_최근_20봉_안에_거래정지가_있으면_신호를_제외한다(self):
        sd = self.crash({T - 20: {"volume": 0.0}})
        self.assertEqual(len(signal_indices(sd, Params(0.08, 3, 0.03, 1.0))), 0)
        sd = self.crash({T - 21: {"volume": 0.0}})
        self.assertEqual(list(signal_indices(sd, Params(0.08, 3, 0.03, 1.0))), [T])

    def test_최근_20봉_안에_조정_이벤트가_있으면_신호를_제외한다(self):
        dates = make_bars()["timestamp"].dt.strftime("%Y-%m-%d")
        self.assertEqual(len(signal_indices(self.crash(event_dates=[dates[T - 20]]), Params(0.08, 3, 0.03, 1.0))), 0)
        self.assertEqual(len(signal_indices(self.crash(event_dates=[dates[T - 21]]), Params(0.08, 3, 0.03, 1.0))), 1)

    def test_앞에_20봉이_없으면_신호를_제외한다(self):
        df = make_bars({10: {"close": 90.0, "volume": 5000.0}})
        sd = build_symbol("TEST", df)
        self.assertEqual(len(signal_indices(sd, Params(0.08, 3, 0.03, 1.0))), 0)


class RunTest(unittest.TestCase):
    P = Params(drop=0.08, vmult=3, tp=0.03, stop_atr=1.0)

    def universe(self):
        crash = {T: {"close": 90.0, "volume": 5000.0}}
        return {"A": build_symbol("A", make_bars(crash)), "B": build_symbol("B", make_bars())}

    def test_보유_봉_수를_기록한다(self):
        self.assertEqual(sim()[1]["hold_bars"], 5)  # 시간 청산
        self.assertEqual(sim({E + 1: {"high": 104.0}})[1]["hold_bars"], 1)  # 다음 봉 익절

    def test_구간을_지정하면_다른_구간_거래는_계산하지_않는다(self):
        u = self.universe()
        in_tune = run(u, self.P, periods=(TUNE,))
        self.assertEqual(len(in_tune), 1)
        self.assertEqual((in_tune.attrs["n_candidates"], in_tune.attrs["n_after_overlap"]), (1, 1))
        in_confirm = run(u, self.P, periods=(CONFIRM,))  # 합성 봉은 모두 2016년이라 탐색 구간
        self.assertEqual(len(in_confirm), 0)
        self.assertEqual(in_confirm.attrs["n_candidates"], 0)

    def test_같은_날_비신호_종목의_평균을_대조군으로_쓴다(self):
        u = self.universe()
        trades = run(u, self.P, periods=(TUNE,))
        # 신호가 없는 B는 평평한 시간 청산이라 2016년 세율 0.30%와 수수료만큼 손실
        expected = (1 - COMMISSION - 0.0030) / (1 + COMMISSION) - 1
        self.assertAlmostEqual(date_matched_control(u, self.P, trades, periods=(TUNE,)), expected)
        self.assertTrue(np.isnan(date_matched_control(u, self.P, trades.iloc[0:0], periods=(TUNE,))))


class MarketFilterTest(unittest.TestCase):
    CRASH = {T: {"close": 90.0, "volume": 5000.0}}

    def test_시장_전체가_급락한_날의_신호는_제외한다(self):
        u = {"A": build_symbol("A", make_bars(self.CRASH)), "B": build_symbol("B", make_bars(self.CRASH))}
        attach_market(u)  # 두 종목이 같은 날 -10%라 시장도 -10%
        self.assertEqual(len(signal_indices(u["A"], Params(0.08, 3, 0.03, 1.0))), 1)
        self.assertEqual(len(signal_indices(u["A"], Params(0.08, 3, 0.03, 1.0, mkt_drop=0.03))), 0)

    def test_한_종목만_급락하면_시장_평균은_절반이라_임계값에_따라_통과한다(self):
        u = {"A": build_symbol("A", make_bars(self.CRASH)), "B": build_symbol("B", make_bars())}
        market = attach_market(u)
        self.assertAlmostEqual(market.iloc[T], -0.05)
        self.assertEqual(len(signal_indices(u["A"], Params(0.08, 3, 0.03, 1.0, mkt_drop=0.06))), 1)
        self.assertEqual(len(signal_indices(u["A"], Params(0.08, 3, 0.03, 1.0, mkt_drop=0.04))), 0)

    def test_필터를_끄면_시장_수익률이_없어도_동작한다(self):
        sd = build_symbol("A", make_bars(self.CRASH))  # attach_market을 부르지 않음
        self.assertEqual(len(signal_indices(sd, Params(0.08, 3, 0.03, 1.0))), 1)


class CircuitBreakerTest(unittest.TestCase):
    """포트폴리오 연속 손실 서킷 브레이커 (docs/strategy.md 재설계, 봇의 AutoSellGuard와 같은 발상)."""
    P = Params(drop=0.08, vmult=3, tp=0.03, stop_atr=1.0)
    N = 80

    def build(self, name, crash_t, gap_idx=None):
        overrides = {crash_t: {"close": 90.0, "volume": 5000.0}}
        if gap_idx is not None:
            overrides[gap_idx] = {"open": 95.0, "high": 96.0, "low": 94.0}
        return build_symbol(name, make_bars(overrides, n=self.N))

    def universe(self):
        return {
            "L1": self.build("L1", 25, 27),   # 손실(stop_gap), 청산 27
            "L2": self.build("L2", 32, 34),   # 손실, 청산 34
            "L3": self.build("L3", 39, 41),   # 손실, 청산 41 (연속 3회 완성)
            "BLOCKED": self.build("BLOCKED", 42),  # 진입 43, 정지 구간(41~46 거래일) 안이라 막혀야 함
            "AFTER": self.build("AFTER", 50),  # 진입 51, 정지가 끝난 뒤라 정상 진입해야 함
        }

    def calendar(self):
        return build_symbol("CAL", make_bars(n=self.N)).dates

    def test_연속_손실_3회_뒤_정지_기간_안의_신규_진입은_막는다(self):
        trades = run(self.universe(), self.P, circuit_breaker=(3, 5), calendar=self.calendar())
        self.assertNotIn("BLOCKED", set(trades["symbol"]))

    def test_정지_기간이_끝나면_다시_진입한다(self):
        trades = run(self.universe(), self.P, circuit_breaker=(3, 5), calendar=self.calendar())
        self.assertIn("AFTER", set(trades["symbol"]))

    def test_서킷_브레이커를_주지_않으면_기존과_동일하게_전부_받아들인다(self):
        trades = run(self.universe(), self.P)
        self.assertIn("BLOCKED", set(trades["symbol"]))


class ConfirmDecisionTest(unittest.TestCase):
    def test_통과_조건은_건수와_평균_순수익_둘_다이다(self):
        from backtest.confirm import decide
        self.assertTrue(decide(100, 0.0046))
        self.assertFalse(decide(99, 0.01))  # 건수 미달
        self.assertFalse(decide(200, 0.0045))  # 기준 미달
        self.assertFalse(decide(200, -0.01))  # 기대값 음수


if __name__ == "__main__":
    unittest.main()
