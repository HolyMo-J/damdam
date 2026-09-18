package com.damdam.bot.conditionalorder;

import java.util.List;

record ConditionalOrdersPage(List<ConditionalOrderDetail> conditionalOrders, String nextCursor, boolean hasNext) {
}
