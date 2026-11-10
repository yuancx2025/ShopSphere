package ecommerce.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

/**
 * One consumer group on the {@code ProductChanged} topic. Each message is handled alone: parse, then
 * {@link EffectLedger#applyOnce} the group's effect, then acknowledge. A failed effect returns
 * {@code RECONSUME_LATER}; after {@code events.consumer.max-reconsume-times} the broker moves the message to
 * {@code %DLQ%<group>}.
 */
abstract class ProductChangedConsumer {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final String group;
    private final EffectLedger ledger;
    private final ObjectMapper json;
    private final DefaultMQPushConsumer consumer;
    private final Timer effectLag;
    private final Timer consumeLag;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter failures;

    ProductChangedConsumer(String group, EffectLedger ledger, ObjectMapper json, EventsProperties props,
            MeterRegistry meters) throws MQClientException {
        this.group = group;
        this.ledger = ledger;
        this.json = json;
        this.effectLag = Timer.builder("events.effect.lag").tag("consumer", group)
                .description("Time from the outbox row committing to this group's effect committing")
                .publishPercentileHistogram().register(meters);
        this.consumeLag = Timer.builder("events.consume.lag").tag("consumer", group)
                .description("Time from the publisher sending the message to this group's effect committing")
                .publishPercentileHistogram().register(meters);
        this.applied = Counter.builder("events.effects").tag("consumer", group).register(meters);
        this.duplicates = Counter.builder("events.duplicates").tag("consumer", group)
                .description("Deliveries skipped because the event was already applied").register(meters);
        this.failures = Counter.builder("events.effect.failures").tag("consumer", group).register(meters);

        consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(props.namesrv());
        consumer.setInstanceName("shopsphere-" + UUID.randomUUID());
        // A new group reads the topic from the start; ids already applied are skipped by the ledger.
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMaxReconsumeTimes(props.consumer().maxReconsumeTimes());
        consumer.setConsumeThreadMin(props.consumer().threads());
        consumer.setConsumeThreadMax(props.consumer().threads());
        consumer.subscribe(props.topic(), "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> consume(messages));
        consumer.start();
    }

    /** Applies this group's effect for the current state of the product. Must be idempotent. */
    protected abstract void apply(ProductChanged event) throws Exception;

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            ProductChanged event;
            try {
                event = OutboxPublisher.parse(json, message.getBody());
            } catch (Exception ex) {
                failures.increment();
                log.warn("{}: unreadable message {} (attempt {}): {}", group, message.getKeys(),
                        message.getReconsumeTimes() + 1, ex.toString());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            try {
                boolean first = ledger.applyOnce(group, event, () -> {
                    try {
                        apply(event);
                    } catch (RuntimeException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
                if (first) {
                    applied.increment();
                    Instant now = Instant.now();
                    effectLag.record(Duration.between(event.occurredAt(), now));
                    consumeLag.record(Duration.ofMillis(Math.max(0, now.toEpochMilli() - message.getBornTimestamp())));
                } else {
                    duplicates.increment();
                }
            } catch (RuntimeException ex) {
                failures.increment();
                log.warn("{}: event {} failed (attempt {}), will retry: {}", group, event.eventId(),
                        message.getReconsumeTimes() + 1, ex.toString());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    public String group() {
        return group;
    }

    @PreDestroy
    void shutdown() {
        consumer.shutdown();
    }
}
