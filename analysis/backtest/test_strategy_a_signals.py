# 전략 A 신호 계산(Python)이 봇의 Java 구현(InstitutionNetBuySignal)과 같은 규칙인지 확인한다.
# 실행: analysis 폴더에서 .venv/Scripts/python.exe -m unittest backtest.test_strategy_a_signals
import random
import unittest
from decimal import Decimal

import numpy as np

from backtest.strategy_a_signals import CONSECUTIVE_DAYS, MIN_RATIO_PERCENT, signal_flags

NAN = float("nan")


def flags_of_last_day(volume, net_buy):
    has_records, consecutive, ratio_met, signal = signal_flags(volume, net_buy)
    return bool(has_records[-1]), bool(consecutive[-1]), bool(ratio_met[-1]), bool(signal[-1])


class HandCalculationTest(unittest.TestCase):
    def test_constants_match_the_bot(self):
        self.assertEqual((3, 3), (CONSECUTIVE_DAYS, MIN_RATIO_PERCENT))

    def test_three_net_buy_days_with_ratio_above_three_percent_signal(self):
        # 거래량 합계 3000, 순매수 합계 120 = 4%
        self.assertEqual((True, True, True, True), flags_of_last_day([1000, 1000, 1000], [30, 40, 50]))

    def test_exactly_three_percent_passes(self):
        self.assertEqual((True, True, True, True), flags_of_last_day([1000, 1000, 1000], [30, 30, 30]))

    def test_just_below_three_percent_fails(self):
        # 합계 89 < 90
        self.assertEqual((True, True, False, False), flags_of_last_day([1000, 1000, 1000], [30, 30, 29]))

    def test_one_net_sell_day_breaks_the_streak_even_if_the_sum_is_large(self):
        self.assertEqual((True, False, True, False), flags_of_last_day([1000, 1000, 1000], [200, -10, 200]))

    def test_zero_net_buy_day_is_not_a_net_buy(self):
        self.assertEqual((True, False, True, False), flags_of_last_day([1000, 1000, 1000], [100, 0, 100]))

    def test_missing_record_is_not_filled_with_zero(self):
        # 기록이 없는 날이 끼면 판정하지 못한 날이다. 0으로 채워 조용히 신호를 내거나 막으면 안 된다
        self.assertEqual((False, False, False, False), flags_of_last_day([1000, 1000, 1000], [100, NAN, 100]))

    def test_window_is_the_three_days_ending_on_the_signal_day(self):
        # 4번째로 오래된 날의 큰 순매도와 큰 거래량은 창 밖이라 영향이 없다
        volume = [10**9, 1000, 1000, 1000]
        net_buy = [-10**9, 30, 40, 50]
        self.assertEqual((True, True, True, True), flags_of_last_day(volume, net_buy))

    def test_zero_volume_sum_never_passes(self):
        self.assertEqual((True, True, False, False), flags_of_last_day([0, 0, 0], [1, 1, 1]))

    def test_fewer_than_three_days_never_signals(self):
        self.assertEqual((False, False, False, False), flags_of_last_day([1000, 1000], [100, 100]))


# ---- 문자 그대로 옮긴 참조 구현 (InstitutionNetBuySignal.java의 evaluate) ----
def reference_evaluate(volumes, net_buys):
    """volumes, net_buys는 최신순 Decimal 목록(0번이 신호일)이고 3개 이상이다. 기록이 없는 날은 None이며 그러면 판정 불가로 None을 돌려준다."""
    net_sum = Decimal(0)
    volume_sum = Decimal(0)
    consecutive = True
    for i in range(CONSECUTIVE_DAYS):
        if net_buys[i] is None:
            return None
        if net_buys[i] <= 0:
            consecutive = False
        net_sum += net_buys[i]
        volume_sum += volumes[i]
    ratio_met = volume_sum > 0 and net_sum * 100 >= volume_sum * MIN_RATIO_PERCENT
    return consecutive and ratio_met


class ReferenceComparisonTest(unittest.TestCase):
    def test_every_day_agrees_with_the_literal_port(self):
        rng = random.Random(20260930)
        compared = evaluated = signals = 0
        for _ in range(6):
            n = 500
            volume = [rng.randint(0, 3000) if rng.random() > 0.03 else 0 for _ in range(n)]
            # 순매수는 거래량 대비 -6%~+9% 사이에서 움직여 3% 경계 근처가 자주 나오게 하고, 기록이 없는 날도 섞는다
            net_buy = []
            for v in volume:
                if rng.random() < 0.03:
                    net_buy.append(NAN)
                else:
                    net_buy.append(round(v * rng.uniform(-0.06, 0.09)))
            has_records, consecutive, ratio_met, signal = signal_flags(volume, net_buy)
            for t in range(CONSECUTIVE_DAYS - 1, n):
                # 최신순 3개. 슬라이스(t-3이 음수가 되면 뒤에서부터 센다) 대신 인덱스로 직접 뽑는다
                vols = [Decimal(volume[t - i]) for i in range(CONSECUTIVE_DAYS)]
                nbs = [None if np.isnan(net_buy[t - i]) else Decimal(int(net_buy[t - i])) for i in range(CONSECUTIVE_DAYS)]
                expected = reference_evaluate(vols, nbs)
                compared += 1
                if expected is None:
                    self.assertFalse(signal[t], f"기록이 없는 날이 낀 창에서 신호가 남 t={t}")
                    self.assertFalse(has_records[t])
                    continue
                evaluated += 1
                signals += expected
                self.assertEqual(expected, bool(signal[t]), f"신호 불일치 t={t}")
        # 비교가 의미가 있으려면 판정한 날과 신호가 충분히 있어야 한다 (모두 False끼리만 비교한 것이 아니라는 확인)
        self.assertGreater(evaluated, 2500)
        self.assertGreater(signals, 30)
        self.assertLess(evaluated, compared)  # 기록이 없는 창도 실제로 나왔다


if __name__ == "__main__":
    unittest.main()
