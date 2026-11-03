package cn.sec.hn.openbackupsaas.journal.domain;

/** 动作主体类型。{@code SYSTEM} 表示没有可信身份的后台路径（任务、时钟触发）。 */
public enum ActorType {
    USER,
    SYSTEM,
    TASK,
    CHANNEL
}
