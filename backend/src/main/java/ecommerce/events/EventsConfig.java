package ecommerce.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableConfigurationProperties(EventsProperties.class)
public class EventsConfig {

    /** Polls the outbox on a timer when events are on and scheduling isn't turned off (tests publish by hand). */
    @Configuration
    @EnableScheduling
    @ConditionalOnExpression("${events.enabled:false} and ${events.publisher.schedule:true}")
    static class PublisherSchedule {

        private final OutboxPublisher publisher;

        PublisherSchedule(OutboxPublisher publisher) {
            this.publisher = publisher;
        }

        /** Publishes until the outbox is empty (or a send fails), then waits one poll interval. */
        // ISO-8601, as in SearchConfig: this Spring version's @Scheduled doesn't parse "100ms".
        @Scheduled(fixedDelayString = "${events.publisher.poll-interval:PT0.1S}", initialDelayString = "PT1S")
        void publish() {
            publisher.publishAll();
        }
    }
}
