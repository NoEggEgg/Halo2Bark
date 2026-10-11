package com.wuqishi.halo.bark.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.config.SettingsStore;
import com.wuqishi.halo.bark.pipeline.EventPipeline;
import com.wuqishi.halo.bark.pipeline.NoiseReducer;
import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import com.wuqishi.halo.bark.transport.BarkTransport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("运行状态汇总：快照聚合与计数暴露")
class StatusReporterTest {

    private final SettingsStore settingsStore = mock(SettingsStore.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final NoiseReducer noiseReducer = mock(NoiseReducer.class);
    private final EventPipeline pipeline = mock(EventPipeline.class);
    private final BarkTransport transport = mock(BarkTransport.class);
    private final StatusReporter reporter = new StatusReporter(settingsStore, dispatcher, noiseReducer,
            pipeline, transport);

    @Test
    @DisplayName("快照聚合各层计数：丢弃与失败都可被管理员看到")
    void snapshotAggregatesCounters() {
        when(settingsStore.current()).thenReturn(new BarkSettings(PushSettings.defaults(), null,
                List.of("加密密钥缺失"), List.of("设备「手机」的服务地址不是合法地址")));
        when(dispatcher.running()).thenReturn(true);
        when(dispatcher.queueSize()).thenReturn(7);
        when(dispatcher.droppedCount()).thenReturn(2L);
        when(noiseReducer.running()).thenReturn(true);
        when(noiseReducer.overflowSize()).thenReturn(3);
        when(noiseReducer.droppedOverflowCount()).thenReturn(5L);
        when(noiseReducer.quietBufferSize()).thenReturn(11);
        when(noiseReducer.droppedQuietCount()).thenReturn(13L);
        when(pipeline.failedEventCount()).thenReturn(17L);
        when(transport.lastDelivery()).thenReturn(Optional.of(
                new BarkTransport.LastDelivery(Instant.parse("2026-01-01T00:00:00Z"), 2, 1, 1)));

        var snapshot = reporter.snapshot();

        assertThat(snapshot.deliverable()).as("阻断级非空即不可投递").isFalse();
        assertThat(snapshot.blockingIssues()).hasSize(1);
        assertThat(snapshot.warnings()).hasSize(1);
        assertThat(snapshot.dispatcher()).isEqualTo(new StatusSnapshot.Dispatcher(true, 7, 2L));
        assertThat(snapshot.noise()).isEqualTo(new StatusSnapshot.Noise(true, 3, 5L, 11, 13L));
        assertThat(snapshot.failedEvents()).isEqualTo(17L);
        assertThat(snapshot.lastDelivery().deviceCount()).isEqualTo(2);
        assertThat(snapshot.lastDelivery().successCount()).isEqualTo(1);
        assertThat(snapshot.lastDelivery().failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("配置合法且尚未投递过：可投递、无问题、最近投递为空")
    void snapshotWithoutDelivery() {
        when(settingsStore.current()).thenReturn(BarkSettings.defaults());
        when(transport.lastDelivery()).thenReturn(Optional.empty());

        var snapshot = reporter.snapshot();

        assertThat(snapshot.deliverable()).isTrue();
        assertThat(snapshot.lastDelivery()).isNull();
        assertThat(snapshot.blockingIssues()).isEmpty();
        assertThat(snapshot.warnings()).isEmpty();
        assertThat(snapshot.deviceCount()).isZero();
    }

    @Test
    @DisplayName("周期汇总可重复启停，不抛异常")
    void summaryLifecycleIsIdempotent() {
        when(settingsStore.current()).thenReturn(BarkSettings.defaults());
        when(transport.lastDelivery()).thenReturn(Optional.empty());

        reporter.start();
        reporter.start();
        reporter.stop();
        reporter.stop();
    }
}
