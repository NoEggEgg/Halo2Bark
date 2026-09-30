package com.wuqishi.halo.bark;

import com.wuqishi.halo.bark.config.SettingsStore;
import com.wuqishi.halo.bark.pipeline.NoiseReducer;
import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Halo2Bark 插件入口，承载插件生命周期钩子。
 *
 * <p>站点事件采集、事件适配、降噪、内容渲染与 Bark 投递均由各自模块承载，不在本类实现；
 * 本类只负责在启停时驱动配置加载、降噪层与投递线程池的启停。
 */
@Slf4j
@Component
public class BarkPlugin extends BasePlugin {

    private final SettingsStore settingsStore;
    private final NotificationDispatcher dispatcher;
    private final NoiseReducer noiseReducer;

    public BarkPlugin(PluginContext pluginContext, SettingsStore settingsStore,
            NotificationDispatcher dispatcher, NoiseReducer noiseReducer) {
        super(pluginContext);
        this.settingsStore = settingsStore;
        this.dispatcher = dispatcher;
        this.noiseReducer = noiseReducer;
    }

    @Override
    public void start() {
        settingsStore.loadInitial();
        dispatcher.start();
        noiseReducer.start();
        log.info("{} 插件已启动", BarkConstants.LOG_PREFIX);
    }

    @Override
    public void stop() {
        // 顺序不可调换：dispatcher.stop() 会等待在途投递任务，而这些任务仍会调用
        // NoiseReducer.accept()；先关降噪层的定时器与补发执行器会让在途事件滞留或被拒绝。
        dispatcher.stop();
        noiseReducer.stop();
        log.info("{} 插件已停止", BarkConstants.LOG_PREFIX);
    }
}
