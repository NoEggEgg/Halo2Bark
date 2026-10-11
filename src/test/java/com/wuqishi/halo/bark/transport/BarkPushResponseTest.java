package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BarkPushResponseTest {

    @Test
    void associatesReorderedResultsWithRequestedKeys() {
        var parsed = BarkPushResponse.parse(
                "{\"code\":200,\"data\":[{\"device_key\":\"B\",\"code\":400},"
                        + "{\"device_key\":\"A\",\"code\":200}]}", List.of("A", "B"));

        assertThat(parsed).isNotNull();
        assertThat(parsed.deviceCodes()).containsExactly(200, 400);
    }

    @Test
    void rejectsAmbiguousOrUnrelatedDeviceResults() {
        assertThat(BarkPushResponse.parse(
                "{\"code\":200,\"data\":[{\"device_key\":\"A\",\"code\":200},"
                        + "{\"device_key\":\"A\",\"code\":400}]}", List.of("A", "B")))
                .isNull();
        assertThat(BarkPushResponse.parse(
                "{\"code\":200,\"data\":[{\"device_key\":\"A\",\"code\":200},"
                        + "{\"device_key\":\"C\",\"code\":200}]}", List.of("A", "B")))
                .isNull();
        assertThat(BarkPushResponse.parse(
                "{\"code\":200,\"data\":[{\"device_key\":\"A\",\"code\":200},"
                        + "{\"device_key\":\"A\",\"code\":200}]}", List.of("A", "A")))
                .isNull();
    }
}
