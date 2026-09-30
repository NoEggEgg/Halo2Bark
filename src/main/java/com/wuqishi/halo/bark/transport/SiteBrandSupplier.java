package com.wuqishi.halo.bark.transport;

/**
 * 站点品牌信息（标题与 Logo）的提供者。
 *
 * <p>存在理由：通知分组留空时取站点标题、图标留空时取站点 Logo（PRD 5.2、10.6.1），
 * 而两者的读取依赖 Halo 运行时。把它抽象为独立接口后，传输层本身不依赖 Halo，
 * 可在无 Halo 环境下单元测试（PRD 10.4.5）。
 *
 * <p>实现必须是**阻塞安全**的：调用方是投递线程。若实现内部使用 Reactor，
 * 不得在 Reactor 的非阻塞线程上调用本接口（会触发 {@code block()} 的线程检查）。
 */
public interface SiteBrandSupplier {

    /** 站点标题；读取失败返回空字符串，由调用方回退到 {@code PushSettings#GROUP_FALLBACK}。 */
    String title();

    /** 站点 Logo 地址；读取失败返回空字符串，此时不下发 icon 参数，由 Bark 显示默认图标。 */
    String logo();
}
