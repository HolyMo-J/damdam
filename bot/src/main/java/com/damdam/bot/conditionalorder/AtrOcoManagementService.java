package com.damdam.bot.conditionalorder;

import com.damdam.bot.control.TradingHaltSwitch;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.market.AtrService;
import com.damdam.bot.notification.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 매수 체결마다 평단가 기준으로 ATR 익절/손절 OCO를 등록하거나 기존 걸 수정한다 (docs/strategy.md v0)
@Service
public class AtrOcoManagementService {

	private static final Logger log = LoggerFactory.getLogger(AtrOcoManagementService.class);
	// 조건주문 만료일은 최대 보유 5거래일(시간 청산 기준)을 넉넉히 덮도록 잡는다. 그 전에 시간 청산이 대신 정리한다
	private static final long EXPIRE_DAYS = 10;

	private final HoldingsService holdingsService;
	private final AtrService atrService;
	private final ConditionalOrderService conditionalOrderService;
	private final Notifier notifier;
	private final TradingHaltSwitch haltSwitch;

	public AtrOcoManagementService(HoldingsService holdingsService, AtrService atrService,
			ConditionalOrderService conditionalOrderService, Notifier notifier, TradingHaltSwitch haltSwitch) {
		this.holdingsService = holdingsService;
		this.atrService = atrService;
		this.conditionalOrderService = conditionalOrderService;
		this.notifier = notifier;
		this.haltSwitch = haltSwitch;
	}

	// 매수 체결(신규/추가매수) 이벤트를 받으면 현재 평단가와 보유 수량으로 OCO를 최신화한다
	public void syncAfterBuyFill(long accountSeq, String symbol) {
		try {
			doSync(accountSeq, symbol);
		} catch (Exception e) {
			log.warn("[OCO 갱신] {} 처리 중 오류로 건너뜁니다: {}", symbol, e.getMessage());
			alertOcoProblem(symbol, "OCO 등록/수정 중 오류: " + e.getMessage());
		}
	}

