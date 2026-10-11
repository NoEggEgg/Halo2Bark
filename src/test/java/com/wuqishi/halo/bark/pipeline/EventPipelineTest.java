package com.wuqishi.halo.bark.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.config.EventToggles;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.content.CommentAuthorResolver;
import com.wuqishi.halo.bark.content.ResolvedSubject;
import com.wuqishi.halo.bark.content.SubjectResolver;
import com.wuqishi.halo.bark.event.SiteEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.infra.ExternalLinkProcessor;

@DisplayName("事件流水线：去重 → 判定 → 开关 → 适配 → 降噪")
class EventPipelineTest {

    private final EventJudge judge = mock(EventJudge.class);
    private final NoiseReducer noiseReducer = mock(NoiseReducer.class);
    private final SubjectResolver resolver = event -> new ResolvedSubject("来源标题", "https://example.com/p/1");
    private final CommentAuthorResolver authorResolver = new CommentAuthorResolver() {
        @Override
        public String avatarUrl(String ownerKind, String ownerName) {
            return "";
        }

        @Override
        public String quotedReplyAuthorName(String quoteReplyName) {
            return "";
        }
    };
    private final EventPipeline pipeline = new EventPipeline(new RecentEventDeduplicator(), judge,
            new NotificationComposer(resolver, authorResolver, mock(ExternalLinkProcessor.class)),
            noiseReducer);

    private static BarkSettings allEnabled() {
        return new BarkSettings(PushSettings.defaults(),
                new EventToggles(true, true, true, true, true, true, true));
    }

    private static SiteEvent event(String name) {
        return new SiteEvent.CommentCreated(name, null, "User", "bob", "Bob", "hi", false, null);
    }

    @Test
    @DisplayName("判定通过且开关开启 → 交给降噪层")
    void handsOverWhenJudgedAndEnabled() {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));

        pipeline.process(event("c-1"), allEnabled());

        verify(noiseReducer).accept(eq(Judgement.NEW_COMMENT), any(), any());
    }

    @Test
    @DisplayName("判定为不通知 → 不交给降噪层")
    void dropsWhenJudgedEmpty() {
        when(judge.judge(any())).thenReturn(Optional.empty());

        pipeline.process(event("c-1"), allEnabled());

        verify(noiseReducer, never()).accept(any(), any(), any());
    }

    @Test
    @DisplayName("配置非法 → 停止投递，且不进入判定")
    void dropsWhenConfigInvalid() {
        var invalid = new BarkSettings(PushSettings.defaults(),
                new EventToggles(true, true, true, true, true, true, true),
                List.of("加密密钥必须是 16 个字节（AES128 要求）"));

        pipeline.process(event("c-1"), invalid);

        verify(noiseReducer, never()).accept(any(), any(), any());
        verify(judge, never()).judge(any());
    }

    @Test
    @DisplayName("配置恢复合法 → 恢复投递")
    void resumesWhenConfigBecomesValid() {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));
        var invalid = new BarkSettings(PushSettings.defaults(),
                new EventToggles(true, true, true, true, true, true, true),
                List.of("加密密钥必须是 16 个字节（AES128 要求）"));

        pipeline.process(event("c-1"), invalid);
        pipeline.process(event("c-2"), allEnabled());

        verify(noiseReducer).accept(eq(Judgement.NEW_COMMENT), any(), any());
    }

    @Test
    @DisplayName("事件开关关闭 → 不交给降噪层")
    void dropsWhenToggleOff() {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));
        var settings = new BarkSettings(PushSettings.defaults(),
                new EventToggles(false, true, true, true, true, true, true));

        pipeline.process(event("c-1"), settings);

        verify(noiseReducer, never()).accept(any(), any(), any());
    }

    @Test
    @DisplayName("窗口内重复事件只交给降噪层一次")
    void deduplicatesWithinWindow() {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));

        pipeline.process(event("c-1"), allEnabled());
        pipeline.process(event("c-1"), allEnabled());

        verify(noiseReducer, times(1)).accept(any(), any(), any());
    }

    @Test
    @DisplayName("Concurrent copies cannot both enter delivery")
    void concurrentCopiesAreClaimedOnlyOnce() throws Exception {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(noiseReducer).accept(any(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> pipeline.process(event("c-1"), allEnabled()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> pipeline.process(event("c-1"), allEnabled()));
            second.get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            pipeline.process(event("c-1"), allEnabled());
            verify(noiseReducer, times(1)).accept(any(), any(), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Failed handoff releases claim for subsequent retry")
    void failedHandoffCanRetry() {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));
        doThrow(new IllegalStateException("boom")).doNothing()
                .when(noiseReducer).accept(any(), any(), any());

        pipeline.process(event("c-1"), allEnabled());
        pipeline.process(event("c-1"), allEnabled());

        verify(noiseReducer, times(2)).accept(any(), any(), any());
    }

    @Test
    @DisplayName("降噪层抛异常时就地消化，不向外抛出")
    void reducerFailureIsSwallowed() {
        when(judge.judge(any())).thenReturn(Optional.of(Judgement.NEW_COMMENT));
        doThrow(new IllegalStateException("boom")).when(noiseReducer).accept(any(), any(), any());

        pipeline.process(event("c-1"), allEnabled());
    }
}
