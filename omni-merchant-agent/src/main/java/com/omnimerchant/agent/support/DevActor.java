package com.omnimerchant.agent.support;

/**
 * 开发期操作者身份占位。
 *
 * <p>当前工程尚未恢复 JWT / Security / RBAC，无法从登录态取得真实客服 ID。
 * 工单（Ticket/Inbox）与动作审批（Action Approval）等写操作需要一个 actorId，
 * 统一在此提供，避免各模块各自造一套。
 *
 * <p>TODO 恢复登录鉴权后，改为由 JwtPrincipal 提供真实 actorId，并删除本类。
 */
public final class DevActor {

    private DevActor() {
    }

    /** 开发期默认操作者 ID。 */
    public static final Long ID = 1L;

    /** 请求未携带 actorId 时回退到开发期默认值。 */
    public static Long resolve(Long requested) {
        return requested == null ? ID : requested;
    }
}
