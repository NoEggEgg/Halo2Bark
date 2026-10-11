package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.extension.ExtensionClient;

/**
 * 站点操作者识别：判断一条评论 / 回复是不是**站点管理员自己**发出的（PRD 4.3 规则 1）。
 *
 * <p><b>存在必要性</b>：仅按「评论者是否是被评论主体的作者」判断无法覆盖全部情形——第三方注册的
 * 评论主体（友链插件、瞬间等）不提供主体作者，多作者站点里被评论主体的作者也不是管理员本人，
 * 这两种情形下管理员自己发出的评论与回复都会被推送到管理员自己的设备。
 *
 * <p>本类把依据换成「评论者是否为**站点操作者**」，与评论主体类型无关：站点所有者在创建时即绑定到
 * Halo 的系统保留角色 {@code super-role}，因此「绑定到 {@code super-role} 的登录用户」即站点操作者。
 *
 * <p>操作者集合整体缓存：集合不随评论量变化，逐条事件查询纯属浪费；成功结果按
 * {@link #TTL} 缓存，失败只按 {@link #FAILURE_TTL} 短时缓存，避免一次瞬时存储故障导致逐条事件
 * 反复重试并刷爆日志。
 *
 * <p><b>失败语义</b>：读取失败一律返回「不是操作者」（**不抑制**）——宁可多推一条，也不因一次
 * 存储抖动吞掉真实通知。
 *
 * <p>本类执行数据查询，**只允许在投递线程调用**。
 */
@Slf4j
@Component
public class SiteOperatorRegistry {

    /** Halo 的系统保留角色，站点所有者绑定于它。 */
    static final String SUPER_ROLE = "super-role";

    private static final String USER_KIND = "User";

    /** 成功结果的缓存时长。 */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** 失败结果的短时缓存：既避免故障瞬间疯狂重试，又能尽快恢复读取。 */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);

    private final ExtensionClient client;
    private final Clock clock;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Autowired
    public SiteOperatorRegistry(ExtensionClient client) {
        this(client, Clock.systemUTC());
    }

    SiteOperatorRegistry(ExtensionClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * 该身份是否为站点操作者。
     *
     * <p>只认登录用户账号（{@code kind == "User"}）：访客邮箱是**评论表单的输入值**，
     * 任何人都可以填写管理员的邮箱，据此抑制通知会误杀真实访客评论。
     *
     * @param ownerKind 评论者 / 回复者类型（{@code User} / {@code Email}）
     * @param ownerName 评论者 / 回复者标识（用户名或邮箱）
     * @return 非登录用户、空白标识或读取失败时均为 {@code false}
     */
    public boolean isOperator(String ownerKind, String ownerName) {
        if (!USER_KIND.equalsIgnoreCase(ownerKind) || ownerName == null || ownerName.isBlank()) {
            return false;
        }
        return operators().contains(ownerName);
    }

    /** 站点操作者用户名集合；带 TTL 缓存。 */
    private Set<String> operators() {
        var now = clock.instant();
        var cached = cache.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.names();
        }
        try {
            var names = client.list(RoleBinding.class, SiteOperatorRegistry::isSuperRoleBinding,
                            Comparator.comparing((RoleBinding binding) -> binding.getMetadata().getName()))
                    .stream()
                    .flatMap(binding -> subjectsOf(binding).stream())
                    .filter(subject -> USER_KIND.equalsIgnoreCase(subject.getKind()))
                    .map(RoleBinding.Subject::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
            cache.set(new Cached(names, now.plus(TTL)));
            return names;
        } catch (RuntimeException e) {
            log.warn("{} 读取站点操作者失败，本次按「不抑制」处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            cache.set(new Cached(Set.of(), now.plus(FAILURE_TTL)));
            return Set.of();
        }
    }

    /** 是否绑定到 {@code super-role}；绑定缺少 roleRef 时按「不是」处理。 */
    private static boolean isSuperRoleBinding(RoleBinding binding) {
        if (binding == null || binding.getRoleRef() == null) {
            return false;
        }
        return SUPER_ROLE.equals(binding.getRoleRef().getName());
    }

    private static List<RoleBinding.Subject> subjectsOf(RoleBinding binding) {
        return binding.getSubjects() == null ? List.of() : binding.getSubjects();
    }

    /** 缓存条目：成功按 {@link #TTL}、失败按 {@link #FAILURE_TTL}。 */
    private record Cached(Set<String> names, Instant expiresAt) {
    }
}
