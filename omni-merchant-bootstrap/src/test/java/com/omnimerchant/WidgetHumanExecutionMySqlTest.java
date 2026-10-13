package com.omnimerchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.admin.service.IdentityService;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.agent.service.*;
import com.omnimerchant.common.util.JwtUtil;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实 MySQL 事务、行锁和完整认证链；不调用大模型，浏览器联调单独执行。 */
@EnabledIf("infraAvailable")
@SpringBootTest(properties = {"admin.email=widget-it-admin@example.com", "admin.password=widget-it-admin-pw-0123",
        "admin.jwt-secret=01234567890123456789012345678901", "omnimerchant.helpdesk.projection-scheduler-enabled=false",
        "app.widget.session-rate-limit-per-tenant=100000"})
@AutoConfigureMockMvc
class WidgetHumanExecutionMySqlTest {
    static boolean infraAvailable() { return WidgetApiTest.infraAvailable(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired IdentityService identity;
    @Autowired JwtUtil jwt;
    @Autowired ChatMessagePersistenceService messages;
    @Autowired ConversationMapper conversations;
    @Autowired CommerceActionExecutionService execution;
    @Autowired CommercePlatformService commerce;
    @MockitoSpyBean CommerceActionAuditService audit;
    @MockitoSpyBean com.omnimerchant.agent.mapper.OrderInfoMapper orderMapper;
    @MockitoSpyBean com.omnimerchant.agent.mapper.CommerceActionPolicyMapper policyMapper;
    String supervisor, agent;
    List<String> conversationIds = new ArrayList<>();
    List<Long> orderIds = new ArrayList<>();
    List<Long> requestIds = new ArrayList<>();
    List<Long> returnIds = new ArrayList<>();

    @BeforeEach void setup() {
        supervisor = user("human-execution-supervisor-it@example.com", "SUPPORT_SUPERVISOR", 1001L);
        agent = user("human-execution-agent-it@example.com", "SUPPORT_AGENT", 1001L);
    }
    String user(String email, String role, Long tenant) {
        if (db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email) == 0)
            identity.createUser(new IdentityService.CreateUserCommand(email, "人工闭环测试账号", "human-it-password-012345", false,
                    List.of(new IdentityService.MembershipCommand(tenant, role))), null);
        return identity.login(email, "human-it-password-012345").accessToken();
    }
    @AfterEach void cleanup() {
        reset(audit);
        reset(orderMapper, policyMapper);
        SecurityContextHolder.clearContext(); TenantContextHolder.clear();
        for (var uuid : conversationIds) {
            db.update("DELETE FROM chat_message WHERE conversation_uuid=?", uuid);
            db.update("DELETE FROM conversation WHERE conversation_uuid=?", uuid);
        }
        for (var id : requestIds) { db.update("DELETE FROM commerce_action_record WHERE source='commerce_action_request' AND request_id=?", id); db.update("DELETE FROM commerce_action_request WHERE id=?", id); }
        for (var id : returnIds) { db.update("DELETE FROM commerce_action_record WHERE source='return_request' AND request_id=?", id); db.update("DELETE FROM return_request WHERE id=?", id); }
        for (var id : orderIds) db.update("DELETE FROM order_info WHERE id=?", id);
    }
    JsonNode session(String code) throws Exception {
        var result = mvc.perform(post("/api/widget/session").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantCode", code)))).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        conversationIds.add(data.get("conversationUuid").asText()); return data;
    }
    JsonNode adminPost(String path, String token, Long tenant, Object body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    JsonNode pull(JsonNode session, int cursor) throws Exception {
        return json.readTree(mvc.perform(get("/api/widget/messages").param("conversationUuid", session.get("conversationUuid").asText())
                .param("afterSeqNo", String.valueOf(cursor)).header("Authorization", "Bearer " + session.get("customerSessionToken").asText()))
                .andReturn().getResponse().getContentAsString());
    }

    @Test void widgetMessagesEnforceIdentityConversationTenantAndExpiry() throws Exception {
        var s = session("OM-1001"); var other = session("OM-1002");
        var path = get("/api/widget/messages").param("conversationUuid", s.get("conversationUuid").asText());
        assertThat(mvc.perform(path).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/widget/messages").param("conversationUuid", s.get("conversationUuid").asText())
                .header("Authorization", "Bearer " + supervisor)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/widget/messages").param("conversationUuid", other.get("conversationUuid").asText())
                .header("Authorization", "Bearer " + s.get("customerSessionToken").asText())).andReturn().getResponse().getStatus()).isEqualTo(403);
        var expired = jwt.generateWidgetCustomerToken("过期测试", 1001L, "OM-1001", s.get("conversationUuid").asText(), -60000);
        assertThat(mvc.perform(get("/api/widget/messages").param("conversationUuid", s.get("conversationUuid").asText())
                .header("Authorization", "Bearer " + expired)).andReturn().getResponse().getStatus()).isEqualTo(401);
        // 即使签名有效、会话 UUID 一致，令牌租户与真实数据库不一致仍拒绝。
        var forgedBinding = jwt.generateWidgetCustomerToken("错绑测试", 1002L, "OM-1002", s.get("conversationUuid").asText(), 60000);
        var wrong = json.readTree(mvc.perform(get("/api/widget/messages").param("conversationUuid", s.get("conversationUuid").asText())
                .header("Authorization", "Bearer " + forgedBinding)).andReturn().getResponse().getContentAsString());
        assertThat(wrong.get("code").asText()).isNotEqualTo("200");
    }

    @Test void humanReplyAndBuyerSendShareDatabaseAndClosedHistoryRemainsReadable() throws Exception {
        var s = session("OM-1001"); var uuid = s.get("conversationUuid").asText();
        assertThat(adminPost("/api/inbox/" + uuid + "/takeover", supervisor, 1001L, Map.of()).get("code").asText()).isEqualTo("200");
        assertThat(adminPost("/api/inbox/" + uuid + "/reply", supervisor, 1001L, Map.of("message", "人工测试回复", "actorId", -1)).get("code").asText()).isEqualTo("200");
        assertThat(db.queryForObject("SELECT status FROM conversation WHERE conversation_uuid=?", Integer.class, uuid)).isEqualTo(4);
        var row = pull(s, 0).get("data").get("messages").get(0);
        assertThat(row.get("source").asText()).isEqualTo("HUMAN");
        assertThat(row.has("senderId")).isFalse();
        var actor = jwt.parsePrincipal(supervisor).userId();
        assertThat(db.queryForObject("SELECT sender_id FROM chat_message WHERE message_uuid=?", Long.class, row.get("messageUuid").asText())).isEqualTo(actor);
        var beforeRuns = db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE conversation_uuid=?", Long.class, uuid);
        var result = mvc.perform(post("/api/widget/chat/stream").header("Authorization", "Bearer " + s.get("customerSessionToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("tenantCode", "OM-1001", "conversationUuid", uuid, "message", "补充给人工")))).andReturn();
        result.getAsyncResult(3000);
        mvc.perform(asyncDispatch(result));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE conversation_uuid=?", Long.class, uuid)).isEqualTo(beforeRuns);
        assertThat(pull(s, 1).get("data").get("messages").get(0).get("source").asText()).isEqualTo("USER");
        adminPost("/api/inbox/" + uuid + "/reply", supervisor, 1001L, Map.of("message", "处理结束", "closeAfterReply", true));
        assertThat(pull(s, 0).get("data").get("conversationStatus").asInt()).isEqualTo(5);
        var closed = mvc.perform(post("/api/widget/chat/stream").header("Authorization", "Bearer " + s.get("customerSessionToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("tenantCode", "OM-1001", "conversationUuid", uuid, "message", "不能重新启动")))).andReturn();
        assertThat(json.readTree(closed.getResponse().getContentAsString()).get("code").asText()).isNotEqualTo("200");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_uuid=?", Long.class, uuid)).isEqualTo(3);
    }

    @Test void concurrentHumanAndBuyerWritesHaveUniqueSequenceAndExactCounter() throws Exception {
        var s = session("OM-1001"); var uuid = s.get("conversationUuid").asText();
        adminPost("/api/inbox/" + uuid + "/takeover", supervisor, 1001L, Map.of());
        TenantContextHolder.set(1001L);
        var conversation = conversations.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Conversation>().eq(Conversation::getConversationUuid, uuid));
        TenantContextHolder.clear();
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    TenantContextHolder.set(1001L);
                    try {
                        if (n % 2 == 0) messages.saveWidgetUserMessage(conversation, "重复文字");
                        else messages.saveHumanMessage(conversation, "重复文字", jwt.parsePrincipal(supervisor).userId());
                    } finally { TenantContextHolder.clear(); }
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        var counts = db.queryForMap("SELECT COUNT(*) total,COUNT(DISTINCT seq_no) unique_seq,MAX(seq_no) maximum FROM chat_message WHERE conversation_uuid=?", uuid);
        assertThat(((Number) counts.get("total")).intValue()).isEqualTo(24);
        assertThat(((Number) counts.get("unique_seq")).intValue()).isEqualTo(24);
        assertThat(((Number) counts.get("maximum")).intValue()).isEqualTo(24);
        assertThat(db.queryForObject("SELECT message_count FROM conversation WHERE conversation_uuid=?", Integer.class, uuid)).isEqualTo(24);
    }

    @Test void pollingPaginationFiltersInternalRecordsAndDoesNotExposeTools() throws Exception {
        var s = session("OM-1001"); var uuid = s.get("conversationUuid").asText();
        var id = db.queryForObject("SELECT id FROM conversation WHERE conversation_uuid=?", Long.class, uuid);
        for (int i=1; i<=103; i++) db.update("INSERT INTO chat_message(message_uuid,conversation_uuid,conversation_id,tenant_id,role,seq_no,content,source,tool_name) VALUES(?,?,?,1001,?,?,?,?,?)",
                UUID.randomUUID().toString(), uuid, id, i == 2 ? "tool" : "assistant", i, "内容" + i, "AI", i == 3 ? "内部工具" : null);
        db.update("INSERT INTO chat_message(message_uuid,conversation_uuid,conversation_id,tenant_id,role,seq_no,content,source) VALUES(?,?,?,1001,'assistant',104,'内部管理信息','INTERNAL')", UUID.randomUUID().toString(), uuid, id);
        var first = pull(s, 0).get("data");
        assertThat(first.get("messages").size()).isEqualTo(100);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("messages").get(1).get("seqNo").asInt()).isEqualTo(4);
        var last = first.get("messages").get(99).get("seqNo").asInt();
        var second = pull(s, last).get("data");
        assertThat(second.get("messages").size()).isEqualTo(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(pull(s, -1).get("code").asText()).isNotEqualTo("200");
    }

    long order(String status) {
        var number = "IT-LOCAL-" + UUID.randomUUID();
        db.update("INSERT INTO order_info(tenant_id,external_order_id,external_order_number,platform,customer_email,order_status,payment_status,fulfillment_status,currency,total_amount,placed_at,sync_source) VALUES(1001,?,?, 'custom','local-buyer-it@example.com',?,'paid',?,'CNY',100,NOW(),'DEMO')", number, number, status, "paid".equals(status) ? "unfulfilled" : "fulfilled");
        var id = db.queryForObject("SELECT id FROM order_info WHERE tenant_id=1001 AND external_order_number=?", Long.class, number);
        orderIds.add(id); return id;
    }
    long request(long order, String type, String state) {
        var number = db.queryForObject("SELECT external_order_number FROM order_info WHERE id=?", String.class, order);
        db.update("INSERT INTO commerce_action_request(tenant_id,request_no,action_type,platform,external_order_number,customer_email,status,requested_payload) VALUES(1001,?,?,'custom',?,'local-buyer-it@example.com',?,?)", "IT-REQ-"+UUID.randomUUID(), type, number, state, addressPayload());
        var id = db.queryForObject("SELECT MAX(id) FROM commerce_action_request WHERE external_order_number=?", Long.class, number);
        requestIds.add(id); return id;
    }
    String addressPayload() { return "{\"newAddress\":{\"recipient\":\"测试收件人\",\"phone\":\"13800000000\",\"address\":\"北京市朝阳区测试路一号\",\"country\":\"CN\",\"state\":\"北京市\",\"zip\":\"100000\"}}"; }
    JsonNode executeApi(long id, String token) throws Exception { return adminPost("/api/actions/commerce_action_request/" + id + "/execute", token, 1001L, Map.of()); }

    @Test void approvalAndExecutionAreSeparateAndAuditIsTraceable() throws Exception {
        var order = order("paid"); var id = request(order, "CANCEL_ORDER", "PENDING_APPROVAL");
        assertThat(executeApi(id, supervisor).get("code").asText()).isNotEqualTo("200");
        assertThat(adminPost("/api/actions/commerce_action_request/"+id+"/approve", supervisor,1001L,Map.of("note","批准本地取消")).get("data").get("execution").get("state").asText()).isEqualTo("PENDING_EXECUTION");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?", String.class, order)).isEqualTo("paid");
        assertThat(executeApi(id, supervisor).get("data").get("state").asText()).isEqualTo("EXECUTED");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?", String.class, order)).isEqualTo("cancelled");
        assertThat(db.queryForObject("SELECT payment_status FROM order_info WHERE id=?", String.class, order)).isEqualTo("paid");
        assertThat(db.queryForObject("SELECT cancelled_at FROM order_info WHERE id=?", java.sql.Timestamp.class, order)).isNotNull();
        var version = db.queryForObject("SELECT version FROM order_info WHERE id=?", Integer.class, order);
        assertThat(executeApi(id, supervisor).get("data").get("state").asText()).isEqualTo("EXECUTED");
        assertThat(db.queryForObject("SELECT version FROM order_info WHERE id=?", Integer.class, order)).isEqualTo(version);
        assertThat(adminPost("/api/actions/commerce_action_request/"+id+"/reject", supervisor,1001L,Map.of()).get("code").asText()).isNotEqualTo("200");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commerce_action_record WHERE source='commerce_action_request' AND request_id=?", Integer.class, id)).isEqualTo(2);
    }

    @Test void rejectedAndShippedCancelledRefundedOrdersCannotExecute() throws Exception {
        for (var status : List.of("shipped","delivered","cancelled","refunded")) {
            var order = order(status); var id = request(order,"CANCEL_ORDER","APPROVED_MANUAL");
            assertThat(executeApi(id,supervisor).get("code").asText()).isNotEqualTo("200");
            assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?", String.class, order)).isEqualTo(status);
            assertThat(db.queryForObject("SELECT state FROM commerce_action_record WHERE request_id=? AND event_type='EXECUTION'", String.class,id)).isEqualTo("FAILED");
        }
        assertThat(executeApi(request(order("paid"),"CANCEL_ORDER","REJECTED"),supervisor).get("code").asText()).isNotEqualTo("200");
    }

    @Test void addressChangeUsesApprovedStructuredAddressAndAuditsBeforeAfter() throws Exception {
        var order=order("paid"); var id=request(order,"ADDRESS_CHANGE","APPROVED_MANUAL");
        assertThat(executeApi(id,supervisor).get("code").asText()).isEqualTo("200");
        assertThat(db.queryForObject("SELECT shipping_zip FROM order_info WHERE id=?",String.class,order)).isEqualTo("100000");
        assertThat(db.queryForObject("SELECT shipping_address FROM order_info WHERE id=?",String.class,order)).contains("测试路");
        assertThat(db.queryForObject("SELECT before_snapshot FROM commerce_action_record WHERE request_id=?",String.class,id)).contains("shippingAddress");
        var malformed = request(order("paid"),"ADDRESS_CHANGE","APPROVED_MANUAL");
        db.update("UPDATE commerce_action_request SET requested_payload='{\"newAddress\":\"不完整地址\"}' WHERE id=?",malformed);
        assertThat(executeApi(malformed,supervisor).get("code").asText()).isNotEqualTo("200");
    }

    @Test void refundReturnReplacementNeverChangePaymentOrInventFulfillment() throws Exception {
        for (var type:List.of("REFUND","RETURN","REPLACEMENT")) {
            var order=order("paid"); var id=request(order,type,"PENDING_APPROVAL");
            var approved=adminPost("/api/actions/commerce_action_request/"+id+"/approve",supervisor,1001L,Map.of());
            assertThat(approved.get("data").get("execution").get("executable").asBoolean()).isFalse();
            assertThat(executeApi(id,supervisor).get("code").asText()).isNotEqualTo("200");
            assertThat(db.queryForObject("SELECT payment_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("paid");
            assertThat(db.queryForObject("SELECT fulfillment_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("unfulfilled");
        }
    }

    @Test void normalAgentAndCrossTenantExecutionAreDenied() throws Exception {
        var order=order("paid"); var id=request(order,"CANCEL_ORDER","APPROVED_MANUAL");
        var denied = mvc.perform(post("/api/actions/commerce_action_request/"+id+"/execute").header("Authorization","Bearer "+agent).header("X-Tenant-Id","1001")).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        var cross = user("human-execution-other-it@example.com","SUPPORT_SUPERVISOR",1002L);
        assertThat(adminPost("/api/actions/commerce_action_request/"+id+"/execute",cross,1002L,Map.of()).get("code").asText()).isNotEqualTo("200");
        // 同租户申请不能定位到他租户订单。
        db.update("UPDATE order_info SET tenant_id=1002 WHERE id=?",order);
        assertThat(executeApi(id,supervisor).get("code").asText()).isNotEqualTo("200");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("paid");
    }

    @Test void concurrentExecuteHasOneSideEffectAndOneSuccessRecord() throws Exception {
        var order=order("paid"); var id=request(order,"CANCEL_ORDER","APPROVED_MANUAL");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> {
                TenantContextHolder.set(1001L);
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(jwt.parsePrincipal(supervisor),null,List.of()));
                try { assertThat(execution.execute("commerce_action_request",id).getState()).isEqualTo("EXECUTED"); }
                finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
            }));
            for(var f:futures) f.get(30,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(db.queryForObject("SELECT version FROM order_info WHERE id=?",Integer.class,order)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commerce_action_record WHERE request_id=? AND execution_key='LOCAL'",Integer.class,id)).isEqualTo(1);
    }

    @Test void auditFailureRollsBackOrderAndRequestThenRecordsFailure() throws Exception {
        var order=order("paid");var id=request(order,"CANCEL_ORDER","APPROVED_MANUAL");
        doThrow(new IllegalStateException("测试审计写入失败")).when(audit).record(eq("commerce_action_request"),eq(id),anyString(),eq("EXECUTION"),eq("EXECUTED"),anyLong(),anyString(),anyString(),anyString());
        assertThat(executeApi(id,supervisor).get("code").asText()).isNotEqualTo("200");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("paid");
        assertThat(db.queryForObject("SELECT version FROM order_info WHERE id=?",Integer.class,order)).isZero();
        assertThat(db.queryForObject("SELECT status FROM commerce_action_request WHERE id=?",String.class,id)).isEqualTo("APPROVED_MANUAL");
        assertThat(db.queryForObject("SELECT state FROM commerce_action_record WHERE request_id=?",String.class,id)).isEqualTo("FAILED");
    }

    @Test void numericReturnApprovalExecutesLocalCancelWithCompatibleStatus() throws Exception {
        var order=order("paid");var number=db.queryForObject("SELECT external_order_number FROM order_info WHERE id=?",String.class,order);
        var no="IT-RETURN-"+UUID.randomUUID();
        db.update("INSERT INTO return_request(tenant_id,request_no,request_type,external_order_number,customer_email,status) VALUES(1001,?,'CANCEL_ORDER',?,'local-buyer-it@example.com',1)",no,number);
        var id=db.queryForObject("SELECT id FROM return_request WHERE request_no=?",Long.class,no);returnIds.add(id);
        assertThat(adminPost("/api/actions/return_request/"+id+"/approve",supervisor,1001L,Map.of()).get("code").asText()).isEqualTo("200");
        assertThat(adminPost("/api/actions/return_request/"+id+"/execute",supervisor,1001L,Map.of()).get("code").asText()).isEqualTo("200");
        assertThat(db.queryForObject("SELECT status FROM return_request WHERE id=?",Integer.class,id)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT resolution FROM return_request WHERE id=?",String.class,id)).isEqualTo("EXECUTED_LOCAL");
    }

    @Test void policyRoleAndMissingPolicyFailClosed() throws Exception {
        var order=order("paid"); var id=request(order,"CANCEL_ORDER","APPROVED_MANUAL");
        var restricted=new com.omnimerchant.agent.entity.CommerceActionPolicy();
        restricted.setTenantId(1001L); restricted.setActionType("CANCEL_ORDER"); restricted.setActive(1); restricted.setMinApproverRole("TENANT_ADMIN");
        doReturn(restricted).when(policyMapper).selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertThat(executeApi(id,supervisor).get("code").asText()).isNotEqualTo("200");
        doReturn(null).when(policyMapper).selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertThat(executeApi(id,supervisor).get("code").asText()).isNotEqualTo("200");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("paid");
    }

    @Test void versionConflictAndOwnerMismatchDoNotModifyOrder() throws Exception {
        var order=order("paid"); var id=request(order,"CANCEL_ORDER","APPROVED_MANUAL");
        doReturn(0).when(orderMapper).updateById(any(com.omnimerchant.agent.entity.OrderInfo.class));
        assertThat(executeApi(id,supervisor).get("message").asText()).contains("版本冲突");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("paid");
        reset(orderMapper);
        db.update("UPDATE commerce_action_request SET customer_email='other-buyer-it@example.com' WHERE id=?",id);
        assertThat(executeApi(id,supervisor).get("message").asText()).contains("身份与订单不一致");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,order)).isEqualTo("paid");
    }

    @Test void latestOrderToolProgressUsesBusinessRecordsRatherThanChatHistory() throws Exception {
        var order=order("paid"); var id=request(order,"REFUND","PENDING_APPROVAL");
        var number=db.queryForObject("SELECT external_order_number FROM order_info WHERE id=?",String.class,order);
        adminPost("/api/actions/commerce_action_request/"+id+"/approve",supervisor,1001L,Map.of());
        TenantContextHolder.set(1001L);
        try {
            var result=commerce.queryOrder(number,"local-buyer-it@example.com");
            assertThat(result.paymentRefundConfirmed()).isFalse();
            assertThat(result.paymentStatus()).isEqualTo("paid");
            assertThat(result.afterSalesProgress()).hasSize(1);
            assertThat(result.afterSalesProgress().get(0).executionStatus()).isEqualTo("MANUAL_PENDING");
            assertThat(result.afterSalesProgress().get(0).executionMessage()).contains("未执行支付退款");
        } finally { TenantContextHolder.clear(); }
    }

    @Test void ambiguousOrderNumberAndMismatchedExternalIdAreRefused() throws Exception {
        var first=order("paid"); var second=order("paid"); var id=request(first,"CANCEL_ORDER","APPROVED_MANUAL");
        var number=db.queryForObject("SELECT external_order_number FROM order_info WHERE id=?",String.class,first);
        var secondNumber=db.queryForObject("SELECT external_order_number FROM order_info WHERE id=?",String.class,second);
        db.update("UPDATE order_info SET external_order_number=? WHERE id=?",number,second);
        assertThat(executeApi(id,supervisor).get("message").asText()).contains("关联不唯一");
        db.update("UPDATE order_info SET external_order_number=? WHERE id=?",secondNumber,second);
        db.update("UPDATE commerce_action_request SET external_order_id='错误订单标识' WHERE id=?",id);
        assertThat(executeApi(id,supervisor).get("message").asText()).contains("订单标识与实际订单不一致");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,first)).isEqualTo("paid");
        assertThat(db.queryForObject("SELECT order_status FROM order_info WHERE id=?",String.class,second)).isEqualTo("paid");
    }
}
