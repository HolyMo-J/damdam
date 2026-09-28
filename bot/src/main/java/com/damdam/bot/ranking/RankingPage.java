package com.damdam.bot.ranking;

import java.util.List;

// rankedAt은 랭킹 집계 기준 시각(ISO 8601)이고, 집계된 랭킹이 없어 rankings가 빈 배열이면 null이다
public record RankingPage(String rankedAt, List<Ranking> rankings) {
}
