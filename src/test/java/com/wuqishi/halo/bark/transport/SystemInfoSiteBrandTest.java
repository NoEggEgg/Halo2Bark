package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.infra.SystemInfo;
import run.halo.app.infra.SystemInfoGetter;

@DisplayName("站点品牌读取：单次投递内只阻塞读取一次")
class SystemInfoSiteBrandTest {

    private final SystemInfoGetter getter = mock(SystemInfoGetter.class);

    @Test
    @DisplayName("title 与 logo 在同一窗口内复用同一份读取结果，只阻塞一次")
    void coalescesWithinTtl() {
        var info = new SystemInfo();
        info.setTitle("我的站点");
        info.setLogo("https://example.com/logo.png");
        when(getter.get()).thenReturn(Mono.just(info));

        var brand = new SystemInfoSiteBrand(getter);
        assertThat(brand.title()).isEqualTo("我的站点");
        assertThat(brand.logo()).isEqualTo("https://example.com/logo.png");

        // 一次投递会分别取标题与图标；缓存使两次调用只触发一次底层阻塞读取
        verify(getter, times(1)).get();
    }

    @Test
    @DisplayName("读取失败返回空且缓存失败结果，避免每次投递都阻塞")
    void failureReturnsEmptyAndCaches() {
        when(getter.get()).thenReturn(Mono.error(new RuntimeException("boom")));
        var brand = new SystemInfoSiteBrand(getter);
        assertThat(brand.title()).isEmpty();
        assertThat(brand.logo()).isEmpty();
        verify(getter, times(1)).get();
    }
}
