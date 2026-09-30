package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bark 服务端版本探测：严格解析主版本号，杂项字符串一律回退 V1")
class ServerInfoTest {

    @Test
    @DisplayName("v2.3.6 判定支持 V2")
    void v2WithPrefix() {
        var info = ServerInfo.reachable("v2.3.6");
        assertThat(info.v2Supported()).isTrue();
        assertThat(info.version()).isEqualTo("v2.3.6");
    }

    @Test
    @DisplayName("纯数字主版本 2.0.0 判定支持 V2")
    void v2WithoutPrefix() {
        assertThat(ServerInfo.reachable("2.0.0").v2Supported()).isTrue();
    }

    @Test
    @DisplayName("主版本 1.x 不支持 V2")
    void v1NotSupported() {
        assertThat(ServerInfo.reachable("1.2.3").v2Supported()).isFalse();
        assertThat(ServerInfo.reachable("v1").v2Supported()).isFalse();
    }

    @Test
    @DisplayName("主版本 3.x 同样支持 V2（更高主版本向后兼容）")
    void v3Supported() {
        assertThat(ServerInfo.reachable("v3.1.0").v2Supported()).isTrue();
    }

    @Test
    @DisplayName("release-2026 这类杂项字符串不被误判为 V2")
    void garbageStringNotV2() {
        assertThat(ServerInfo.reachable("release-2026").v2Supported()).isFalse();
    }

    @Test
    @DisplayName("带预发布后缀的字符串不被误判（1.2.3-beta）")
    void preReleaseSuffixNotV2() {
        assertThat(ServerInfo.reachable("1.2.3-beta").v2Supported()).isFalse();
    }

    @Test
    @DisplayName("空版本视为不可达回退，不抛异常")
    void emptyVersionDefaultsToV1() {
        assertThat(ServerInfo.reachable("").v2Supported()).isFalse();
        assertThat(ServerInfo.reachable("   ").v2Supported()).isFalse();
    }
}
