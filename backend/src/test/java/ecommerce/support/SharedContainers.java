package ecommerce.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * Containers shared by every test class in the JVM, each started on first use and removed by Ryuk at exit, so two
 * test classes that need Elasticsearch don't start two of them. Tests that pause a container unpause it in a
 * {@code finally} block.
 */
public final class SharedContainers {

    public static final String PRODUCT_TOPIC = "catalog-product-changed";

    private SharedContainers() {
    }

    public static ElasticsearchContainer elasticsearch() {
        return Es.CONTAINER;
    }

    public static GenericContainer<?> redis() {
        return Redis.CONTAINER;
    }

    /** One container running a RocketMQ name server and broker, with {@link #PRODUCT_TOPIC} created. */
    public static GenericContainer<?> rocketMq() {
        return RocketMq.CONTAINER;
    }

    public static String rocketMqNamesrv() {
        rocketMq();
        return "127.0.0.1:" + RocketMq.NAMESRV_PORT;
    }

    private static final class Es {
        static final ElasticsearchContainer CONTAINER =
                new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.10.4")
                        .withEnv("xpack.security.enabled", "false")
                        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

        static {
            CONTAINER.start();
        }
    }

    private static final class Redis {
        static final GenericContainer<?> CONTAINER =
                new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

        static {
            CONTAINER.start();
        }
    }

    /**
     * The broker advertises {@code brokerIP1:listenPort} through the name server, and the client connects to exactly
     * that address. So both ports are bound to the same number on the host and inside the container, and the broker
     * advertises 127.0.0.1. The ports are picked free per JVM, so parallel builds don't collide.
     */
    @SuppressWarnings("deprecation") // FixedHostPortGenericContainer: the advertised address needs fixed ports.
    private static final class RocketMq {
        static final int NAMESRV_PORT = freePort();
        static final int BROKER_PORT = freePort();
        static final GenericContainer<?> CONTAINER = new FixedHostPortGenericContainer<>("apache/rocketmq:5.3.2")
                .withFixedExposedPort(NAMESRV_PORT, NAMESRV_PORT)
                .withFixedExposedPort(BROKER_PORT, BROKER_PORT)
                // Applies to the name server, the broker and mqadmin; the start scripts would ask for gigabytes.
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx256m -Xmn128m")
                .withCommand("sh", "-c", String.join(" && ",
                        "printf 'listenPort=" + NAMESRV_PORT + "\\n' > /tmp/namesrv.conf",
                        "(sh mqnamesrv -c /tmp/namesrv.conf &)",
                        "printf 'brokerClusterName=test\\nbrokerName=broker-a\\nbrokerId=0\\nbrokerIP1=127.0.0.1\\n"
                                + "listenPort=" + BROKER_PORT + "\\nnamesrvAddr=127.0.0.1:" + NAMESRV_PORT + "\\n"
                                + "autoCreateTopicEnable=false\\n' > /tmp/broker.conf",
                        "exec sh mqbroker -c /tmp/broker.conf"))
                .waitingFor(Wait.forLogMessage(".*The broker\\[broker-a.*boot success.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));

        static {
            CONTAINER.start();
            // "boot success" is logged before the broker's first registration with the name server, so the
            // cluster can be unknown for a few seconds. Retry until the topic is created.
            String output = "";
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (System.nanoTime() < deadline) {
                    var result = CONTAINER.execInContainer("sh", "mqadmin", "updateTopic", "-n",
                            "127.0.0.1:" + NAMESRV_PORT, "-c", "test", "-t", PRODUCT_TOPIC);
                    output = result.getStdout() + result.getStderr();
                    if (result.getStdout().contains("success")) {
                        break;
                    }
                    Thread.sleep(1_000);
                }
            } catch (IOException | InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
            if (!output.contains("success")) {
                throw new IllegalStateException("Creating the RocketMQ topic failed: " + output);
            }
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
