package com.damdam.bot.stocks;

import java.time.LocalDate;
import java.util.SortedMap;
import java.util.TreeMap;

// 종목 하나의 투자자별 매매동향을 until과 nextUntil로 과거 방향으로 끝까지 넘겨 모은다. 조회 전용이다.
// 보관 기간을 재는 조회 러너와 과거 자료를 수집하는 러너가 같이 쓴다 (종료 판정이 두 벌이 되지 않게 한곳에 둔다).
// 페이지 상한(maxPages)에 걸려 멈춘 것과 API가 더 없다고 응답한 것을 구분해서 돌려준다: 상한에 걸린 결과의 가장 오래된 날짜는
// 보관 한계가 아니다 (수집기 상한에 잘린 1988년을 데이터 특성으로 착각했던 사고와 같은 실수를 막으려는 것)
public final class InvestorTradingHistory {

	public enum StopReason {
		API_END("API가 더 없다고 응답함(nextUntil이 null). 가장 오래된 날짜가 실제 보관 한계로 보인다"),
		EMPTY_PAGE("빈 페이지를 받음. 보관 한계로 보이지만 nextUntil이 남아 있었는지는 확인 못 함"),
		PAGE_CAP("우리 페이지 상한에 걸려 멈춤. 가장 오래된 날짜는 보관 한계가 아니다. 상한을 올려 다시 재야 한다"),
		NO_PROGRESS("다음 페이지가 과거로 나아가지 않아 멈춤. 원인 확인 못 함");

		public final String text;

		StopReason(String text) {
			this.text = text;
		}
	}

	// byDate는 날짜 오름차순이다. 페이지 경계 날짜가 겹쳐 오면(until이 포함 기준이라) 먼저 받은 기록을 남기고 중복을 없앤다
	public record Result(int pages, SortedMap<LocalDate, InvestorTradingRecord> byDate, StopReason stopReason) {
	}

	private InvestorTradingHistory() {
	}

	// pageSize는 명세상 최대 100. 페이지 사이에 pauseMs만큼 쉰다 (STOCK_TRADING_TREND 그룹은 초당 10회)
	public static Result walk(InvestorTradingService service, String symbol, int pageSize, int maxPages, long pauseMs)
			throws InterruptedException {
		SortedMap<LocalDate, InvestorTradingRecord> byDate = new TreeMap<>();
		LocalDate until = null;
		LocalDate previousNextUntil = null;
		int pages = 0;
		StopReason reason = StopReason.PAGE_CAP;
		while (pages < maxPages) {
			InvestorTradingPage page = service.getRecordsPage(symbol, pageSize, until);
			pages++;
			int before = byDate.size();
			for (InvestorTradingRecord record : page.records()) {
				byDate.merge(record.date(), record, (kept, ignored) -> kept);
			}
			if (page.records().isEmpty()) {
				reason = StopReason.EMPTY_PAGE;
				break;
			}
			if (page.nextUntil() == null) {
				reason = StopReason.API_END;
				break;
			}
			// 새 날짜가 하나도 없거나 기준일이 뒤로 가지 않으면 같은 페이지를 되풀이하는 것이라 끝낸다
			if (byDate.size() == before || (previousNextUntil != null && !page.nextUntil().isBefore(previousNextUntil))) {
				reason = StopReason.NO_PROGRESS;
				break;
			}
			until = page.nextUntil();
			previousNextUntil = until;
			if (pauseMs > 0) {
				Thread.sleep(pauseMs);
			}
		}
		return new Result(pages, byDate, reason);
	}
}
