package com.omnimerchant.agent.controller;

import com.omnimerchant.agent.service.*;
import com.omnimerchant.common.dto.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 客户 / 订单 / 商品查询接口。
 *
 * <p>只读接口对已认证的租户成员开放（数据由 TenantInterceptor + TenantLineInnerInterceptor
 * 按租户隔离）；商品重建索引属于知识库写操作，需要 {@code knowledge:write} 权限。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommerceController {

    private final CommercePlatformService commerceService;


    @GetMapping("/customers")
    public R<?> customers(@RequestParam(required = false) String keyword,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(commerceService.listCustomers(keyword, page, size));
    }

    @GetMapping("/customers/{id}")
    public R<?> customer(@PathVariable Long id) {
        return R.ok(commerceService.getCustomer(id));
    }


    @GetMapping("/orders")
    public R<?> orders(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(commerceService.listOrders(keyword, status, page, size));

    }

    @GetMapping("/orders/{id}")
    public R<?> order(@PathVariable Long id) {
        return R.ok(commerceService.getOrder(id));
    }

    @GetMapping("/orders/{id}/after-sales")
    public R<?> orderAfterSales(@PathVariable Long id) {
        return R.ok(commerceService.orderAfterSales(id));
    }

    @GetMapping("/orders/by-number/{orderNumber}")
    public R<?> orderByNumber(@PathVariable String orderNumber) {
        return R.ok(commerceService.getOrderByNumber(orderNumber));

    }



    @GetMapping("/products")
    public R<?> products(@RequestParam(required = false) String keyword,
                         @RequestParam(required = false) String category,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(commerceService.listProducts(keyword, category, page, size));
    }


    @GetMapping("/products/{id}")
    public R<?> product(@PathVariable Long id) {
        return R.ok(commerceService.getProduct(id));
    }

    @PostMapping("/products/reindex")
    @PreAuthorize("@tenantAuthorization.hasPermission('knowledge:write')")
    public R<?> reindexProducts() {
        return R.ok(Map.of("queued", commerceService.markProductsForReindex()));
    }


}

