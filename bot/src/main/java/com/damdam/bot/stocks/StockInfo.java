package com.damdam.bot.stocks;

public record StockInfo(String symbol, String name, String securityType, boolean isCommonShare, String status) {
}
