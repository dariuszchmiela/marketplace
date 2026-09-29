package pl.dch.marketplace.checkout;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.payment.PaymentClient;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.payment.RemotePayment;
import pl.dch.marketplace.session.SessionId;

/**
 * Recovers orders whose payment result is not known ({@code PAYMENT_UNKNOWN}, or a
 * {@code PAYMENT_PENDING} order left behind by a crash) by asking payment-service for the payment
 * stored under the order's payment idempotency key.
 * <ul>
 *   <li>payment found → its result is applied ({@code PAID} or {@code PAYMENT_FAILED});</li>
 *   <li>no payment found → the order is left unchanged. "Not found" is not proof of failure: a slow
 *       request may still be in progress at payment-service and record the payment later.
 *       The stock stays taken and reconciliation can be run again.</li>
 * </ul>
 * Like checkout, the remote call runs outside any database transaction.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final OrderPaymentUpdater orderPaymentUpdater;
    private final PaymentClient paymentClient;

    public PaymentReconciliationService(OrderPaymentUpdater orderPaymentUpdater, PaymentClient paymentClient) {
        this.orderPaymentUpdater = orderPaymentUpdater;
        this.paymentClient = paymentClient;
    }

    public OrderResponse reconcile(SessionId sessionId, long orderId) {
        OrderPaymentUpdater.PaymentTarget target = orderPaymentUpdater.findPaymentTarget(sessionId, orderId);
        OrderResponse order = target.order();
        if (!order.status().isAwaitingPaymentResult()) {
            return order;
        }

        Optional<RemotePayment.Response> payment = paymentClient.findByIdempotencyKey(target.paymentIdempotencyKey());
        if (payment.isEmpty()) {
            log.info("reconciliation.no_payment orderId={} status={} paymentKey={} result=unchanged",
                    orderId, order.status(), target.paymentIdempotencyKey());
            return order;
        }

        RemotePayment.Response found = payment.get();
        requireSamePayment(order, found);
        PaymentOutcome outcome = switch (found.status()) {
            case SUCCEEDED -> new PaymentOutcome.Succeeded(found.paymentId());
            case DECLINED -> new PaymentOutcome.Declined(found.paymentId());
        };
        log.info("reconciliation.payment_found orderId={} paymentId={} paymentStatus={}",
                orderId, found.paymentId(), found.status());
        return orderPaymentUpdater.applyOutcome(orderId, outcome);
    }

    /** Never apply a payment that does not belong to this order: this would indicate a bug or data corruption. */
    private static void requireSamePayment(OrderResponse order, RemotePayment.Response payment) {
        boolean matches = payment.orderId() == order.id()
                && payment.amount() != null && payment.amount().compareTo(order.total()) == 0
                && Order.CURRENCY.equals(payment.currency());
        if (!matches) {
            log.error("reconciliation.mismatch orderId={} orderTotal={} paymentId={} paymentOrderId={} paymentAmount={} paymentCurrency={}",
                    order.id(), order.total(), payment.paymentId(), payment.orderId(), payment.amount(), payment.currency());
            throw new MarketplaceException(ErrorCode.PAYMENT_RECONCILIATION_CONFLICT,
                    "Payment found for order %d does not match the order".formatted(order.id()));
        }
    }
}
