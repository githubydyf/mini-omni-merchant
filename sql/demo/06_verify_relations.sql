-- =====================================================================
-- 06_verify_relations.sql —— 演示数据关联与跨租户一致性检查（真实执行）
--
-- 说明：本文件只读（全部 SELECT），会对每一检查项真实执行统计查询，
--       输出「检查项 / 违规数 / 结果(PASS/FAIL)」。违规数为 0 才代表通过。
--
-- 执行：mysql ... < sql/demo/06_verify_relations.sql
-- =====================================================================

SELECT '演示数据关联检查（结果应为全 PASS）' AS report_title, NOW() AS checked_at;

-- ---------------------------------------------------------------------
SELECT '#01' AS item, '订单引用的客户必须存在' AS checks, COUNT(*) AS violations,
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END AS result
FROM order_info o
LEFT JOIN customer c ON c.id = o.customer_id
WHERE o.is_deleted=0 AND (o.customer_id IS NULL OR c.id IS NULL)
UNION ALL
SELECT '#02', '订单与客户必须属于同一租户', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM order_info o JOIN customer c ON c.id = o.customer_id
WHERE o.tenant_id <> c.tenant_id
UNION ALL
SELECT '#03', '每个订单至少包含一个属于对应租户商品库的 SKU', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM (
  SELECT o.id
  FROM order_info o
  LEFT JOIN product p ON p.tenant_id = o.tenant_id AND p.is_deleted = 0
       AND o.order_items LIKE CONCAT('%', p.default_sku, '%')
  WHERE o.is_deleted = 0 AND o.order_items IS NOT NULL
  GROUP BY o.id
  HAVING SUM(CASE WHEN p.id IS NULL THEN 0 ELSE 1 END) = 0
) missing_sku
UNION ALL
SELECT '#03b', '订单不得引用其他租户的 SKU（跨租户商品引用）', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM order_info o
JOIN product p ON p.tenant_id <> o.tenant_id AND p.is_deleted = 0
WHERE o.is_deleted = 0 AND o.order_items LIKE CONCAT('%', p.default_sku, '%')
UNION ALL
SELECT '#04', '会话引用的客户必须存在（允许匿名会话 customer_id 为空）', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM conversation v
LEFT JOIN customer c ON c.id = v.customer_id
WHERE v.customer_id IS NOT NULL AND c.id IS NULL
UNION ALL
SELECT '#05', '会话引用的订单号必须存在于同租户订单中', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM conversation v
WHERE v.related_order_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM order_info o
                  WHERE o.tenant_id=v.tenant_id AND o.external_order_number=v.related_order_id AND o.is_deleted=0)
UNION ALL
SELECT '#06', 'ChatMessage 引用的会话必须存在', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM chat_message m
LEFT JOIN conversation v ON v.conversation_uuid = m.conversation_uuid
WHERE v.conversation_uuid IS NULL
UNION ALL
SELECT '#07', 'ChatMessage 与会话 tenantId 必须一致', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM chat_message m JOIN conversation v ON v.conversation_uuid = m.conversation_uuid
WHERE m.tenant_id <> v.tenant_id
UNION ALL
SELECT '#08', '同一会话内 seqNo 不得重复', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM (SELECT conversation_id, seq_no, COUNT(*) c
      FROM chat_message GROUP BY conversation_id, seq_no HAVING c > 1) dup
UNION ALL
SELECT '#09', '会话 message_count 必须等于真实消息数', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM conversation v
LEFT JOIN (SELECT conversation_uuid, COUNT(*) n FROM chat_message GROUP BY conversation_uuid) mc
       ON mc.conversation_uuid = v.conversation_uuid
WHERE v.is_deleted=0 AND v.message_count <> COALESCE(mc.n, 0)
UNION ALL
SELECT '#10', 'EscalationRecord 必须关联真实会话', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM escalation_record e
LEFT JOIN conversation v ON v.conversation_uuid = e.conversation_uuid
WHERE v.conversation_uuid IS NULL
UNION ALL
SELECT '#10b', 'EscalationRecord 与会话租户必须一致', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM escalation_record e JOIN conversation v ON v.conversation_uuid = e.conversation_uuid
WHERE e.tenant_id <> v.tenant_id
UNION ALL
SELECT '#11', 'Ticket(source_type=ESCALATION) 必须关联真实升级记录', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM ticket t
LEFT JOIN escalation_record e ON e.id = t.source_id AND e.tenant_id = t.tenant_id
WHERE t.source_type='ESCALATION' AND e.id IS NULL
UNION ALL
SELECT '#12', 'ReturnRequest 必须关联真实订单号', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM return_request r
WHERE NOT EXISTS (SELECT 1 FROM order_info o
                  WHERE o.tenant_id=r.tenant_id AND o.external_order_number=r.external_order_number AND o.is_deleted=0)
