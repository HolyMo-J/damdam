# 합성 일봉으로 엔진의 체결 경계를 확인한다. 실행: analysis 폴더에서 .venv/Scripts/python.exe -m unittest backtest.test_engine
import unittest

import pandas as pd

from backtest.engine import COMMISSION, SELL_TAX, Params, build_symbol, signal_indices, simulate

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

    def test_비용은_수수료_양쪽과_매도세를_반영한다(self):
        _, tr = sim()
        expected = 100 * (1 - COMMISSION - SELL_TAX) / (100 * (1 + COMMISSION)) - 1
        self.assertAlmostEqual(tr["net"], expected)
        self.assertAlmostEqual(tr["net"], -0.0023, places=4)


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


if __name__ == "__main__":
    unittest.main()
