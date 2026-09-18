package com.damdam.bot.ranking;

public record Ranking(int rank, String symbol, String currency, RankingPrice price, String tradingVolume, String tradingAmount) {
}