UNION ALL
SELECT '#13', '审批操作人（approved_by / assigned_agent_id）必须引用真实用户', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM (
  SELECT approved_by AS uid FROM commerce_action_request WHERE approved_by IS NOT NULL
  UNION ALL SELECT assigned_agent_id FROM ticket WHERE assigned_agent_id IS NOT NULL
  UNION ALL SELECT assigned_agent_id FROM escalation_record WHERE assigned_agent_id IS NOT NULL
  UNION ALL SELECT human_agent_id FROM conversation WHERE human_agent_id IS NOT NULL
) actors
LEFT JOIN app_user u ON u.id = actors.uid
WHERE u.id IS NULL
UNION ALL
SELECT '#14', '不存在跨租户业务引用（会话客户）', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM conversation v JOIN customer c ON c.id=v.customer_id
WHERE v.tenant_id <> c.tenant_id
UNION ALL
SELECT '#14b', '不存在跨租户业务引用（升级客户）', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM escalation_record e JOIN customer c ON c.id=e.customer_id
WHERE e.tenant_id <> c.tenant_id
UNION ALL
SELECT '#15', 'CANCELLED 订单不得为 paid', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM order_info WHERE order_status='cancelled' AND payment_status='paid'
UNION ALL
SELECT '#15b', 'REFUNDED 订单 payment_status 必须为 refunded', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM order_info WHERE order_status='refunded' AND payment_status<>'refunded'
UNION ALL
SELECT '#16', '客户 total_orders 与真实订单数一致', COUNT(*),
       CASE WHEN COUNT(*)=0 THEN 'PASS' ELSE 'FAIL' END
FROM customer c
LEFT JOIN (SELECT tenant_id, customer_id, COUNT(*) n FROM order_info
           WHERE is_deleted=0 AND order_status<>'cancelled' GROUP BY tenant_id, customer_id) o
       ON o.tenant_id=c.tenant_id AND o.customer_id=c.id
WHERE c.tenant_id IN (1001,1002) AND c.id BETWEEN 2001 AND 2016
  AND c.total_orders <> COALESCE(o.n,0)
ORDER BY item;

-- ---------------------------------------------------------------------
-- 汇总：是否存在任何 FAIL（便于脚本判定）
-- ---------------------------------------------------------------------
SELECT '=== 汇总 ===' AS section;
SELECT COALESCE(SUM(c),0) AS fail_count
FROM (
  SELECT COUNT(*) c FROM order_info o LEFT JOIN customer c2 ON c2.id=o.customer_id
    WHERE o.is_deleted=0 AND (o.customer_id IS NULL OR c2.id IS NULL)
  UNION ALL SELECT COUNT(*) FROM order_info o JOIN customer c2 ON c2.id=o.customer_id WHERE o.tenant_id<>c2.tenant_id
  UNION ALL SELECT COUNT(*) FROM conversation v LEFT JOIN customer c2 ON c2.id=v.customer_id WHERE v.customer_id IS NOT NULL AND c2.id IS NULL
  UNION ALL SELECT COUNT(*) FROM chat_message m LEFT JOIN conversation v ON v.conversation_uuid=m.conversation_uuid WHERE v.conversation_uuid IS NULL
  UNION ALL SELECT COUNT(*) FROM chat_message m JOIN conversation v ON v.conversation_uuid=m.conversation_uuid WHERE m.tenant_id<>v.tenant_id
  UNION ALL SELECT COUNT(*) FROM (SELECT conversation_id,seq_no,COUNT(*) c FROM chat_message GROUP BY conversation_id,seq_no HAVING c>1) d
  UNION ALL SELECT COUNT(*) FROM escalation_record e LEFT JOIN conversation v ON v.conversation_uuid=e.conversation_uuid WHERE v.conversation_uuid IS NULL
  UNION ALL SELECT COUNT(*) FROM ticket t LEFT JOIN escalation_record e ON e.id=t.source_id AND e.tenant_id=t.tenant_id WHERE t.source_type='ESCALATION' AND e.id IS NULL
  UNION ALL SELECT COUNT(*) FROM return_request r WHERE NOT EXISTS (SELECT 1 FROM order_info o WHERE o.tenant_id=r.tenant_id AND o.external_order_number=r.external_order_number AND o.is_deleted=0)
  UNION ALL SELECT COUNT(*) FROM order_info WHERE order_status='cancelled' AND payment_status='paid'
  UNION ALL SELECT COUNT(*) FROM order_info WHERE order_status='refunded' AND payment_status<>'refunded'
) all_checks;
