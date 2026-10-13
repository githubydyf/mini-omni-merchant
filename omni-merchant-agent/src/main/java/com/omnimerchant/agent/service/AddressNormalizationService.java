package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.omnimerchant.agent.entity.OrderInfo;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/** 只提取明确字段，不猜测地名、邮编、联系人；歧义交给客户补充。 */
@Service
@RequiredArgsConstructor
public class AddressNormalizationService {
    private final ObjectMapper json;
    private static final List<String> FIELDS = List.of("recipient", "phone", "address", "country", "state", "zip");
    private static final Map<String, String> LABELS = Map.of("recipient", "收件人", "phone", "联系电话",
            "address", "详细街道门牌", "country", "国家", "state", "省市区", "zip", "六位邮编");
    private static final Pattern REGION = Pattern.compile(
            "((?:北京市|上海市|天津市|重庆市)[\\p{IsHan}]{1,10}(?:区|县)|"
            + "(?:广东省|浙江省|江苏省|山东省|福建省|四川省|湖北省|湖南省|河北省|河南省|安徽省|江西省|陕西省|山西省|辽宁省|吉林省|黑龙江省|云南省|贵州省|甘肃省|青海省|海南省|台湾省|内蒙古自治区|广西壮族自治区|西藏自治区|宁夏回族自治区|新疆维吾尔自治区)[\\p{IsHan}]{2,10}(?:市|自治州)[\\p{IsHan}]{1,10}(?:区|县|市))");

    public Result normalize(String raw, OrderInfo verifiedOrder) {
        if (raw == null || raw.isBlank() || raw.length() > 2000) {
            throw bad("请提供不超过 2000 字的完整新地址");
        }
        var candidate = json.createObjectNode();
        boolean structured = false;
        try {
            int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                var root = json.readTree(raw.substring(start, end + 1));
                if (root.has("newAddress")) root = root.get("newAddress");
                if (root.isObject()) {
                    structured = true;
                    for (var field : FIELDS) if (root.has(field)) candidate.set(field, root.get(field));
                }
            }
        } catch (Exception ignored) {
            // 不把损坏的 JSON 当成完整结构化地址。
        }
        if (!structured) {
            var region = REGION.matcher(raw);
            if (region.find()) {
                var state = region.group(1);
                var tail = raw.substring(region.end()).replaceFirst("^[\\s，,：:]+", "");
                tail = tail.split("邮编|收件人|联系人|电话|手机|[，,；;\\n（(]", 2)[0].trim();
                candidate.put("state", state);
                candidate.put("address", tail);
                candidate.put("country", "CN");
                if (region.find()) candidate.remove(List.of("state", "address"));
            } else {
                var street = Pattern.compile("(?:详细地址|街道地址)(?:改为|改成|修改为)?[：:\\s]*([^，,；;\\n（(]+)").matcher(raw);
                if (street.find() && !street.group(1).matches(".*[省市区县].*")) {
                    candidate.put("address", street.group(1).trim());
                }
            }
            extract(candidate, "zip", raw, "(?:邮编|邮政编码)[：:\\s]*([0-9]{6})(?![0-9])");
            extract(candidate, "phone", raw, "(?:联系电话|手机号|手机|电话)[：:\\s]*(\\+?[0-9][0-9 ()-]{5,29})");
            extract(candidate, "recipient", raw, "(?:收件人|联系人)[：:\\s]+([\\p{IsHan}·]{2,20})");
        }
        JsonNode old = null;
        try { old = json.readTree(verifiedOrder.getShippingAddress()); } catch (Exception ignored) { }
        if (!Pattern.compile("收件人|联系人").matcher(raw).find()) {
            fallback(candidate, "recipient", oldText(old, "recipient", "name"), verifiedOrder.getCustomerName());
        }
        if (!Pattern.compile("电话|手机").matcher(raw).find()) {
            fallback(candidate, "phone", oldText(old, "phone", "phone"), verifiedOrder.getCustomerPhone());
        }
        if (!candidate.has("state") && !raw.matches("(?s).*[省市区县].*")) {
            fallback(candidate, "state", oldText(old, "state", "state"), verifiedOrder.getShippingState());
            fallback(candidate, "country", oldText(old, "country", "country"), verifiedOrder.getShippingCountry());
        }
        if (text(candidate, "state").equals(Objects.toString(verifiedOrder.getShippingState(), ""))
                && !raw.contains("邮编") && !raw.contains("邮政编码")) {
            fallback(candidate, "zip", oldText(old, "zip", "zip"), verifiedOrder.getShippingZip());
        }
        return inspect(candidate);
    }

    public ObjectNode validate(JsonNode address) {
        var result = inspect(address);
        if (!result.missingFields().isEmpty()) throw bad("请补充或核实新地址字段：" + String.join("、", result.missingFields()));
        return result.address();
    }

    private Result inspect(JsonNode input) {
        var normalized = json.createObjectNode();
        var missing = new ArrayList<String>();
        for (var field : FIELDS) {
            String value = text(input, field).trim();
            if ("country".equals(field) && Set.of("中国", "中华人民共和国", "China", "cn").contains(value)) value = "CN";
            boolean valid = !value.isBlank() && value.length() <= ("address".equals(field) ? 500 : "state".equals(field) ? 64 : 100)
                    && value.chars().noneMatch(Character::isISOControl);
            valid &= switch (field) {
                case "country" -> "CN".equals(value);
                case "zip" -> value.matches("[0-9]{6}");
                case "phone" -> value.matches("[+0-9() -]{6,30}");
                case "address" -> value.length() >= 5;
                case "state" -> value.matches("[\\p{IsHan}]{2,64}") && value.matches(".*[省市区县州].*");
                default -> true;
            };
            if (valid) normalized.put(field, value); else missing.add(LABELS.get(field));
        }
        return new Result(normalized, List.copyOf(missing));
    }

    private void extract(ObjectNode candidate, String field, String raw, String regex) {
        var matcher = Pattern.compile(regex).matcher(raw);
        if (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!matcher.find()) candidate.put(field, value);
            else candidate.putNull(field);
        }
    }
    private void fallback(ObjectNode node, String field, String first, String second) {
        if (node.has(field)) return;
        var value = first == null || first.isBlank() ? second : first;
        if (value != null && !value.isBlank()) node.put(field, value);
    }
    private String oldText(JsonNode old, String field, String alternate) {
        String value = text(old, field);
        return value.isBlank() ? text(old, alternate) : value;
    }
    private static String text(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : "";
    }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    public record Result(ObjectNode address, List<String> missingFields) { }
}
