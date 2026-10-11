package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.support.MockBarkServer;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 服务端能力探测的集成测试（Mock 服务端）。
 *
 * <p>依据 PRD 5.2：探测到 V2 才走 {@code POST /push}，未探测到则回退 V1；探测结果缓存，不每次发送都探测。
 * 官方服务返回 {@code v2.0.0}、自建服务返回 {@code v2.3.6}，两者字段不同，因此**不得**假设
 * {@code /info} 一定包含 {@code devices} 等扩展字段（见 DEVELOPMENT.md 3.1）。
 */
class BarkServerProbeTest {

    private MockBarkServer server;

    @BeforeEach
    void startServer() {
        server = new MockBarkServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("版本号为 v2.x 时判定支持 V2")
    void detectsV2() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}"));

        var info = newProbe().probe(server.url());

        assertThat(info.reachable()).isTrue();
        assertThat(info.version()).isEqualTo("v2.3.6");
        assertThat(info.v2Supported()).isTrue();
    }

    @Test
    @DisplayName("官方服务的 v2.0.0 同样判定支持 V2")
    void detectsOfficialVersion() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"v2.0.0\"}"));

        assertThat(newProbe().probe(server.url()).v2Supported()).isTrue();
    }

    @Test
    @DisplayName("主版本小于 2 时回退 V1")
    void fallsBackToV1OnOldVersion() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"1.2.0\"}"));

        var info = newProbe().probe(server.url());

        assertThat(info.reachable()).isTrue();
        assertThat(info.v2Supported()).isFalse();
    }

    @Test
    @DisplayName("/info 非 200 时判定不可达并按 V1 处理")
    void unreachableWhenServerRejectsInfo() {
        server.respondStatus(404);

        var info = newProbe().probe(server.url());

        assertThat(info.reachable()).isFalse();
        assertThat(info.v2Supported()).isFalse();
        assertThat(info.failure()).isEqualTo(DeliveryFailure.UNREACHABLE);
        assertThat(info.message()).contains("服务地址不可达");
    }

    @Test
    @DisplayName("/info 缺少 version 字段时按 V1 处理")
    void versionMissingMeansV1() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"devices\":3}"));

        var info = newProbe().probe(server.url());

        assertThat(info.reachable()).isTrue();
        assertThat(info.version()).isEmpty();
        assertThat(info.v2Supported()).isFalse();
    }

    @Test
    @DisplayName("结果被缓存，不会每次发送都探测")
    void cachesResult() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}"));
        var probe = newProbe();

        probe.probe(server.url());
        probe.probe(server.url());

        assertThat(server.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("强制刷新会重新探测（供「测试该设备」使用）")
    void forceRefreshProbesAgain() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}"));
        var probe = newProbe();

        probe.probe(server.url());
        probe.probe(server.url(), true);

        assertThat(server.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("冷缓存并发探测单飞：同一地址只发一次 /info")
    void concurrentProbesAreSingleFlight() throws Exception {
        server.respond(request -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}");
        });
        var probe = newProbe();
        var results = new CopyOnWriteArrayList<Boolean>();
        var start = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            var thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                results.add(probe.probe(server.url()).v2Supported());
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (var thread : threads) {
            thread.join(5_000);
        }

        assertThat(results).hasSize(4).containsOnly(true);
        assertThat(server.count())
                .as("并发遇到冷缓存时按分片锁单飞，不得重复探测同一地址")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("支持 V2 的结论按较长 TTL 缓存，超过后重新探测")
    void positiveResultIsCachedForLongTtl() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"v2.3.6\"}"));
        var clock = new MutableClock();
        var probe = new BarkServerProbe(new BarkHttpClient(), clock);

        probe.probe(server.url());
        clock.advance(BarkConstants.SERVER_PROBE_TTL_MILLIS * 10);
        assertThat(probe.probe(server.url()).v2Supported()).isTrue();
        assertThat(server.count()).as("远未到正向 TTL，不应重新探测").isEqualTo(1);

        clock.advance(BarkConstants.SERVER_PROBE_POSITIVE_TTL_MILLIS);
        probe.probe(server.url());
        assertThat(server.count()).as("超过正向 TTL 后应重新探测，使服务端降级能被发现").isEqualTo(2);
    }

    @Test
    @DisplayName("不支持 V2 的结论只短期缓存，过期后重新探测")
    void negativeResultExpires() {
        server.respond(request -> new MockBarkServer.Response(200, "{\"version\":\"1.0.0\"}"));
        var clock = new MutableClock();
        var probe = new BarkServerProbe(new BarkHttpClient(), clock);

        probe.probe(server.url());
        assertThat(server.count()).isEqualTo(1);

        clock.advance(BarkConstants.SERVER_PROBE_TTL_MILLIS + 1);
        assertThat(probe.probe(server.url()).v2Supported()).isFalse();
        assertThat(server.count()).isEqualTo(2);
    }

    private BarkServerProbe newProbe() {
        return new BarkServerProbe(new BarkHttpClient());
    }

    /** 可推进的时钟，用于验证缓存有效期而不必真实等待。 */
    private static final class MutableClock implements LongSupplier {

        private long nowMillis = 1_700_000_000_000L;

        @Override
        public long getAsLong() {
            return nowMillis;
        }

        void advance(long millis) {
            nowMillis += millis;
        }
    }
}
