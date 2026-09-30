package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.PushNotification;

/**
 * 被降噪层暂存的一条通知：附带判定语义（用于摘要文案）与**入队时刻**的配置快照。
 *
 * <p>不可变，可安全跨线程传递（PRD 10.4.2）。
 */
record BufferedNotification(Judgement judgement, PushNotification notification,
        PushSettings settings) {
}
