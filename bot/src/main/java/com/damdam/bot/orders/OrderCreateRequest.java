package com.damdam.bot.orders;

record OrderCreateRequest(String clientOrderId, String symbol, String side, String orderType, String quantity) {
}
