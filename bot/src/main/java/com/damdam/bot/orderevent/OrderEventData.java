package com.damdam.bot.orderevent;

record OrderEventData(String event, String accountSeq, OrderEventSnapshot order) {
}
