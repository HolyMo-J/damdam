package com.damdam.bot.market;

// integrated는 휴장일이면 null, 개장일이면 세션 정보 객체다. 세션 내부 필드는 지금 쓰지 않아 Object로만 null 여부를 본다.
record MarketCalendarDay(String date, Object integrated) {

	boolean isTradingDay() {
		return integrated != null;
	}
}
