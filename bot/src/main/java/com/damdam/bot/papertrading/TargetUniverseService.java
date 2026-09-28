package com.damdam.bot.papertrading;

import com.damdam.bot.ranking.Ranking;
import com.damdam.bot.ranking.RankingPage;
import com.damdam.bot.ranking.RankingService;
import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockInfoService;
import com.damdam.bot.stocks.StockWarning;
import com.damdam.bot.stocks.StockWarningLookupException;
import com.damdam.bot.stocks.StockWarningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 가상매매 대상 종목군을 한 번 계산한다: 전 거래일 거래대금 상위 100종목 -> 종목 정보 일괄 조회 -> 종목별 경고 조회.
// 조회 전용이다. 언제 부를지(장 시작 전 1회)와 실패했을 때 어제 종목군을 유지할지는 스케줄 조각이 정한다
@Service
public class TargetUniverseService {

	private static final Logger log = LoggerFactory.getLogger(TargetUniverseService.class);

	private static final String MARKET_COUNTRY = "KR";
	private static final String DURATION = "1d";
	// 명세상 순위는 최대 100위까지라, 종목 정보 조회 한도(한 번에 200개) 안에 항상 들어온다
	private static final int RANKING_COUNT = 100;
	// 경고 조회는 STOCK 그룹 초당 5회 한도를 종목 정보 조회와 나눠 쓴다. 호출 사이 250ms(초당 4회)를 둔다
	private static final long PAUSE_BETWEEN_WARNING_CALLS_MS = 250;

	private final RankingService rankingService;
	private final StockInfoService stockInfoService;
	private final StockWarningService stockWarningService;
	private final long pauseMs;

	@Autowired
	public TargetUniverseService(RankingService rankingService, StockInfoService stockInfoService,
								 StockWarningService stockWarningService) {
		this(rankingService, stockInfoService, stockWarningService, PAUSE_BETWEEN_WARNING_CALLS_MS);
	}

	TargetUniverseService(RankingService rankingService, StockInfoService stockInfoService,
						  StockWarningService stockWarningService, long pauseMs) {
		this.rankingService = rankingService;
		this.stockInfoService = stockInfoService;
		this.stockWarningService = stockWarningService;
		this.pauseMs = pauseMs;
	}

	// 순위나 종목 정보 일괄 조회가 실패하면 예외를 그대로 던진다. 종목 하나의 경고 조회 실패는 그 종목만 뺀다
	public TargetUniverse build() {
		// 순위 옵션(excludeInvestmentCaution)은 끈다. 이 옵션이 정확히 어떤 지정 종목을 빼는지 명세에 설명이 없고 실측으로도 아직 확인하지
		// 못했다(2026-09-29). 켜 두면 "정리매매, 투자경고, 투자위험, 거래정지만 거른다"는 결정이 API 쪽에서 몰래 넓어질 수 있어서,
		// 그 네 가지는 아래에서 우리가 직접 거르고 나머지는 거르지 않는다
		RankingPage page = rankingService.getMarketTradingAmountPage(MARKET_COUNTRY, DURATION, RANKING_COUNT, false);
		List<Ranking> rankings = page == null || page.rankings() == null ? List.of() : page.rankings();
		if (rankings.isEmpty()) {
			// 명세상 집계가 없으면 에러 없이 빈 배열이 온다. 빈 종목군을 정상 결과로 돌려주면 "오늘은 대상 없음"으로 오해되므로 실패로 다룬다
			throw new IllegalStateException("거래대금 순위가 비어 있어 대상 종목군을 만들 수 없습니다.");
		}

		List<StockInfo> infos = stockInfoService.getStocks(rankings.stream().map(Ranking::symbol).toList());
		if (infos.isEmpty()) {
			// 응답 본문이 비면 서비스가 빈 목록을 돌려준다. 그대로 두면 100종목이 전부 INFO_MISSING이 되어 "대상 0개"라는 정상 결과처럼 보인다
			throw new IllegalStateException("종목 정보 조회 결과가 비어 있어 대상 종목군을 만들 수 없습니다.");
		}
		Map<String, StockInfo> infoBySymbol = infos.stream()
			.collect(Collectors.toMap(StockInfo::symbol, Function.identity(), (first, second) -> first));

		List<TargetUniverse.Member> members = new ArrayList<>();
		List<TargetUniverse.Exclusion> exclusions = new ArrayList<>();
		boolean firstWarningCall = true;

		for (Ranking ranking : rankings) {
			StockInfo info = infoBySymbol.get(ranking.symbol());
			Optional<ExclusionReason> infoReason = UniverseFilter.checkInfo(info);
			if (infoReason.isPresent()) {
				exclusions.add(new TargetUniverse.Exclusion(ranking.rank(), ranking.symbol(), infoReason.get()));
				continue;
			}

			if (!firstWarningCall) {
				pause();
			}
			firstWarningCall = false;

			Optional<ExclusionReason> warningReason;
			try {
				List<StockWarning> warnings = stockWarningService.getWarnings(ranking.symbol());
				warningReason = UniverseFilter.checkWarnings(warnings);
			} catch (StockWarningLookupException e) {
				log.warn("[대상 종목군] {} 경고 조회에 실패해 제외합니다: {}", ranking.symbol(), e.getMessage());
				warningReason = Optional.of(ExclusionReason.WARNING_LOOKUP_FAILED);
			}

			if (warningReason.isPresent()) {
				exclusions.add(new TargetUniverse.Exclusion(ranking.rank(), ranking.symbol(), warningReason.get()));
			} else {
				members.add(new TargetUniverse.Member(ranking.rank(), ranking.symbol(), info.name(), ranking.tradingAmount()));
			}
		}

		long lookupFailures = exclusions.stream().filter(e -> e.reason() == ExclusionReason.WARNING_LOOKUP_FAILED).count();
		if (members.isEmpty()) {
			// 거래대금 상위 100종목이 모두 걸러지는 것은 현실적이지 않다. 경고 API 전체 장애(403, 401, 서버 오류)가 종목마다 조회 실패로
			// 쌓인 결과일 수 있어서, 빈 종목군을 정상 결과로 돌려주지 않고 실패로 알린다
			throw new IllegalStateException("통과한 종목이 없습니다 (순위 %d종목, 제외 %d종목, 그중 경고 조회 실패 %d종목)."
				.formatted(rankings.size(), exclusions.size(), lookupFailures));
		}
		if (lookupFailures > 0) {
			log.warn("[대상 종목군] 경고 조회에 실패해 제외된 종목이 {}개 있습니다 (호출 제한이나 일시 장애일 수 있음)", lookupFailures);
		}
		log.info("[대상 종목군] 순위 {}종목 중 {}종목 통과, {}종목 제외", rankings.size(), members.size(), exclusions.size());
		return new TargetUniverse(List.copyOf(members), List.copyOf(exclusions));
	}

	private void pause() {
		if (pauseMs <= 0) {
			return;
		}
		try {
			Thread.sleep(pauseMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
