package com.wuqishi.halo.bark.queue;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.event.SiteEvent;
import java.util.Objects;

/**
 * 一条待处理任务：站点事件 + **入队时刻**的配置快照。
 *
 * <p>在入队时刻取配置快照，使在途任务不受此后的配置热更新影响，行为可预期。
 * 两个字段均为不可变对象，可安全跨线程传递（PRD 10.4.2）。
 *
 * @param event 采集层产出的站点事件（不可变）
 * @param settings 入队时刻的配置快照：推送配置 + 事件开关（不可变）
 */
record DeliveryTask(SiteEvent event, BarkSettings settings) {

    DeliveryTask {
        Objects.requireNonNull(event, "event 不可为空");
        Objects.requireNonNull(settings, "settings 不可为空");
    }
}
