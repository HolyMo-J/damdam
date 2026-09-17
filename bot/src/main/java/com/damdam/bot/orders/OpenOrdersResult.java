package com.damdam.bot.orders;

import java.util.List;

record OpenOrdersResult(List<Order> orders, String nextCursor, boolean hasNext) {
}
