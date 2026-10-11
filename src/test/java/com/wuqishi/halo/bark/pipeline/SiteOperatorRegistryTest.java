package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.extension.ExtensionClient;

@DisplayName("站点操作者识别：super-role 绑定、失败兜底与缓存")
class SiteOperatorRegistryTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final MutableClock clock = new MutableClock(START);
    private final SiteOperatorRegistry registry = new SiteOperatorRegistry(client, clock);

    /** 可推进的时钟，用于验证 TTL 到期后重新查询。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static RoleBinding binding(String roleName, String... usernames) {
        var roleRef = new RoleBinding.RoleRef();
        roleRef.setKind("Role");
        roleRef.setName(roleName);
        var subjects = new ArrayList<RoleBinding.Subject>();
        for (var username : usernames) {
            var subject = new RoleBinding.Subject();
            subject.setKind("User");
            subject.setName(username);
            subjects.add(subject);
        }
        var binding = new RoleBinding();
        binding.setRoleRef(roleRef);
        binding.setSubjects(subjects);
        return binding;
    }

    /** 绑定一个不含 subjects 的角色绑定，用于验证空集合不导致异常。 */
    private static RoleBinding bindingWithoutSubjects(String roleName) {
        var roleRef = new RoleBinding.RoleRef();
        roleRef.setKind("Role");
        roleRef.setName(roleName);
        var binding = new RoleBinding();
        binding.setRoleRef(roleRef);
        return binding;
    }

    /**
     * 桩：真实 {@code ExtensionClient.list} 会**在存储侧应用谓词**，因此这里必须同样过滤，
     * 否则会把非 super-role 绑定的主体也算进来，测不出谓词本身的正确性。
     */
    private void givenBindings(RoleBinding... bindings) {
        when(client.list(eq(RoleBinding.class), any(), any())).thenAnswer(invocation -> {
            Predicate<RoleBinding> predicate = invocation.getArgument(1);
            return Arrays.stream(bindings).filter(predicate).toList();
        });
    }

    @Test
    @DisplayName("绑定到 super-role 的登录用户 → 是站点操作者")
    void superRoleUserIsOperator() {
        givenBindings(binding("super-role", "alice"));
        assertThat(registry.isOperator("User", "alice")).isTrue();
        assertThat(registry.isOperator("user", "alice")).isTrue(); // kind 大小写不敏感
    }

    @Test
    @DisplayName("多个 super-role 绑定的用户都被识别")
    void multipleSuperRoleUsersAreOperators() {
        givenBindings(binding("super-role", "alice"), binding("super-role", "bob"));
        assertThat(registry.isOperator("User", "alice")).isTrue();
        assertThat(registry.isOperator("User", "bob")).isTrue();
    }

    @Test
    @DisplayName("仅绑定普通角色（如 authenticated）的注册读者 → 不是操作者，不得误杀")
    void registeredReaderIsNotOperator() {
        givenBindings(binding("super-role", "alice"), binding("authenticated", "reader"));
        assertThat(registry.isOperator("User", "reader")).isFalse();
    }

    @Test
    @DisplayName("访客邮箱身份 → 不是操作者，且不触发查询")
    void emailKindIsNeverOperator() {
        givenBindings(binding("super-role", "alice"));
        assertThat(registry.isOperator("Email", "alice@example.com")).isFalse();
        verify(client, times(0)).list(eq(RoleBinding.class), any(), any());
    }

    @Test
    @DisplayName("空白或空用户名 → 不是操作者，且不触发查询")
    void blankUsernameIsNotOperator() {
        givenBindings(binding("super-role", "alice"));
        assertThat(registry.isOperator("User", null)).isFalse();
        assertThat(registry.isOperator("User", "  ")).isFalse();
        verify(client, times(0)).list(eq(RoleBinding.class), any(), any());
    }

    @Test
    @DisplayName("角色绑定缺少 subjects / roleRef 时不影响其余绑定")
    void malformedBindingsAreSkipped() {
        givenBindings(bindingWithoutSubjects("super-role"), new RoleBinding(),
                binding("super-role", "alice"));
        assertThat(registry.isOperator("User", "alice")).isTrue();
    }

    @Test
    @DisplayName("成功结果被缓存：TTL 内只查询一次，到期后重新查询")
    void successIsCachedUntilTtl() {
        givenBindings(binding("super-role", "alice"));
        assertThat(registry.isOperator("User", "alice")).isTrue();
        assertThat(registry.isOperator("User", "alice")).isTrue();
        verify(client, times(1)).list(eq(RoleBinding.class), any(), any());

        clock.advance(Duration.ofMinutes(11));
        assertThat(registry.isOperator("User", "alice")).isTrue();
        verify(client, times(2)).list(eq(RoleBinding.class), any(), any());
    }

    @Test
    @DisplayName("读取失败 → 按「不抑制」处理（不是操作者），且失败被短时缓存")
    void readFailureIsNotSuppressedAndCachedBriefly() {
        when(client.list(eq(RoleBinding.class), any(), any()))
                .thenThrow(new IllegalStateException("存储抖动"));

        assertThat(registry.isOperator("User", "alice")).isFalse();
        assertThat(registry.isOperator("User", "alice")).isFalse();
        verify(client, times(1)).list(eq(RoleBinding.class), any(), any());

        // 失败只缓存 30 s：到期后应重新尝试读取，恢复正常后立即生效
        clock.advance(Duration.ofSeconds(31));
        givenBindings(binding("super-role", "alice"));
        assertThat(registry.isOperator("User", "alice")).isTrue();
        verify(client, times(2)).list(eq(RoleBinding.class), any(), any());
    }
}
