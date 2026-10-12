package com.omnimerchant.knowledge.service;

import com.omnimerchant.tenant.context.TenantContextHolder;

/**
 * 政策知识库的租户可见性范围。
 *
 * <p>PgVector 使用独立的 PostgreSQL 数据源，MyBatis 的 {@code TenantLineInnerInterceptor}
 * <b>不能</b>自动限制其查询，因此租户过滤必须在检索路径上手动实现：
 * <ul>
 *   <li>{@link #GLOBAL_TENANT_ID}（0）：全局公开政策语料，所有租户可见；</li>
 *   <li>真实 {@code tenantId}：租户私有政策，仅该租户可见。</li>
 * </ul>
 *
 * <p>检索时可见范围 = <b>全局语料</b> + <b>当前租户的私有语料</b>。缺少租户上下文时
 * 只允许全局语料，绝不退化为“无过滤”（fail-closed）。
 *
 * <p><b>当前演示语料说明</b>：仓库内置的 policy/*.txt 全部以
 * {@link #GLOBAL_TENANT_ID} 入库，属于<b>共享演示语料</b>，不是任何租户的私有内容；
 * 禁止把租户私有政策写入该共享索引。
 */
public final class RagTenantScope {

    /** 全局公开语料使用的保留 tenantId。 */
    public static final long GLOBAL_TENANT_ID = 0L;

    private RagTenantScope() {
    }

    /** 当前请求可见的租户范围：全局 +（若存在上下文）当前租户。 */
    public static long[] visibleTenantIds() {
        var tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            return new long[]{GLOBAL_TENANT_ID};
        }
        return new long[]{GLOBAL_TENANT_ID, tenantId};
    }

    /**
     * 生成 PgVector 通用过滤表达式（Spring AI metadata filter 语法）。
     *
     * <p>PgVector 将 metadata 以 JSON 存储并按<b>文本</b>比较，因此 tenant_id 统一使用
     * 单引号字符串字面量：{@code (tenant_id == '0' || tenant_id == '<current>')}。
     */
    public static String filterExpression() {
        var ids = visibleTenantIds();
        if (ids.length == 1) {
            return "tenant_id == '" + ids[0] + "'";
        }
        return "(tenant_id == '" + ids[0] + "' || tenant_id == '" + ids[1] + "')";
    }

    /** 写入 PgVector metadata 时的 tenant_id 文本值（与过滤表达式保持一致的文本比较）。 */
    public static String metadataTenantId() {
        var tenantId = TenantContextHolder.get();
        return String.valueOf(tenantId == null ? GLOBAL_TENANT_ID : tenantId);
    }

    /** 判断一个 chunk 的 tenant_id 是否对当前请求可见。 */
    public static boolean isVisible(Long chunkTenantId) {
        if (chunkTenantId == null) {
            // 无 tenant_id 元数据的旧 chunk 仅视为全局，避免私有内容被误当成全局
            return true;
        }
        if (chunkTenantId == GLOBAL_TENANT_ID) {
            return true;
        }
        var current = TenantContextHolder.get();
        return current != null && current.equals(chunkTenantId);
    }
}
