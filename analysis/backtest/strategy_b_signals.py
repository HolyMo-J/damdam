# 전략 B(일목 구름 상향 돌파와 거래량 급증) 신호 계산의 Python 버전. 과거 신호 빈도를 세는 분석용이다.
# 봇의 `IchimokuCloudBreakout`(Java)과 같은 규칙이고, 봇과 분석은 서로의 코드에 의존하지 않으므로 규칙을 옮겨 적었다 (docs/strategy.md "전략 B").
# 옮긴 코드가 어긋나지 않았는지는 test_strategy_b_signals.py가 확인한다 (Java 단위 테스트의 손계산 값과 문자 그대로 옮긴 참조 구현 비교).
#   조건: 전날 종가 <= 전날 구름 상단, 신호일 종가 > 신호일 구름 상단 (같으면 돌파 아님),
#         신호일 거래량 >= 직전 20거래일 평균의 2배 (거래량 0인 날은 평균에서 제외, 정확히 2배는 통과)
#   구름 상단: 선행스팬1(전환선 9, 기준선 26의 평균)과 선행스팬2(52) 중 큰 값. 선행스팬은 계산한 날보다 25봉 뒤에 그려지므로
#              그날 위치의 값은 25거래일 전 봉까지의 데이터로 계산한다 (토스 차트 측정 결과, 26이 아니라 25)
#   필요한 봉: 신호일 포함 78개 (전날 구름 상단이 25칸 앞에서 52개 봉을 읽는다)
import numpy as np
import pandas as pd

TENKAN = 9
KIJUN = 26
SENKOU_B = 52
DISPLACEMENT = 25
VOLUME_WINDOW = 20
VOLUME_MULTIPLE = 2
MIN_CANDLES = 1 + DISPLACEMENT + SENKOU_B


def cloud_top(high, low):
    """위치마다 그날 위치의 구름 상단을 돌려준다 (봉이 모자라 계산할 수 없는 앞쪽은 NaN).
    high, low는 오래된 것부터 정렬된 배열이다."""
    h = pd.Series(np.asarray(high, dtype="float64"))
    lo = pd.Series(np.asarray(low, dtype="float64"))

    def midpoint(period):
        return (h.rolling(period).max() + lo.rolling(period).min()) / 2

    span_a = (midpoint(TENKAN) + midpoint(KIJUN)) / 2
    span_b = midpoint(SENKOU_B)
    top = pd.Series(np.maximum(span_a.to_numpy(), span_b.to_numpy()))
    # 25칸 뒤에 그려진다: 위치 u의 구름은 위치 u - 25까지의 봉으로 계산한 값
    return top.shift(DISPLACEMENT).to_numpy()


def signal_flags(high, low, close, volume):
    """위치마다 (구름 돌파 여부, 거래량 급증 여부, 신호 여부)를 돌려준다. 봉이 모자란 앞쪽은 모두 False다."""
    close = np.asarray(close, dtype="float64")
    volume = np.asarray(volume, dtype="float64")
    n = len(close)
    top = cloud_top(high, low)

    crossed = np.zeros(n, dtype=bool)
    # NaN과의 비교는 False라서 구름을 계산할 수 없는 날은 돌파가 아니다
    crossed[1:] = (close[:-1] <= top[:-1]) & (close[1:] > top[1:])

    positive = volume > 0
    volume_sum = pd.Series(np.where(positive, volume, 0.0)).rolling(VOLUME_WINDOW).sum().shift(1).to_numpy()
    volume_days = pd.Series(positive.astype("float64")).rolling(VOLUME_WINDOW).sum().shift(1).to_numpy()
    # 평균 x 2 이상을 나눗셈 없이 (거래량 x 일수 >= 합계 x 2)로 비교한다 (봇과 같은 방식)
    with np.errstate(invalid="ignore"):
        surge = (volume_days > 0) & (volume * volume_days >= volume_sum * VOLUME_MULTIPLE)

    return crossed, surge, crossed & surge
