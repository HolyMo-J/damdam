# 전략 B 신호 계산(Python)이 봇의 Java 구현과 같은 규칙인지 확인한다.
# 실행: analysis 폴더에서 .venv/Scripts/python.exe -m unittest backtest.test_strategy_b_signals
#  1) Java 단위 테스트(IchimokuCloudBreakoutTest)의 손계산 값을 그대로 옮긴 경우
#  2) Java 코드를 문자 그대로 옮긴 느린 참조 구현(최신순 목록, Decimal)과 무작위 자료에서 모든 날의 결과를 비교
import random
import unittest
from decimal import Decimal

import numpy as np

from backtest.strategy_b_signals import (
    DISPLACEMENT,
    KIJUN,
    MIN_CANDLES,
    SENKOU_B,
    TENKAN,
    VOLUME_MULTIPLE,
    VOLUME_WINDOW,
    cloud_top,
    signal_flags,
)


class Series:
    """Java 테스트의 Series와 같다. 인덱스 0이 신호일(최신). 기본값: 고가 110, 저가 90, 종가 100, 거래량 1000.
    구름 손계산용으로 인덱스 25의 고가를 130, 인덱스 34~50의 저가를 70으로 바꿔서 시작한다."""

    def __init__(self, count=MIN_CANDLES):
        self.count = count
        self.high = [110.0] * count
        self.low = [90.0] * count
        self.close = [100.0] * count
        self.volume = [1000.0] * count
        self.high[25] = 130.0
        for i in range(34, 51):
            self.low[i] = 70.0

    def ascending(self):
        # 최신순을 오래된 것부터로 뒤집는다. 마지막 원소가 신호일이다
        return tuple(np.array(list(reversed(x)), dtype="float64") for x in (self.high, self.low, self.close, self.volume))

    def flags(self):
        high, low, close, volume = self.ascending()
        crossed, surge, signal = signal_flags(high, low, close, volume)
        return bool(crossed[-1]), bool(surge[-1]), bool(signal[-1])

    def tops(self):
        high, low, _, _ = self.ascending()
        top = cloud_top(high, low)
        return top[-1], top[-2]


def ramp():
    """고가 = 200 + 2i, 저가 = 100 - i, 종가 150, 거래량 = 1000 + i. 창의 끝 봉이 값을 정해서 한 칸만 어긋나도 결과가 달라진다.
    신호일 구름 상단 188, 전날 188.5, 거래량 평균 1010.5"""
    s = Series()
    for i in range(s.count):
        s.high[i] = float(200 + 2 * i)
        s.low[i] = float(100 - i)
        s.close[i] = 150.0
        s.volume[i] = float(1000 + i)
    return s


class HandCalculationTest(unittest.TestCase):
    def test_constants_match_the_bot(self):
        self.assertEqual((9, 26, 52, 25, 20, 2, 78), (TENKAN, KIJUN, SENKOU_B, DISPLACEMENT, VOLUME_WINDOW, VOLUME_MULTIPLE, MIN_CANDLES))

    def test_base_series_cloud_top(self):
        today, previous = Series().tops()
        self.assertEqual((105.0, 90.0), (today, previous))

    def test_ramp_cloud_top_is_decided_by_each_windows_oldest_bar(self):
        today, previous = ramp().tops()
        self.assertEqual((188.0, 188.5), (today, previous))

    def test_ramp_with_spike_makes_senkou_a_the_top(self):
        s = ramp()
        s.high[30] = 400.0
        today, previous = s.tops()
        self.assertEqual((229.25, 228.75), (today, previous))

    def test_cloud_does_not_depend_on_the_recent_25_bars(self):
        s = Series()
        for i in range(25):
            s.high[i] = 100000.0
            s.low[i] = 1.0
        self.assertEqual((105.0, 90.0), s.tops())

    def test_ramp_signal_boundaries_for_cloud_top_and_volume(self):
        s = ramp()
        s.close[1] = 188.5  # 전날 구름 상단과 같음 (이하이므로 통과)
        s.close[0] = 188.1  # 신호일 구름 상단 188 초과
        s.volume[0] = 2021.0  # 평균 1010.5의 정확히 2배
        self.assertEqual((True, True, True), s.flags())

        s.volume[0] = 2020.0
        self.assertFalse(s.flags()[1])

        s.volume[0] = 2021.0
        s.close[0] = 188.0  # 구름 상단과 같음
        self.assertFalse(s.flags()[0])

        s.close[0] = 188.1
        s.close[1] = 188.6  # 전날 구름 상단 초과
        self.assertFalse(s.flags()[0])

    def test_breakout_with_volume_surge_signals(self):
        s = Series()
        s.close[1] = 90.0
        s.close[0] = 106.0
        s.volume[0] = 2000.0
        self.assertEqual((True, True, True), s.flags())

    def test_close_equal_to_cloud_top_is_not_a_breakout(self):
        s = Series()
        s.close[1] = 90.0
        s.close[0] = 105.0
        s.volume[0] = 5000.0
        self.assertFalse(s.flags()[0])

    def test_previous_close_above_cloud_top_is_not_a_breakout(self):
        s = Series()
        s.close[1] = 91.0
        s.close[0] = 106.0
        s.volume[0] = 5000.0
        self.assertFalse(s.flags()[0])

    def test_volume_just_below_twice_average_is_not_a_surge(self):
        s = Series()
        s.close[1] = 90.0
        s.close[0] = 106.0
        s.volume[0] = 1999.0
        self.assertEqual((True, False, False), s.flags())

    def test_zero_volume_days_are_excluded_from_the_average(self):
        s = Series()
        s.close[1] = 90.0
        s.close[0] = 106.0
        for i in range(1, 11):
            s.volume[i] = 0.0
        s.volume[0] = 1500.0  # 0을 뺀 평균 1000의 2배 미만 (0을 포함한 평균 500이라면 통과했을 값)
        self.assertFalse(s.flags()[1])

    def test_no_volume_history_means_no_surge(self):
        s = Series()
        s.close[1] = 90.0
        s.close[0] = 106.0
        for i in range(1, 21):
            s.volume[i] = 0.0
        s.volume[0] = 5000.0
        self.assertFalse(s.flags()[1])

    def test_volume_window_is_the_20_days_before_the_signal_day(self):
        s = Series()
        s.close[1] = 90.0
        s.close[0] = 106.0
        s.volume[0] = 2000.0
        s.volume[21] = 1000000.0  # 20일 창 바깥
        self.assertTrue(s.flags()[1])

    def test_seventy_seven_candles_never_signal_but_seventy_eight_can(self):
        s = Series(77)
        s.close[1] = 90.0
        s.close[0] = 106.0
        s.volume[0] = 2000.0
        self.assertEqual((False, True, False), s.flags())  # 구름을 계산할 수 없어 돌파는 False
        s = Series(78)
        s.close[1] = 90.0
        s.close[0] = 106.0
        s.volume[0] = 2000.0
        self.assertEqual((True, True, True), s.flags())


