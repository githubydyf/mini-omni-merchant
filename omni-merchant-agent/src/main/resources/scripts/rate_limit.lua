-- rate_limit.lua —— 三层限流原子检查（QPS / 月度预算 / 并发调用）
--
-- 语义说明：
--   * QPS 使用 INCR + EXPIRE，属于「固定时间窗口」，不是严格滑动窗口。
--   * 任一层的检查与资源占用在本脚本内原子完成，避免"检查通过但资源未占用"的竞态。
--   * 被拒绝的请求会回滚已产生的计数，避免失败请求污染限流统计。
--   * 并发使用 ZSET 租约（member = leaseToken，score = 过期时间戳毫秒）：
--       进程崩溃时靠 score 过期自动回收，release 幂等且不会出现负数计数。
--
-- KEYS[1]: QPS key            omni:rate:qps:{tenantId}
-- KEYS[2]: Budget key         omni:rate:budget:{tenantId}:{yyyyMM}
-- KEYS[3]: Concurrent ZSET    omni:rate:concurrent:{tenantId}
--
-- ARGV[1]: QPS 上限（<=0 表示不限）
-- ARGV[2]: 月度 Token 预算（<=0 表示不限）
-- ARGV[3]: 本次预计消耗 Token（预算预留）
-- ARGV[4]: 并发上限（<=0 表示不限）
-- ARGV[5]: QPS 窗口 TTL（秒）
-- ARGV[6]: 预算 key TTL（秒）
-- ARGV[7]: 本次并发租约 token（唯一）
-- ARGV[8]: 并发租约 TTL（毫秒）
--
-- 返回: {allowed(1|0), reject_reason}
--   reject_reason ∈ OK / QPS_LIMITED / BUDGET_EXCEEDED / CONCURRENT_LIMITED

local qps_key    = KEYS[1]
local budget_key = KEYS[2]
local conc_key   = KEYS[3]

local qps_limit      = tonumber(ARGV[1])
local monthly_budget = tonumber(ARGV[2])
local est_tokens     = tonumber(ARGV[3])
local conc_limit     = tonumber(ARGV[4])
local qps_ttl        = tonumber(ARGV[5])
local budget_ttl     = tonumber(ARGV[6])
local lease_token    = ARGV[7]
local lease_ttl_ms   = tonumber(ARGV[8])

-- 回滚已经产生的 QPS 计数（拒绝时调用，避免失败请求占用额度）
local function rollback_qps()
    if qps_limit > 0 then
        redis.call('DECR', qps_key)
    end
end

-- 第 1 层：QPS（固定窗口）
local qps_incremented = false
if qps_limit > 0 then
    local current = redis.call('INCR', qps_key)
    qps_incremented = true
    if current == 1 then
        redis.call('EXPIRE', qps_key, qps_ttl)
    end
    if current > qps_limit then
        rollback_qps()
        return {0, 'QPS_LIMITED'}
    end
end

-- 第 2 层：月度 Token 预算（预留估算值；未通过时不得占用并发额度）
if monthly_budget > 0 then
    local usage = redis.call('GET', budget_key)
    local used = usage and tonumber(usage) or 0
    if used + est_tokens > monthly_budget then
        rollback_qps()
        return {0, 'BUDGET_EXCEEDED'}
    end
end

-- 第 3 层：并发调用（ZSET 租约）
if conc_limit > 0 then
    local now = tonumber(ARGV[9])
    -- 先清理已过期的租约（进程崩溃后的自动回收）
    redis.call('ZREMRANGEBYSCORE', conc_key, '-inf', now)
    redis.call('ZADD', conc_key, now + lease_ttl_ms, lease_token)
    -- 让 ZSET 整体过期时间略长于最久租约，避免空 key 常驻
    redis.call('PEXPIRE', conc_key, lease_ttl_ms * 2)
    local inflight = redis.call('ZCARD', conc_key)
    if inflight > conc_limit then
        redis.call('ZREM', conc_key, lease_token)
        rollback_qps()
        return {0, 'CONCURRENT_LIMITED'}
    end
end

return {1, 'OK'}
