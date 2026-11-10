package ecommerce.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

/**
 * Sends unpublished outbox rows to RocketMQ as {@code ProductChanged} and marks them published only after the
 * broker accepts them.
 *
 * <p>Delivery is at-least-once. If the backend dies after the broker accepted a message but before
 * {@code published_at} is written, the next poll sends the same event ID again; consumers drop it by ID. A failed
 * or timed-out send stops the batch and leaves the rest of the rows pending for the next poll, in order.
 * One publisher per backend: two backends would only send duplicates, which consumers already tolerate.
 */
@Component
@ConditionalOnProperty(name = "events.enabled", havingValue = "true")
public class OutboxPublisher {

    static final String PRODUCER_GROUP = "catalog-outbox-publisher";
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final CatalogOutbox outbox;
    private final ObjectMapper json;
    private final DefaultMQProducer producer;
    private final String topic;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final Timer publishLag;
    private final Counter published;
    private final Counter failures;

    public OutboxPublisher(CatalogOutbox outbox, ObjectMapper json, EventsProperties props, MeterRegistry meters)
            throws MQClientException {
        this.outbox = outbox;
        this.json = json;
        this.topic = props.topic();
        this.batchSize = props.publisher().batchSize();
        this.sendTimeoutMs = props.publisher().sendTimeout().toMillis();
        this.producer = new DefaultMQProducer(PRODUCER_GROUP);
        producer.setNamesrvAddr(props.namesrv());
        // Own client instance, so a second application context in the same JVM (tests) can't collide with it.
        producer.setInstanceName("shopsphere-" + UUID.randomUUID());
        producer.setSendMsgTimeout((int) sendTimeoutMs);
        producer.setRetryTimesWhenSendFailed(props.publisher().retries());
        producer.start();

        this.publishLag = Timer.builder("events.publish.lag")
                .description("Time from the outbox row committing to the broker accepting its message")
                .publishPercentileHistogram()
                .register(meters);
        this.published = Counter.builder("events.published").register(meters);
        this.failures = Counter.builder("events.publish.failures")
                .description("Polls that stopped early because a send failed or timed out")
                .register(meters);
        Gauge.builder("events.outbox.pending", outbox, CatalogOutbox::pending)
                .description("Outbox rows not yet accepted by the broker").register(meters);
        Gauge.builder("events.outbox.oldest.age", outbox, CatalogOutbox::oldestPendingAgeSeconds)
                .baseUnit("seconds").description("Age of the oldest unpublished outbox row").register(meters);
    }

    /** Publishes up to one batch. Returns how many rows the broker accepted (0 when empty or the first send failed). */
    public int publishOnce() {
        List<ProductChanged> batch = outbox.unpublished(batchSize);
        List<Long> accepted = new ArrayList<>(batch.size());
        try {
            for (ProductChanged event : batch) {
                SendResult result = producer.send(message(event), sendTimeoutMs);
                if (result.getSendStatus() != SendStatus.SEND_OK) {
                    // Accepted, but not flushed/replicated as requested. With ASYNC_FLUSH and one broker this
                    // doesn't happen; treat it as a failure so the row is retried rather than silently trusted.
                    throw new IllegalStateException("send status " + result.getSendStatus());
                }
                accepted.add(event.eventId());
                publishLag.record(Duration.between(event.occurredAt(), Instant.now()));
            }
        } catch (Exception ex) {
            failures.increment();
            log.warn("Outbox publish stopped after {} of {} events; the rest stay pending: {}", accepted.size(),
                    batch.size(), ex.toString());
        } finally {
            outbox.markPublished(accepted);
            published.increment(accepted.size());
        }
        return accepted.size();
    }

    /** Publishes until the outbox is empty or a send fails. Returns the events accepted. */
    public int publishAll() {
        int total = 0;
        int done;
        do {
            done = publishOnce();
            total += done;
        } while (done == batchSize);
        return total;
    }

    /** Sends one event as it is, outside the outbox. Tests use it to deliver a duplicate. */
    void send(ProductChanged event) throws Exception {
        producer.send(message(event), sendTimeoutMs);
    }

    private Message message(ProductChanged event) throws JsonProcessingException {
        Message message = new Message(topic, event.change().name(), json.writeValueAsBytes(event));
        message.setKeys(String.valueOf(event.eventId()));
        return message;
    }

    static ProductChanged parse(ObjectMapper json, byte[] body) throws java.io.IOException {
        ProductChanged event = json.readValue(new String(body, StandardCharsets.UTF_8), ProductChanged.class);
        if (event.schemaVersion() != ProductChanged.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unknown ProductChanged schema version " + event.schemaVersion());
        }
        return event;
    }

    @PreDestroy
    void shutdown() {
        producer.shutdown();
    }
}