# ---- 문자 그대로 옮긴 참조 구현 (IchimokuCloudBreakout.java의 evaluate, cloudTop, midpoint) ----
def _midpoint(highs, lows, start, period):
    highest = max(highs[start:start + period])
    lowest = min(lows[start:start + period])
    return (highest + lowest) / Decimal(2)


def _cloud_top(highs, lows, index):
    base = index + DISPLACEMENT
    tenkan = _midpoint(highs, lows, base, TENKAN)
    kijun = _midpoint(highs, lows, base, KIJUN)
    senkou_a = (tenkan + kijun) / Decimal(2)
    senkou_b = _midpoint(highs, lows, base, SENKOU_B)
    return max(senkou_a, senkou_b)


def reference_evaluate(highs, lows, closes, volumes):
    """모든 인자는 최신순 Decimal 목록이다 (0번이 신호일). (돌파, 급증)을 돌려준다."""
    crossed = closes[1] <= _cloud_top(highs, lows, 1) and closes[0] > _cloud_top(highs, lows, 0)
    volume_sum = Decimal(0)
    non_zero_days = 0
    for i in range(1, VOLUME_WINDOW + 1):
        if volumes[i] > 0:
            volume_sum += volumes[i]
            non_zero_days += 1
    surge = non_zero_days > 0 and volumes[0] * non_zero_days >= volume_sum * VOLUME_MULTIPLE
    return crossed, surge


class ReferenceComparisonTest(unittest.TestCase):
    def _random_series(self, rng, n, wide):
        # 종가가 구름 근처를 자주 오르내리도록 넓은 범위의 무작위 걸음을 쓰고, 거래량 0인 날과 급증한 날을 섞는다
        price = 1000
        highs, lows, closes, volumes = [], [], [], []
        for _ in range(n):
            price = max(50, price + rng.randint(-wide, wide))
            spread = rng.randint(0, wide)
            close = price
            highs.append(close + rng.randint(0, spread))
            lows.append(max(1, close - rng.randint(0, spread)))
            closes.append(close)
            roll = rng.random()
            # 네 번에 한 번은 4배로 급증해서 "돌파와 급증이 함께 나오는 날"이 충분히 생기게 한다
            volumes.append(0 if roll < 0.05 else rng.randint(500, 1500) * (rng.choice([1, 1, 1, 4])))
        return highs, lows, closes, volumes

    def test_every_day_agrees_with_the_literal_port(self):
        rng = random.Random(20260930)
        compared = 0
        signals = 0
        crossings = 0
        for wide in (5, 20, 60):
            highs, lows, closes, volumes = self._random_series(rng, 400, wide)
            crossed, surge, signal = signal_flags(highs, lows, closes, volumes)
            for t in range(MIN_CANDLES - 1, len(closes)):
                # 최신순 목록: 0번이 t, 앞쪽 끝은 (봇은 100봉을 받지만 결과는 78봉만 쓴다) 처음까지 넣어도 같아야 한다
                newest_first = slice(t, None, -1)
                ref_crossed, ref_surge = reference_evaluate(
                    [Decimal(x) for x in highs[newest_first]], [Decimal(x) for x in lows[newest_first]],
                    [Decimal(x) for x in closes[newest_first]], [Decimal(x) for x in volumes[newest_first]])
                self.assertEqual(ref_crossed, bool(crossed[t]), f"돌파 불일치 wide={wide} t={t}")
                self.assertEqual(ref_surge, bool(surge[t]), f"급증 불일치 wide={wide} t={t}")
                compared += 1
                crossings += ref_crossed
                signals += ref_crossed and ref_surge
            # 앞쪽 77일은 신호가 없어야 한다
            self.assertFalse(signal[:MIN_CANDLES - 1].any())
        # 비교가 의미가 있으려면 돌파와 신호가 실제로 여러 번 나와야 한다 (모두 False끼리만 비교한 것이 아니라는 확인)
        self.assertGreater(compared, 900)
        self.assertGreater(crossings, 20)
        self.assertGreater(signals, 5)


if __name__ == "__main__":
    unittest.main()
