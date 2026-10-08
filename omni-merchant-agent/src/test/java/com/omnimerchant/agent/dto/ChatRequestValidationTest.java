package com.omnimerchant.agent.service;

import com.omnimerchant.agent.dto.ChatRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatRequest 参数校验测试：conversationUuid / message 必填，message 最长 2000。
 */
class ChatRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validRequestShouldPass() {
        var request = new ChatRequest("conv-1", "退货期限是多少天？", "POLICY_QA");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void blankConversationUuidShouldFail() {
        var request = new ChatRequest("  ", "你好", "POLICY_QA");
        assertThat(validator.validate(request))
                .anyMatch(v -> v.getPropertyPath().toString().equals("conversationUuid"));
    }

    @Test
    void blankMessageShouldFail() {
        var request = new ChatRequest("conv-1", "", "POLICY_QA");
        assertThat(validator.validate(request))
                .anyMatch(v -> v.getPropertyPath().toString().equals("message"));
    }

    @Test
    void tooLongMessageShouldFail() {
        var request = new ChatRequest("conv-1", "a".repeat(2001), "POLICY_QA");
        assertThat(validator.validate(request))
                .anyMatch(v -> v.getPropertyPath().toString().equals("message"));
    }

    @Test
    void exactly2000CharsShouldPass() {
        var request = new ChatRequest("conv-1", "a".repeat(2000), null);
        assertThat(validator.validate(request)).isEmpty();
    }
}
