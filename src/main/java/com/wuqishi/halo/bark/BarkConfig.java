package com.wuqishi.halo.bark;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.config.SettingsStore;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 插件级 Spring 装配。
 *
 * <p>{@link endpoint.DeviceTester} 以 {@code Supplier<PushSettings>} 形式获取全局推送配置，
 * 以便在没有 Halo 运行时也能单元测试（PRD 10.4.5）。这里把该 Supplier 绑定到已保存配置的快照，
 * 即「测试端点使用已保存的全局配置 + 表单中未保存的设备值」（PRD 5.6）。
 *
 * <p>{@link queue.NotificationDispatcher} 以 {@code Supplier<BarkSettings>} 形式在**入队时刻**
 * 取整体配置快照（推送配置 + 事件开关），供投递线程上的流水线完成开关过滤与投递。
 */
@Configuration
public class BarkConfig {

    @Bean
    Supplier<PushSettings> pushSettingsSupplier(SettingsStore settingsStore) {
        return () -> settingsStore.current().push();
    }

    @Bean
    Supplier<BarkSettings> barkSettingsSupplier(SettingsStore settingsStore) {
        return settingsStore::current;
    }

    /**
     * 当前配置快照的校验问题集合（为空表示合法）。
     *
     * <p>供「测试该设备」端点在配置非法时把具体问题直接返回给管理员，使配置错误可见
     * （配置页保存动作无法被插件拦截，测试端点是用户能立刻看到反馈的入口）。
     */
    @Bean
    Supplier<List<String>> configIssuesSupplier(SettingsStore settingsStore) {
        return () -> settingsStore.current().issues();
    }
}
