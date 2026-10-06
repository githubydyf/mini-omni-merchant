package com.dyf.miniomnimerchant.tool;

import com.dyf.miniomnimerchant.dto.RefundRequestResult;
import com.dyf.miniomnimerchant.service.CommerceActionRequestService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ReturnTools {

    private final CommerceActionRequestService actionRequestService;


    public ReturnTools(
            CommerceActionRequestService actionRequestService
    ) {

        this.actionRequestService =
                actionRequestService;
    }


    /**
     * 创建退款申请
     */
    @Tool(
            description = """
                    为指定订单创建退款申请。

                    该工具只用于用户已经明确要求退款的情况。

                    使用前应确认：
                    1. 用户明确要求提交退款；
                    2. 已查询真实订单；
                    3. 已根据退款或售后政策判断当前问题；
                    4. 不应因为用户仅咨询退款政策而调用本工具。

                    本工具只创建内部退款处理请求。

                    创建成功后请求状态为 PENDING，
                    表示等待后续审核和处理。

                    本工具不会直接执行资金退款，
                    不会直接修改订单 payment_status 为 refunded。

                    参数：
                    orderNo：订单编号；
                    reason：用户退款原因。
                    """
    )
    public RefundRequestResult requestRefund(
            String orderNo,
            String reason
    ) {



        return actionRequestService
                .requestRefund(
                        orderNo,
                        reason
                );
    }
}