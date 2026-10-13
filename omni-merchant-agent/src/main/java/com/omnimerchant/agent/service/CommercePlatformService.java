package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.dto.CommerceDtos;
import com.omnimerchant.agent.entity.Customer;
import com.omnimerchant.agent.entity.OrderInfo;
import com.omnimerchant.agent.entity.Product;
import com.omnimerchant.agent.entity.ReturnRequest;
import com.omnimerchant.agent.mapper.CustomerMapper;
import com.omnimerchant.agent.mapper.OrderInfoMapper;
import com.omnimerchant.agent.mapper.ProductMapper;
import com.omnimerchant.agent.mapper.ReturnRequestMapper;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.omnimerchant.agent.dto.CommerceDtos.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommercePlatformService {

    private final CustomerMapper customerMapper;
    private final OrderInfoMapper orderMapper;
    private final ProductMapper productMapper;
    private final ReturnRequestMapper returnRequestMapper;
    private final ObjectMapper objectMapper;
    private final CommerceApprovalService approvalService;
    private final AddressChangeDraftService addressDrafts;
    private final CommerceActionAuditService actionAudit;






    public IPage<CommerceDtos.CustomerVO> listCustomers(String keyword, int page, int size) {
        var wrapper = new LambdaQueryWrapper<Customer>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Customer::getEmail, keyword)
                        .or().like(Customer::getDisplayName, keyword)
                        .or().like(Customer::getPhone, keyword))
                .orderByDesc(Customer::getLastOrderAt)
                .orderByDesc(Customer::getCreatedAt);
        return customerMapper.selectPage(new Page<>(page, clampSize(size)), wrapper).convert(this::toCustomerVO);
    }

    public CommerceDtos.CustomerVO getCustomer(Long id) {
        var customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在");
        }
        return toCustomerVO(customer);
    }







    public IPage<CommerceDtos.OrderVO> listOrders(String keyword, String status, int page, int size) {
        var wrapper = new LambdaQueryWrapper<OrderInfo>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(OrderInfo::getExternalOrderNumber, keyword)
                        .or().like(OrderInfo::getExternalOrderId, keyword)
                        .or().like(OrderInfo::getCustomerEmail, keyword)
                        .or().like(OrderInfo::getTrackingNumber, keyword))
                .eq(status != null && !status.isBlank(), OrderInfo::getOrderStatus, status)
                .orderByDesc(OrderInfo::getPlacedAt);
        return orderMapper.selectPage(new Page<>(page, clampSize(size)), wrapper).convert(this::toOrderVO);
    }

    public CommerceDtos.OrderVO getOrder(Long id) {
        var order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return toOrderVO(order);
    }

    public List<com.omnimerchant.agent.dto.HelpdeskDtos.ActionRequestVO> orderAfterSales(Long id) {
        var order = getOrder(id);
        var conversation = new com.omnimerchant.agent.entity.Conversation();
        conversation.setRelatedOrderId(order.externalOrderNumber());
        return approvalService.forConversation(conversation);
    }

    public CommerceDtos.OrderVO getOrderByNumber(String orderNumber) {
        var order = findOrder(orderNumber);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return toOrderVO(order);
    }


    public IPage<CommerceDtos.ProductVO> listProducts(String keyword, String category, int page, int size) {
        var wrapper = new LambdaQueryWrapper<Product>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Product::getTitle, keyword)
                        .or().like(Product::getDescriptionPlain, keyword)
                        .or().like(Product::getDefaultSku, keyword)
                        .or().like(Product::getTags, keyword))
                .and(category != null && !category.isBlank(), w -> w
                        .eq(Product::getCategoryL1, category)
                        .or().eq(Product::getCategoryL2, category)
                        .or().eq(Product::getProductType, category))
                .orderByDesc(Product::getStatus)
                .orderByDesc(Product::getUpdatedAt);
        return productMapper.selectPage(new Page<>(page, clampSize(size)), wrapper).convert(this::toProductVO);
    }

    public CommerceDtos.ProductVO getProduct(Long id) {
        var product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return toProductVO(product);
    }

    public int markProductsForReindex() {
        var products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1));
        for (var product : products) {
            product.setVectorSynced(0);
            product.setVectorSyncedAt(null);
            productMapper.updateById(product);
        }
        return products.size();
    }

    // ==================================================================
    // 客户诉求准备与创建：退货、退款或补发只创建待人工审批申请；地址先准备候选草稿，客户确认后再创建申请。
    // 工具调用保留订单归属核验和租户上下文，工作人员审批与本地执行分开；本服务没有外部支付或物流写入口。
    // ==================================================================

    @Transactional
    public ReturnActionResult createReturnRequest(String orderNumber, String customerEmail,
                                                  String reason, String itemsJson) {
        var lookup = queryOrder(orderNumber, customerEmail);
        if (!lookup.verified()) {
            return ReturnActionResult.rejected(orderNumber, "IDENTITY_VERIFICATION_REQUIRED",
                    "请先提供下单邮箱或手机号，核验后才能创建退货申请。");
        }
        var request = new ReturnRequest();
        request.setTenantId(tenantIdOf(lookup));
        request.setRequestNo(nextOrderRequestNo("RET"));
        request.setRequestType("RETURN");
        request.setExternalOrderNumber(lookup.orderId());
        request.setCustomerEmail(customerEmail);
        request.setReason(concise(reason, 512));
        request.setRequestedItems(normalizeJsonText(itemsJson));
        request.setAmount(lookup.totalAmount());
        request.setCurrency(lookup.currency());
        request.setPriority(2);
        request.setStatus(1);
        request.setApprovalRequiredReason("AI_CREATED_PENDING_HUMAN_APPROVAL");
        returnRequestMapper.insert(request);
        return ReturnActionResult.created(request);
    }

    @Transactional
    public ReturnActionResult requestRefundOrReplacement(String orderNumber, String customerEmail,
                                                         String action, String reason) {
        var lookup = queryOrder(orderNumber, customerEmail);
        if (!lookup.verified()) {
            return ReturnActionResult.rejected(orderNumber, "IDENTITY_VERIFICATION_REQUIRED",
                    "请先核验订单归属，再提交退款或补发申请。");
        }
        var request = new ReturnRequest();
        request.setTenantId(tenantIdOf(lookup));
        request.setRequestNo(nextOrderRequestNo("ACT"));
        request.setRequestType("replacement".equalsIgnoreCase(action) ? "REPLACEMENT" : "REFUND");
        request.setExternalOrderNumber(lookup.orderId());
        request.setCustomerEmail(customerEmail);
        request.setReason(concise(reason, 512));
        request.setAmount(lookup.totalAmount());
        request.setCurrency(lookup.currency());
        request.setPriority(lookup.totalAmount() != null && lookup.totalAmount().compareTo(new BigDecimal("100")) > 0 ? 3 : 2);
        request.setStatus(1);
        request.setApprovalRequiredReason("PAYMENT_OR_FULFILLMENT_ACTION_REQUIRES_HUMAN_APPROVAL");
        returnRequestMapper.insert(request);
        return ReturnActionResult.created(request);
    }

    @Transactional
    public ReturnActionResult requestAddressChange(String orderNumber, String customerEmail, String newAddress) {
        return requestAddressChange(orderNumber, customerEmail, newAddress, null);
    }

    @Transactional
    public ReturnActionResult requestAddressChange(String orderNumber, String customerEmail, String rawInput, String conversationUuid) {
        var order = findOrder(orderNumber);
        if (order == null || !matchesCustomer(order, customerEmail)) {
            return ReturnActionResult.rejected(orderNumber, "IDENTITY_VERIFICATION_REQUIRED", "请先核验订单归属，再确认地址修改申请。");
        }
        return addressDrafts.prepare(order, customerEmail, rawInput, conversationUuid);
    }

    /**
     * 核验订单归属（复现自参考项目 queryOrder）。
     * 只有邮箱或手机号与订单匹配，才认为通过核验，后续才允许创建审批请求。
     */
    public OrderLookup queryOrder(String orderNumber, String customerEmailOrPhone) {
        var order = findOrder(orderNumber);
        if (order == null) {
            return OrderLookup.notFound(orderNumber);
        }
        var verified = matchesCustomer(order, customerEmailOrPhone);
        if (!verified) {
            return OrderLookup.requiresVerification(order);
        }
        var conversation = new com.omnimerchant.agent.entity.Conversation();
        conversation.setRelatedOrderId(order.getExternalOrderNumber());
        var progress = approvalService.forConversation(conversation).stream()
                .map(row -> new AfterSalesProgress(row.requestNo(), row.actionType(), row.statusLabel(),
                        row.execution().state(), row.execution().label())).toList();
        return OrderLookup.verified(order, parseJsonList(order.getOrderItems()), progress, actionAudit.refundMode(order.getId()));
    }

    /**
     * 查询物流轨迹（复现自参考项目 trackLogistics）。
     * 从订单缓存按 trackingNumber 查，返回状态、预计送达与轨迹 checkpoints。
     */
    public LogisticsLookup trackLogistics(String trackingNumber) {
        var order = orderMapper.selectOne(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getTrackingNumber, trackingNumber)
                .last("LIMIT 1"));
        if (order == null) {
            return new LogisticsLookup(trackingNumber, "NOT_FOUND", null, List.of(),
                    "No shipment was found for this tracking number.");
        }
        return new LogisticsLookup(trackingNumber,
                valueOr(order.getTrackingStatus(), "UNKNOWN"),
                order.getEstimatedDeliveryAt() == null ? null : order.getEstimatedDeliveryAt().toString(),
                parseJsonList(order.getTrackingHistory()),
                "Shipment data comes from the tenant order cache.");
    }

    private boolean matchesCustomer(OrderInfo order, String customerEmailOrPhone) {
        if (customerEmailOrPhone == null || customerEmailOrPhone.isBlank()) {
            return false;
        }
        var input = customerEmailOrPhone.trim().toLowerCase(Locale.ROOT);
        return input.equals(valueOr(order.getCustomerEmail(), "").toLowerCase(Locale.ROOT))
                || input.equals(valueOr(order.getCustomerPhone(), "").toLowerCase(Locale.ROOT));
    }

    private Long tenantIdOf(OrderLookup lookup) {
        var order = findOrder(lookup.orderId());
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return order.getTenantId();
    }

    private String nextOrderRequestNo(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** 把 items/newAddress 归一成 JSON 文本（复现自参考项目 normalizeJsonText）。 */
    private String normalizeJsonText(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof String text) {
                var trimmed = text.trim();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    objectMapper.readTree(trimmed);
                    return trimmed;
                }
                return objectMapper.writeValueAsString(List.of(Map.of("description", trimmed)));
            }
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[{\"description\":\"" + concise(String.valueOf(value), 512).replace("\"", "\\\"") + "\"}]";
        }
    }

    /** 解析订单商品明细 JSON（容错，失败返回空列表）。 */
    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String concise(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
















    /**
     * 商品目录检索（复现自参考项目 searchProductCatalog）。
     * 供 ProductTools 调用：按关键词 / 类目 / 最高价过滤，返回结构化商品卡片。
     */
    public List<ProductRecommendation> searchProductCatalog(String query, BigDecimal maxPrice, String category, int limit) {
        var normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        var wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1)
                .le(maxPrice != null, Product::getPrice, maxPrice)
                .and(category != null && !category.isBlank(), w -> w
                        .eq(Product::getCategoryL1, category)
                        .or().eq(Product::getCategoryL2, category)
                        .or().eq(Product::getProductType, category))
                .and(!normalized.isBlank(), w -> w
                        .like(Product::getTitle, normalized)
                        .or().like(Product::getDescriptionPlain, normalized)
                        .or().like(Product::getTags, normalized)
                        .or().like(Product::getDefaultSku, normalized))
                .last("LIMIT " + Math.max(1, Math.min(limit, 10)));
        var products = productMapper.selectList(wrapper);
        if (products.isEmpty() && !normalized.isBlank()) {
            products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                    .eq(Product::getStatus, 1)
                    .le(maxPrice != null, Product::getPrice, maxPrice)
                    .last("LIMIT " + Math.max(1, Math.min(limit, 10))));
        }
        return products.stream()
                .sorted(Comparator.comparing(Product::getTotalStock, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(p -> new ProductRecommendation(
                        p.getId(),
                        p.getTitle(),
                        p.getDefaultSku(),
                        p.getCurrency(),
                        p.getPrice(),
                        p.getStockStatus(),
                        p.getTotalStock(),
                        p.getFeaturedImageUrl(),
                        concise(p.getDescriptionPlain(), 220)))
                .toList();
    }

    private CommerceDtos.CustomerVO toCustomerVO(Customer c) {
        return new CommerceDtos.CustomerVO(c.getId(), c.getExternalCustomerId(), c.getEmail(), c.getPhone(),
                c.getDisplayName(), c.getCountryCode(), c.getLanguagePref(), c.getCustomerTier(),
                c.getTotalOrders(), c.getTotalSpent(), c.getLastOrderAt(), c.getIsBlacklisted(), c.getCreatedAt());
    }

    private CommerceDtos.OrderVO toOrderVO(OrderInfo o) {
        var refundMode = actionAudit.refundMode(o.getId());
        return new CommerceDtos.OrderVO(o.getId(), o.getExternalOrderId(), o.getExternalOrderNumber(), o.getPlatform(),
                o.getCustomerEmail(), o.getCustomerName(), o.getCustomerPhone(), o.getOrderStatus(),
                o.getPaymentStatus(), o.getFulfillmentStatus(), o.getCurrency(), o.getTotalAmount(),
                o.getRefundedAmount(), o.getOrderItems(), o.getTrackingNumber(), o.getTrackingCarrier(),
                o.getTrackingStatus(), o.getTrackingHistory(), o.getEstimatedDeliveryAt(),
                o.getActualDeliveryAt(), o.getPlacedAt(), o.getUpdatedAt(), refundMode, false,
                "LOCAL_SIMULATION".equals(refundMode) ? "本地模拟已退款；未调用真实支付渠道，实际支付退款未确认" : "实际支付退款未确认");
    }

    private CommerceDtos.ProductVO toProductVO(Product p) {
        return new CommerceDtos.ProductVO(p.getId(), p.getExternalProductId(), p.getHandle(), p.getTitle(),
                p.getBrand(), p.getProductType(), p.getCategoryL1(), p.getCategoryL2(), p.getDefaultSku(),
                p.getCurrency(), p.getPrice(), p.getTotalStock(), p.getStockStatus(), p.getFeaturedImageUrl(),
                p.getRatingAvg(), p.getRatingCount(), p.getVectorSynced(), p.getStatus(), p.getUpdatedAt());
    }




















    private OrderInfo findOrder(String orderNumber) {
        var normalized = required(orderNumber, "orderNumber").trim();
        var noHash = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderInfo>()
                .and(w -> w.eq(OrderInfo::getExternalOrderNumber, normalized)
                        .or().eq(OrderInfo::getExternalOrderNumber, "#" + noHash)
                        .or().eq(OrderInfo::getExternalOrderId, normalized)
                        .or().eq(OrderInfo::getExternalOrderId, noHash))
                .last("LIMIT 1"));
    }






    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return value;
    }



    /**
     * 分页大小保护。
     * <p>
     * 暂时最多 100。
     */
    private int clampSize(int size) {

        if (size <= 0) {
            return 20;
        }

        return Math.min(size, 100);
    }

    // ==================================================================
    // 订单核验与创建结果（复现自参考项目同名 record，字段保持一致）
    // ==================================================================

    public record OrderLookup(
            String orderId,
            String status,
            boolean verified,
            List<Map<String, Object>> items,
            BigDecimal totalAmount,
            String currency,
            String trackingNumber,
            String trackingCarrier,
            String trackingStatus,
            String fulfillmentStatus,
            String shippingAddress,
            String message,
            String paymentStatus,
            List<AfterSalesProgress> afterSalesProgress,
            boolean paymentRefundConfirmed, String refundExecutionMode) {
        public OrderLookup(String orderId, String status, boolean verified, List<Map<String, Object>> items,
                BigDecimal totalAmount, String currency, String trackingNumber, String trackingCarrier,
                String trackingStatus, String fulfillmentStatus, String shippingAddress, String message) {
            this(orderId, status, verified, items, totalAmount, currency, trackingNumber, trackingCarrier,
                    trackingStatus, fulfillmentStatus, shippingAddress, message, null, List.of(), false, null);
        }
        static OrderLookup notFound(String orderNumber) {
            return new OrderLookup(orderNumber, "NOT_FOUND", false, List.of(), null, null,
                    null, null, null, null, null, "本店铺没有找到该订单。");
        }

        static OrderLookup requiresVerification(OrderInfo order) {
            return new OrderLookup(order.getExternalOrderNumber(), "IDENTITY_VERIFICATION_REQUIRED", false,
                    List.of(), null, order.getCurrency(), null, null, null, order.getFulfillmentStatus(),
                    null, "请先提供下单邮箱或手机号，核验后才能查询订单详情和售后进度。");
        }

        static OrderLookup verified(OrderInfo order, List<Map<String, Object>> items, List<AfterSalesProgress> progress, String refundMode) {
            return new OrderLookup(order.getExternalOrderNumber(), order.getOrderStatus(), true, items,
                    order.getTotalAmount(), order.getCurrency(), order.getTrackingNumber(),
                    order.getTrackingCarrier(), order.getTrackingStatus(), order.getFulfillmentStatus(),
                    order.getShippingAddress(), ("LOCAL_SIMULATION".equals(refundMode) ? "本地模拟退款已完成；未调用真实支付渠道，实际支付退款未确认。" : "已核验本地订单。")
                            + "售后申请、审批、执行与支付退款是不同状态；本系统没有支付退款执行凭据，不能宣称资金到账。",
                    order.getPaymentStatus(), progress, false, refundMode);
        }
    }

    public record ReturnActionResult(
            String requestNo,
            String status,
            String actionType,
            String orderNumber,
            String message) {
        public static ReturnActionResult rejected(String orderNumber, String status, String message) {
            return new ReturnActionResult(null, status, null, orderNumber, message);
        }

        public static ReturnActionResult existing(ReturnRequest request) {
            if (request == null) throw new BusinessException(ErrorCode.NOT_FOUND, "已确认的地址申请不存在，请联系人工核实");
            return switch (request.getStatus()) {
                case 1, 2 -> created(request);
                case 3 -> new ReturnActionResult(request.getRequestNo(), "APPROVED_MANUAL", request.getRequestType(), request.getExternalOrderNumber(), "已有申请已批准，仍需授权人员单独执行。");
                case 4 -> new ReturnActionResult(request.getRequestNo(), "REJECTED", request.getRequestType(), request.getExternalOrderNumber(), "已有申请已拒绝，未重复创建申请。");
                default -> new ReturnActionResult(request.getRequestNo(), "EXISTING_REQUEST", request.getRequestType(), request.getExternalOrderNumber(), "已有申请已处理，请查询最新订单及执行记录核实；未重复创建申请，不代表外部操作或资金到账。");
            };
        }

        public static ReturnActionResult created(ReturnRequest request) {
            return new ReturnActionResult(request.getRequestNo(), "PENDING_HUMAN_APPROVAL",
                    request.getRequestType(), request.getExternalOrderNumber(),
                    "申请已提交，等待人工审批；尚未执行订单修改、退款或补发，不能表示退款成功或资金到账。");
        }
    }

    public record AfterSalesProgress(String requestNo, String actionType, String approvalStatus,
                                    String executionStatus, String executionMessage) {}

    /** 物流查询结果（复现自参考项目 LogisticsLookup）。 */
    public record LogisticsLookup(
            String trackingNumber,
            String status,
            String estimatedDelivery,
            List<Map<String, Object>> checkpoints,
            String message) {
    }

    /** 商品推荐卡片（复现自参考项目 ProductRecommendation）。 */
    public record ProductRecommendation(
            Long productId,
            String title,
            String sku,
            String currency,
            BigDecimal price,
            String stockStatus,
            Integer stock,
            String imageUrl,
            String reason) {
    }
}
