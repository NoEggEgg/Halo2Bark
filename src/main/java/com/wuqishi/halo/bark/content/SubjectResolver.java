package com.wuqishi.halo.bark.content;

import com.wuqishi.halo.bark.event.SiteEvent;

/**
 * 解析事件所属主体的来源信息（标题与链接），供通知的副标题与点击跳转使用（PRD 5.5）。
 *
 * <p>实现会执行数据查询与链接绝对化，因此**只能在投递线程调用**，不得在事件线程或采集层调用。
 */
public interface SubjectResolver {

    /**
     * @return 来源标题与已绝对化的链接；无来源时返回 {@link ResolvedSubject#EMPTY}（链接为空串）
     */
    ResolvedSubject resolve(SiteEvent event);
}
