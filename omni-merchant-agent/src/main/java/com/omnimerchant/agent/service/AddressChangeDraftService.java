package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.omnimerchant.agent.entity.*;
import com.omnimerchant.agent.mapper.*;
import com.omnimerchant.common.exception.*;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 确认必须来自绑定会话的买家令牌；模型只能生成草稿。 */
@Service
@RequiredArgsConstructor
public class AddressChangeDraftService {
    private final AddressChangeDraftMapper draftMapper;
    private final OrderInfoMapper orders;
    private final ReturnRequestMapper requests;
    private final AddressNormalizationService normalization;
    private final ConversationLifecycleService lifecycle;
    private final ObjectMapper json;

    @Transactional
    public CommercePlatformService.ReturnActionResult prepare(OrderInfo order, String identity, String raw, String conversationUuid) {
        if (!Objects.equals(order.getTenantId(), TenantContextHolder.get()) || !matches(order, identity)) throw bad("请先核验本租户订单归属");
        var result = normalization.normalize(raw, order);
        if (!result.missingFields().isEmpty()) return CommercePlatformService.ReturnActionResult.rejected(order.getExternalOrderNumber(),
                "ADDRESS_FIELDS_REQUIRED", "新地址尚未提交审批，请补充或核实：" + String.join("、", result.missingFields()));
        if (conversationUuid == null || conversationUuid.isBlank()) return CommercePlatformService.ReturnActionResult.rejected(
                order.getExternalOrderNumber(), "ADDRESS_CONFIRMATION_REQUIRED", "新地址已验证，请在买家会话中确认完整地址后提交审批。");
        lifecycle.requireForAi(conversationUuid, order.getTenantId());
        CommerceActionExecutionService.requireEditable(order);
        // 锁定同一订单，使草稿去重及订单快照在同一事务中完成。
        var locked = orders.selectOne(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getId, order.getId())
                .eq(OrderInfo::getTenantId, order.getTenantId()).last("FOR UPDATE"));
        if (locked == null || !matches(locked, identity) || !Objects.equals(locked.getVersion(), order.getVersion())) throw bad("订单已变化，请重新核验地址");
        String hash = hash(raw + "|" + order.getId() + "|" + order.getVersion());
        var draft = draftMapper.selectOne(new LambdaQueryWrapper<AddressChangeDraft>()
                .eq(AddressChangeDraft::getTenantId, order.getTenantId()).eq(AddressChangeDraft::getConversationUuid, conversationUuid)
                .eq(AddressChangeDraft::getInputHash, hash).last("LIMIT 1"));
        if (draft != null && draft.getConfirmedRequestId() != null) return CommercePlatformService.ReturnActionResult.existing(requests.selectById(draft.getConfirmedRequestId()));
        if (draft == null) {
            draft = new AddressChangeDraft();
            draft.setTenantId(order.getTenantId()); draft.setDraftUuid(UUID.randomUUID().toString());
            draft.setConversationUuid(conversationUuid); draft.setOrderId(order.getId()); draft.setOrderVersion(order.getVersion());
            draft.setVerifiedIdentity(identity.trim()); draft.setRawInput(raw); draft.setNormalizedAddress(result.address().toString());
            draft.setInputHash(hash); draft.setExpiresAt(LocalDateTime.now().plusMinutes(30)); draft.setCreatedAt(LocalDateTime.now());
            if (draftMapper.insert(draft) != 1) throw bad("地址确认草稿保存失败");
        }
        return CommercePlatformService.ReturnActionResult.rejected(order.getExternalOrderNumber(), "ADDRESS_CONFIRMATION_REQUIRED",
                "新地址已提取，尚未创建审批申请。请核对 Widget 中的完整候选地址，点击“确认地址并提交审批”；不要声称地址已经修改或申请已经提交。");
    }

    public DraftView latest(String conversationUuid) {
        lifecycle.requireForRead(conversationUuid, TenantContextHolder.get());
        var draft = draftMapper.selectOne(new LambdaQueryWrapper<AddressChangeDraft>()
                .eq(AddressChangeDraft::getTenantId, TenantContextHolder.get()).eq(AddressChangeDraft::getConversationUuid, conversationUuid)
                .orderByDesc(AddressChangeDraft::getId).last("LIMIT 1"));
        if (draft == null || draft.getConfirmedRequestId() != null || draft.getExpiresAt().isBefore(LocalDateTime.now())) return null;
        var order = orders.selectById(draft.getOrderId());
        var address = new LinkedHashMap<String, String>();
        tree(draft.getNormalizedAddress()).fields().forEachRemaining(field -> address.put(field.getKey(), field.getValue().asText()));
        return new DraftView(draft.getDraftUuid(), order == null ? null : order.getExternalOrderNumber(), address, draft.getExpiresAt());
    }

    @Transactional
    public CommercePlatformService.ReturnActionResult confirm(String conversationUuid, String draftUuid) {
        var conversation = lifecycle.requireForRead(conversationUuid, TenantContextHolder.get());
        if (Set.of(5, 6).contains(conversation.getStatus())) throw bad("会话已关闭，不能提交新的地址申请");
        var draft = draftMapper.selectOne(new LambdaQueryWrapper<AddressChangeDraft>()
                .eq(AddressChangeDraft::getTenantId, TenantContextHolder.get()).eq(AddressChangeDraft::getConversationUuid, conversationUuid)
                .eq(AddressChangeDraft::getDraftUuid, draftUuid).last("FOR UPDATE"));
        if (draft == null) throw new BusinessException(ErrorCode.NOT_FOUND, "本会话没有该地址确认草稿");
        if (draft.getConfirmedRequestId() != null) return CommercePlatformService.ReturnActionResult.existing(requests.selectById(draft.getConfirmedRequestId()));
        if (draft.getExpiresAt().isBefore(LocalDateTime.now())) throw bad("地址确认已过期，请重新提供地址");
        var latest = draftMapper.selectOne(new LambdaQueryWrapper<AddressChangeDraft>()
                .eq(AddressChangeDraft::getTenantId, TenantContextHolder.get()).eq(AddressChangeDraft::getConversationUuid, conversationUuid)
                .orderByDesc(AddressChangeDraft::getId).last("LIMIT 1"));
        if (!draft.getId().equals(latest.getId())) throw bad("新地址已更新，请确认最新候选地址");
        var order = orders.selectOne(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getTenantId, TenantContextHolder.get())
                .eq(OrderInfo::getId, draft.getOrderId()).last("FOR UPDATE"));
        if (order == null || !matches(order, draft.getVerifiedIdentity()) || !Objects.equals(order.getVersion(), draft.getOrderVersion())) throw bad("订单或收件信息已变化，请重新核验并确认地址");
        CommerceActionExecutionService.requireEditable(order);
        var address = normalization.validate(tree(draft.getNormalizedAddress()));
        var request = new ReturnRequest();
        request.setTenantId(order.getTenantId()); request.setRequestNo("ADR-" + UUID.randomUUID()); request.setRequestType("ADDRESS_CHANGE");
        request.setExternalOrderNumber(order.getExternalOrderNumber()); request.setCustomerEmail(draft.getVerifiedIdentity());
        request.setReason("客户核对完整新地址后明确提交修改申请");
        var payload = json.createObjectNode(); payload.set("newAddress", address); payload.put("rawInput", draft.getRawInput());
        payload.put("customerConfirmed", true); payload.put("confirmationId", draft.getDraftUuid());
        request.setRequestedItems(payload.toString()); request.setAmount(BigDecimal.ZERO); request.setCurrency(order.getCurrency());
        request.setStatus(1); request.setPriority(3); request.setApprovalRequiredReason("ADDRESS_CHANGE_REQUIRES_AGENT_REVIEW_BEFORE_EXTERNAL_WRITE");
        if (requests.insert(request) != 1) throw bad("地址申请创建失败");
        draft.setConfirmedRequestId(request.getId()); draft.setConfirmedAt(LocalDateTime.now());
        if (draftMapper.updateById(draft) != 1) throw bad("地址确认记录保存失败，申请已回滚");
        return CommercePlatformService.ReturnActionResult.created(request);
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch (Exception e) { throw bad("候选地址数据无效，请重新提供"); } }
    private boolean matches(OrderInfo order, String identity) {
        return identity != null && !identity.isBlank() && (identity.trim().equalsIgnoreCase(order.getCustomerEmail()) || identity.trim().equals(order.getCustomerPhone()));
    }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw bad("地址草稿校验失败"); } }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    public record DraftView(String draftUuid, String orderNumber, Map<String, String> address, LocalDateTime expiresAt) { }
}
