package ecommerce.events;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code events.*} settings for the Sprint 5 catalog-event pipeline.
 *
 * @param enabled publish the outbox to RocketMQ and run the consumers (otherwise Sprint 3's direct indexer runs)
 * @param namesrv RocketMQ name-server address, {@code host:port}
 * @param topic the {@code ProductChanged} topic; it must exist (the broker does not auto-create topics)
 */
@ConfigurationProperties(prefix = "events")
public record EventsProperties(boolean enabled, String namesrv, String topic, Publisher publisher, Consumer consumer) {

    public EventsProperties {
        namesrv = namesrv == null ? "localhost:9876" : namesrv;
        topic = topic == null ? "catalog-product-changed" : topic;
        publisher = publisher == null ? new Publisher(null, null, 0, null, -1) : publisher;
        consumer = consumer == null ? new Consumer(0, 0) : consumer;
    }

    /**
     * @param schedule poll the outbox on a timer (tests turn this off and call {@code publishOnce()} directly)
     * @param pollInterval delay between polls when the outbox was empty
     * @param batchSize rows read per poll
     * @param sendTimeout bound on one synchronous send, including the client's own retries
     * @param retries client-side retries for a failed send before the row is left for the next poll
     */
    public record Publisher(Boolean schedule, Duration pollInterval, int batchSize, Duration sendTimeout,
            int retries) {
        public Publisher {
            schedule = schedule == null ? Boolean.TRUE : schedule;
            pollInterval = pollInterval == null ? Duration.ofMillis(100) : pollInterval;
            batchSize = batchSize <= 0 ? 200 : batchSize;
            sendTimeout = sendTimeout == null ? Duration.ofSeconds(3) : sendTimeout;
            retries = retries < 0 ? 2 : retries;
        }
    }

    /**
     * @param maxReconsumeTimes redeliveries after a failed effect before the broker moves the message to the
     *        group's dead-letter topic ({@code %DLQ%<group>})
     * @param threads consume threads per consumer group
     */
    public record Consumer(int maxReconsumeTimes, int threads) {
        public Consumer {
            maxReconsumeTimes = maxReconsumeTimes <= 0 ? 5 : maxReconsumeTimes;
            threads = threads <= 0 ? 4 : threads;
        }
    }
}
