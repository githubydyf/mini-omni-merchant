package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.OrderInfo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AddressNormalizationServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final AddressNormalizationService service = new AddressNormalizationService(json);
    OrderInfo order() {
        var order = new OrderInfo(); order.setCustomerName("测试收件人"); order.setCustomerPhone("13800000000");
        order.setShippingCountry("CN"); order.setShippingState("北京市朝阳区"); order.setShippingZip("100000");
        return order;
    }
    @Test void ordinaryChineseAddressExtractsRegionStreetAndZip() {
        var result = service.normalize("请把订单地址修改为广东省广州市天河区中山大道2号，邮编520000", order());
        assertThat(result.missingFields()).isEmpty();
        assertThat(result.address().path("state").asText()).isEqualTo("广东省广州市天河区");
        assertThat(result.address().path("address").asText()).isEqualTo("中山大道2号");
        assertThat(result.address().path("zip").asText()).isEqualTo("520000");
        assertThat(result.address().path("recipient").asText()).isEqualTo("测试收件人");
        assertThat(result.address().path("phone").asText()).isEqualTo("13800000000");
    }
    @Test void structuredJsonUsesExplicitNewContactAndCanonicalCountry() {
        var result = service.normalize("{\"recipient\":\"新收件人\",\"phone\":\"13900000000\",\"address\":\"中山大道2号\",\"country\":\"中国\",\"state\":\"广东省广州市天河区\",\"zip\":\"520000\"}", order());
        assertThat(result.missingFields()).isEmpty(); assertThat(result.address().path("country").asText()).isEqualTo("CN");
        assertThat(result.address().path("phone").asText()).isEqualTo("13900000000");
    }
    @Test void missingPhoneAndNameAreNotInvented() {
        var order = order(); order.setCustomerPhone(null); order.setCustomerName(null);
        var result = service.normalize("广东省广州市天河区中山大道2号，邮编520000", order);
        assertThat(result.missingFields()).contains("收件人", "联系电话"); assertThat(result.address().has("phone")).isFalse();
    }
    @Test void explicitContactChangeNeverFallsBackToOldContact() {
        var result = service.normalize("请修改收件人和电话，地址广东省广州市天河区中山大道2号，邮编520000", order());
        assertThat(result.missingFields()).contains("收件人", "联系电话");
    }
    @Test void newRegionWithoutZipDoesNotBorrowOldZip() {
        var result = service.normalize("广东省广州市天河区中山大道2号", order());
        assertThat(result.missingFields()).contains("六位邮编");
    }
    @Test void streetOnlyRetainsVerifiedSameOrderRegionAndZip() {
        var result = service.normalize("请把街道地址改成测试街道8号", order());
        assertThat(result.address().path("address").asText()).isEqualTo("测试街道8号");
        assertThat(result.address().path("state").asText()).isEqualTo("北京市朝阳区");
        assertThat(result.address().path("zip").asText()).isEqualTo("100000");
    }
    @Test void reliableShippingContactsTakePriorityOverCustomerContacts() {
        var order = order(); order.setShippingAddress("{\"recipient\":\"订单收件人\",\"phone\":\"13700000000\"}");
        var result = service.normalize("广东省广州市天河区中山大道2号，邮编520000", order);
        assertThat(result.address().path("recipient").asText()).isEqualTo("订单收件人");
        assertThat(result.address().path("phone").asText()).isEqualTo("13700000000");
    }
    @Test void missingDoorAndAmbiguousRegionsRequireSupplement() {
        assertThat(service.normalize("广东省广州市天河区，邮编520000", order()).missingFields()).contains("详细街道门牌");
        assertThat(service.normalize("广东省广州市天河区中山大道2号，或者浙江省杭州市西湖区测试路1号，邮编520000", order()).missingFields()).contains("省市区", "详细街道门牌");
    }
    @Test void sharedValidatorAcceptsNormalizedCandidate() {
        var result = service.normalize("广东省广州市天河区中山大道2号（邮编520000，中国）", order());
        assertThat(service.validate(result.address())).isEqualTo(result.address());
    }
    @Test void invalidExplicitPhoneIsNeverReplacedWithOldPhone() {
        var result = service.normalize("{\"phone\":\"不存在的手机\",\"address\":\"中山大道2号\",\"country\":\"CN\",\"state\":\"广东省广州市天河区\",\"zip\":\"520000\"}", order());
        assertThat(result.missingFields()).contains("联系电话");
    }
    @Test void ambiguousRegionWithoutAdministrativeSuffixDoesNotBorrowOldRegionForCompleteAddress() {
        var result = service.normalize("请把地址改成广东广州天河中山大道2号，邮编520000", order());
        assertThat(result.missingFields()).contains("详细街道门牌");
    }
    @Test void invalidNewZipCannotSilentlyBorrowVerifiedOldZip() {
        var result = service.normalize("北京市朝阳区测试路8号，邮编123", order());
        assertThat(result.missingFields()).contains("六位邮编");
    }
}
