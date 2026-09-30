package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.event.SiteEvent;

/**
 * 投递流水线：把一条 {@link SiteEvent} 加工为可投递的通知并发送。
 *
 * <p>运行在**投递线程池**中，允许阻塞（读取系统设置、获取链接与字段、HTTP 发送）。
 * 采集层只负责产出事件；本接口的实现承担「判定规则 → 事件开关过滤 → 降噪 → 内容获取 →
 * 渲染 → 传输」的全部工作（PRD 6.1、10.4.1）。
 *
 * <p>本接口定义在流水线层，采集层与队列层只依赖它，依赖方向保持单向（PRD 10.4.1）。
 */
@FunctionalInterface
public interface NotificationPipeline {

    /**
     * 处理一条站点事件。
     *
     * <p>实现**不得**向调用方抛出异常——异常应就地消化，避免影响投递线程（PRD 10.4.3）。
     *
     * @param event 采集层产出的事件（不可变）
     * @param settings 入队时刻的配置快照：推送配置 + 事件开关（不可变）
     */
    void process(SiteEvent event, BarkSettings settings);
}
