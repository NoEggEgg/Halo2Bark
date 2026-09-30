package com.wuqishi.halo.bark;

/**
 * 插件级常量。
 *
 * <p>魔法值（超时、阈值、上限、日志前缀）统一在此声明，禁止散落硬编码（PRD 10.4.4）。
 */
public final class BarkConstants {

    /** 日志前缀，便于在 Halo 日志中过滤本插件的输出。 */
    public static final String LOG_PREFIX = "[Halo2Bark]";

    /** 连接超时（毫秒）。PRD 5.2：连接超时 3 s。 */
    public static final long CONNECT_TIMEOUT_MILLIS = 3_000L;

    /** 读取超时（毫秒）。PRD 5.2：读取超时 5 s。 */
    public static final long READ_TIMEOUT_MILLIS = 5_000L;

    /**
     * 单次 HTTP 请求的总超时（毫秒）。
     *
     * <p>JDK {@code HttpClient} 不提供「每读超时」，只有覆盖整次交换的请求超时，
     * 因此以「连接 + 读取」之和近似 PRD 5.2 的两个超时。
     */
    public static final long REQUEST_TIMEOUT_MILLIS = CONNECT_TIMEOUT_MILLIS + READ_TIMEOUT_MILLIS;

    /** 安全事件的最大重试次数（PRD 5.2）。 */
    public static final int SECURITY_MAX_RETRIES = 3;

    /** 其余事件的最大重试次数（PRD 5.2）。 */
    public static final int STANDARD_MAX_RETRIES = 2;

    /**
     * 重试退避间隔（毫秒），按序取用：安全事件取满 3 档，其余取前 2 档（PRD 5.2：1 s / 5 s / 15 s）。
     */
    public static final long[] RETRY_BACKOFF_MILLIS = {1_000L, 5_000L, 15_000L};

    /** 读取站点信息（站点标题 / Logo）允许的阻塞等待上限（毫秒）。 */
    public static final long SITE_INFO_TIMEOUT_MILLIS = 2_000L;

    /**
     * 服务端探测结果的**负向缓存**有效期（毫秒）：10 分钟。
     *
     * <p>「支持 V2」是稳定结论，可长期缓存；但「不可达 / 不支持 V2」可能只是瞬时网络问题或
     * 服务端尚未升级，若永久缓存会把该地址钉死在 V1 回退与禁用批量上，直到插件重启。
     */
    public static final long SERVER_PROBE_TTL_MILLIS = 600_000L;

    /** 一天对应的秒数，用于把归档保留时长从「天」换算为 Bark 要求的「秒」。 */
    public static final long SECONDS_PER_DAY = 86_400L;

    /**
     * 投递队列容量上限。
     *
     * <p>队列有界是硬要求（PRD 5.1、10.4.1）：溢出时按「丢弃优先级最低且最旧」处理并计数，
     * 不无限堆积。容量取值需能吸收一次评论爆发（PRD 13.2 以 1000 次高频事件做冲击验证）。
     */
    public static final int DELIVERY_QUEUE_CAPACITY = 1_000;

    /**
     * 投递线程池线程数。
     *
     * <p>传输层使用阻塞式 HTTP 客户端，必须在专用投递线程池执行，不得占用事件线程或
     * Reactor 调度线程（PRD 10.4.2）。池大小决定可并发的投递条数，与设备数无关。
     */
    public static final int DELIVERY_THREADS = 4;

    /**
     * 插件停用时等待在途投递任务结束的上限（毫秒）。
     *
     * <p>必须 ≥ {@link #REQUEST_TIMEOUT_MILLIS}：单次 Bark 请求最长可达连接 + 读取之和（8 s），
     * 若等待上限小于请求超时，耗时 5–8 s 的在途投递会在 {@code shutdownNow()} 处被强制中断并丢弃。
     * 此处取 9 s，确保最长在途请求能正常完成（PRD 5.1 原定 5 s，与请求超时冲突，以不丢通知为准）。
     */
    public static final long SHUTDOWN_WAIT_MILLIS = 9_000L;

    /** 投递线程名前缀，便于在日志与线程转储中识别。 */
    public static final String DELIVERY_THREAD_NAME_PREFIX = "halo2bark-delivery-";

    private BarkConstants() {
    }
}
