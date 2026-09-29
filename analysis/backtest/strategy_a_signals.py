# 전략 A(기관 순매수 추종, 거래량 기준) 신호 계산의 Python 버전. 과거 신호 빈도를 세는 분석용이다.
# 봇의 `InstitutionNetBuySignal`(Java)과 같은 규칙이고, 봇과 분석은 서로의 코드에 의존하지 않으므로 규칙을 옮겨 적었다 (docs/strategy.md "전략 A").
# 옮긴 코드가 어긋나지 않았는지는 test_strategy_a_signals.py가 확인한다 (손계산 값과 문자 그대로 옮긴 참조 구현 비교).
#   조건: 신호일을 포함한 최근 3개 일봉(거래정지일 봉도 하나로 센다)의 날짜마다 기관 순매수 거래량 > 0 (3거래일 연속 순매수),
#         그리고 3일 합계 순매수 거래량 >= 같은 3일 일봉 거래량 합계의 3% (정확히 3%는 통과)
#   그 날짜의 매매동향 기록이 하나라도 없으면 0으로 채우지 않고 판정하지 못한 날로 둔다 (봇은 예외를 던진다)
import numpy as np
import pandas as pd

CONSECUTIVE_DAYS = 3
MIN_RATIO_PERCENT = 3


def signal_flags(volume, net_buy):
    """위치마다 (3일 기록 있음, 3일 연속 순매수, 비율 충족, 신호)를 돌려준다.
    volume은 오래된 것부터 정렬된 일봉 거래량이고, net_buy는 같은 날짜의 기관 순매수 거래량이다 (기록이 없는 날은 NaN)."""
    v = np.asarray(volume, dtype="float64")
    nb = np.asarray(net_buy, dtype="float64")

    def rolling_sum(values):
        return pd.Series(values).rolling(CONSECUTIVE_DAYS).sum().to_numpy()

    has_records = rolling_sum((~np.isnan(nb)).astype("float64")) == CONSECUTIVE_DAYS
    # NaN과의 비교는 False라서 기록이 없는 날이 낀 창은 연속 순매수가 아니다
    consecutive = rolling_sum((nb > 0).astype("float64")) == CONSECUTIVE_DAYS
    net_sum = rolling_sum(nb)
    volume_sum = rolling_sum(v)
    # 순매수 합계 >= 거래량 합계 x 3% 를 나눗셈 없이 (순매수 x 100 >= 거래량 x 3)로 비교한다 (봇과 같은 방식)
    ratio_met = (volume_sum > 0) & (net_sum * 100 >= volume_sum * MIN_RATIO_PERCENT)
    return has_records, consecutive, ratio_met, has_records & consecutive & ratio_met
