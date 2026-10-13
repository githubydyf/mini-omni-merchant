package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.service.AddressChangeDraftService;
import com.omnimerchant.common.constant.Constants;
import com.omnimerchant.common.dto.R;
import com.omnimerchant.common.exception.*;
import com.omnimerchant.common.util.JwtUtil.WidgetCustomerPrincipal;
import com.omnimerchant.tenant.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Objects;
import java.util.function.Supplier;

/** 买家通过完整地址确认按钮提交申请；模型没有此接口的调用权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/widget")
public class WidgetAddressController {
    private final AddressChangeDraftService drafts;
    @GetMapping("/address-draft")
    public R<?> latest(@RequestParam String conversationUuid, HttpServletRequest request) {
        return scoped(conversationUuid, request, () -> drafts.latest(conversationUuid));
    }
    @PostMapping("/address-confirmation")
    public R<?> confirm(@RequestBody Confirmation input, HttpServletRequest request) {
        if (input == null || input.draftUuid() == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择需要确认的新地址");
        return scoped(input.conversationUuid(), request, () -> drafts.confirm(input.conversationUuid(), input.draftUuid()));
    }
    private R<?> scoped(String conversationUuid, HttpServletRequest request, Supplier<?> action) {
        var principal = (WidgetCustomerPrincipal) request.getAttribute(Constants.ATTR_WIDGET_PRINCIPAL);
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少或无效的买家会话令牌");
        if (!Objects.equals(principal.conversationUuid(), conversationUuid)) throw new BusinessException(ErrorCode.FORBIDDEN, "地址确认与买家会话不匹配");
        var previous = TenantContextHolder.get();
        TenantContextHolder.set(principal.tenantId());
        try { return R.ok(action.get()); }
        finally { if (previous == null) TenantContextHolder.clear(); else TenantContextHolder.set(previous); }
    }
    public record Confirmation(String conversationUuid, String draftUuid) { }
}
