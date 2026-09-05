package ecommerce.order;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ecommerce.order.model.Order;
import ecommerce.order.model.OrderStatus;
import ecommerce.order.repository.OrderRepository;

/**
 * Repairs orders left PENDING because a webhook never arrived.
 *
 * <p>Webhook delivery is the normal path, but it is not guaranteed: the endpoint can be
 * misconfigured, the secret can be wrong, or a delivery can be lost. Without this sweep such an
 * order holds its reserved stock forever, so the inventory is silently lost to nobody.
 *
 * <p>Only orders older than the payment session lifetime plus a grace period are touched, so a
 * customer who is still on the hosted checkout page is never disturbed.
 */
@Component
@ConditionalOnProperty(name = "payments.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class CheckoutReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReconciliationJob.class);

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final Duration staleAfter;
    private final int batchSize;

    public CheckoutReconciliationJob(
            OrderService orderService,
            OrderRepository orderRepository,
            @Value("${payments.reconciliation.stale-after-seconds:2700}") long staleAfterSeconds,
            @Value("${payments.reconciliation.batch-size:50}") int batchSize) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${payments.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${payments.reconciliation.interval-ms:300000}")
    public void sweep() {
        Date cutoff = Date.from(Instant.now().minus(staleAfter));
        List<Order> stale =
                orderRepository.findStaleByStatus(OrderStatus.PENDING, cutoff, PageRequest.of(0, batchSize));
        if (stale.isEmpty()) {
            return;
        }

        log.info("Reconciling {} stale pending order(s) older than {}", stale.size(), staleAfter);
        for (Order order : stale) {
            try {
                OrderStatus resolved = orderService.reconcileStaleOrder(order.getId());
                if (resolved != null) {
                    log.info("Reconciled order {} to {}", order.getId(), resolved);
                }
            } catch (RuntimeException ex) {
                // One bad order must not stop the sweep; the next run retries it.
                log.error("Reconciliation failed for order {}", order.getId(), ex);
            }
        }
    }
}
