package com.damdam.bot.orderevent;

import java.util.List;

record SubscriptionAck(String type, String id, List<String> subscribed, List<RejectedSubscription> rejected) {
}