	// 매도가 완전히 체결(FILL)된 뒤에 호출한다. 주문 접수 직후가 아니라 체결을 확인한 뒤에 정리해야,
	// 매도가 거부되거나 체결이 안 되는 동안에는 손절 보호(OCO)가 유지된다.
	// 보유가 0이면 남은 OCO를 취소하고, 일부만 팔린 경우에는 이미 있는 OCO의 수량만 남은 보유량에 맞춘다 (없던 OCO를 새로 만들지는 않는다)
	public void syncAfterSellFill(long accountSeq, String symbol) {
		try {
			Optional<HoldingItem> holding = findHolding(accountSeq, symbol);
			if (holding.isEmpty() || new BigDecimal(holding.get().quantity()).signum() <= 0) {
				cancelIfOpen(accountSeq, symbol);
				return;
			}
			if (conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol).isPresent()) {
				doSync(accountSeq, symbol);
			}
		} catch (Exception e) {
			log.warn("[OCO 정리] {} 매도 체결 후 처리 중 오류로 건너뜁니다: {}", symbol, e.getMessage());
			alertOcoProblem(symbol, "매도 체결 후 OCO 정리 중 오류: " + e.getMessage());
		}
	}

	// 재동기화용: OCO가 없거나 수량이 보유량과 다르면 등록/수정한다. 손댔으면 true
	public boolean ensureOco(long accountSeq, String symbol) {
		try {
			Optional<HoldingItem> holding = findHolding(accountSeq, symbol);
			if (holding.isEmpty() || new BigDecimal(holding.get().quantity()).signum() <= 0) {
				return false;
			}
			Optional<ConditionalOrderDetail> existing = conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol);
			if (existing.isPresent()
					&& new BigDecimal(existing.get().quantity()).compareTo(new BigDecimal(holding.get().quantity())) == 0) {
				return false;
			}
			doSync(accountSeq, symbol);
			return true;
		} catch (Exception e) {
			log.warn("[OCO 확인] {} 처리 중 오류로 건너뜁니다: {}", symbol, e.getMessage());
			alertOcoProblem(symbol, "재동기화 중 OCO를 확인/등록하지 못했습니다: " + e.getMessage());
			return false;
		}
	}

	// 포지션이 0이 되면 남은 OCO를 정리한다
	public void cancelIfOpen(long accountSeq, String symbol) {
		try {
			conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol).ifPresent(detail -> {
				if (!conditionalOrderService.cancelConditionalOrder(accountSeq, detail.conditionalOrderId())) {
					alertOcoProblem(symbol, "남은 OCO를 취소하지 못했습니다");
				}
			});
		} catch (Exception e) {
			log.warn("[OCO 정리] {} 처리 중 오류: {}", symbol, e.getMessage());
			alertOcoProblem(symbol, "남은 OCO를 정리하는 중 오류가 났습니다: " + e.getMessage());
		}
	}

	private void doSync(long accountSeq, String symbol) {
		if (haltSwitch.isHalted()) {
			log.warn("[OCO 갱신] 정지 파일이 있어 {} OCO 등록/수정을 건너뜁니다.", symbol);
			alertOcoProblem(symbol, "정지 파일 때문에 OCO를 등록/수정하지 않았습니다 (이 종목은 손절 보호가 없거나 실제 보유와 다를 수 있음)");
			return;
		}
		Optional<HoldingItem> holding = findHolding(accountSeq, symbol);
		if (holding.isEmpty() || new BigDecimal(holding.get().quantity()).signum() <= 0) {
			log.warn("[OCO 갱신] {} 보유 수량이 없어 건너뜁니다.", symbol);
			return;
		}
		HoldingItem item = holding.get();

		BigDecimal atr14 = atrService.getAtr14(symbol);
		boolean isKrw = "KRW".equals(item.currency());
		AtrOcoPricing.Prices prices = AtrOcoPricing.calculate(new BigDecimal(item.averagePurchasePrice()), atr14, isKrw);
		String expireDate = LocalDate.now().plusDays(EXPIRE_DAYS).toString();

		Optional<ConditionalOrderDetail> existing = conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol);
		ConditionalOrderPlacementResult result;
		if (existing.isPresent()) {
			result = conditionalOrderService.modifyAtrOco(accountSeq, existing.get().conditionalOrderId(), item.quantity(), expireDate,
				prices.takeProfitTrigger(), prices.takeProfitOrderPrice(), prices.stopLossTrigger(), prices.stopLossOrderPrice());
		} else {
			String clientOrderId = "atr-oco-" + symbol + "-" + System.currentTimeMillis();
			result = conditionalOrderService.createAtrOco(accountSeq, clientOrderId, symbol, item.quantity(), expireDate,
				prices.takeProfitTrigger(), prices.takeProfitOrderPrice(), prices.stopLossTrigger(), prices.stopLossOrderPrice());
		}

		// 실패를 조용히 넘기면 보호 장치(손절)가 없거나 실제 보유와 어긋난 채로 보유하게 된다. 반드시 알린다
		if (result != null && result.status() == ConditionalOrderPlacementResult.Status.FAILED) {
			String consequence = existing.isPresent()
				? "OCO 수정에 실패했습니다 (기존 OCO가 그대로 남아 수량과 가격이 실제 보유와 다를 수 있음)"
				: "OCO 등록에 실패했습니다 (손절 보호가 없는 상태)";
			alertOcoProblem(symbol, consequence + ". 사유: " + result.errorMessage());
		}
	}

	private void alertOcoProblem(String symbol, String detail) {
		notifier.send("oco-" + symbol, "[담담] " + symbol + " " + detail + ". 직접 확인하세요.");
	}

	private Optional<HoldingItem> findHolding(long accountSeq, String symbol) {
		List<HoldingItem> items = holdingsService.getHoldings(accountSeq).items();
		return items.stream().filter(i -> i.symbol().equals(symbol)).findFirst();
	}
}
