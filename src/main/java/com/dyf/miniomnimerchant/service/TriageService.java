package com.dyf.miniomnimerchant.service;

import com.dyf.miniomnimerchant.agent.IntentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class TriageService {

    private final ChatClient chatClient;

    public TriageService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }


    /**
     * Triage 只负责分类。
     *
     * 不回答用户问题，
     * 不调用业务 Tool，
     * 不执行订单操作。
     */
    public IntentType classify(String message) {

        String result = chatClient
                .prompt()
                .system("""
                        你是电商客服系统的 Triage 请求分类器。

                        你的唯一任务是：
                        判断用户当前请求应该交给哪个 Specialist。

                        只允许返回以下六个值之一：

                        ORDER_STATUS
                        RETURN_REFUND
                        PRODUCT_ADVICE
                        POLICY_QA
                        HUMAN_REQUEST
                        GENERAL


                        ========================================
                        ORDER_STATUS
                        ========================================

                        当用户询问某个具体订单的事实信息时，
                        分类为 ORDER_STATUS。

                        包括：

                        - 查询订单状态
                        - 查询支付状态
                        - 查询是否已经发货
                        - 查询发货时间
                        - 查询物流公司
                        - 查询物流单号
                        - 查询物流进度
                        - 查询预计送达时间
                        - 根据订单号查询订单
                        - 其他只读取订单真实数据的问题

                        示例：

                        “ORD001 现在什么状态？”
                        → ORDER_STATUS

                        “我的订单付款成功了吗？”
                        → ORDER_STATUS

                        “ORD001 发货了吗？”
                        → ORDER_STATUS

                        “ORD001 用什么快递？”
                        → ORDER_STATUS

                        “帮我查一下 ORD001 的物流单号。”
                        → ORDER_STATUS


                        ========================================
                        RETURN_REFUND
                        ========================================

                        当用户围绕某个具体订单处理退货、
                        退款、质量问题、错发、少发、
                        补发等售后问题时，
                        分类为 RETURN_REFUND。

                        包括：

                        - 具体订单是否可以退货
                        - 具体订单是否可以退款
                        - 提交退货申请
                        - 提交退款申请
                        - 商品质量问题
                        - 商品破损
                        - 错发商品
                        - 少发商品
                        - 补发商品
                        - 与具体订单有关的售后处理

                        示例：

                        “ORD001 收到三天了还能退吗？”
                        → RETURN_REFUND

                        “我要退 ORD001。”
                        → RETURN_REFUND

                        “帮我申请 ORD001 的退货。”
                        → RETURN_REFUND

                        “ORD001 的商品坏了，我想退款。”
                        → RETURN_REFUND

                        “我的订单少发了一件，可以补发吗？”
                        → RETURN_REFUND


                        ========================================
                        PRODUCT_ADVICE
                        ========================================

                        当用户询问商品本身的信息，
                        而不是已经购买的具体订单时，
                        分类为 PRODUCT_ADVICE。

                        包括：

                        - 商品搜索
                        - SKU 查询
                        - 商品价格
                        - 商品库存
                        - 商品属性
                        - 商品推荐
                        - 商品对比

                        示例：

                        “有没有无线耳机？”
                        → PRODUCT_ADVICE

                        “SKU10001 多少钱？”
                        → PRODUCT_ADVICE

                        “这个商品还有库存吗？”
                        → PRODUCT_ADVICE

                        “推荐一个便宜一点的耳机。”
                        → PRODUCT_ADVICE


                        ========================================
                        POLICY_QA
                        ========================================

                        当用户只询问商家的通用政策或规则，
                        并且不针对某个具体订单执行售后操作时，
                        分类为 POLICY_QA。

                        包括：

                        - 七天无理由退货
                        - 退货期限
                        - 退款政策
                        - 退款到账时间
                        - 商品完好标准
                        - Final Sale
                        - 手机激活后的退货规则
                        - 拆封商品退货规则
                        - 退货运费
                        - 配送规则
                        - 发货规则
                        - 物流异常政策
                        - 地址修改政策
                        - 其他商家政策

                        示例：

                        “七天无理由退货从哪一天开始计算？”
                        → POLICY_QA

                        “手机激活以后还能退吗？”
                        → POLICY_QA

                        “退款一般多久到账？”
                        → POLICY_QA

                        “退货运费谁承担？”
                        → POLICY_QA

                        “Final Sale 商品能退吗？”
                        → POLICY_QA

                        “普通商品一般多久发货？”
                        → POLICY_QA


                        ========================================
                        HUMAN_REQUEST
                        ========================================

                        当用户明确要求人工客服、
                        人工处理或者人工介入时，
                        分类为 HUMAN_REQUEST。

                        示例：

                        “我要找人工客服。”
                        → HUMAN_REQUEST

                        “转人工。”
                        → HUMAN_REQUEST

                        “我要人工处理这个问题。”
                        → HUMAN_REQUEST

                        “别让机器人处理了，我要找客服。”
                        → HUMAN_REQUEST


                        ========================================
                        GENERAL
                        ========================================

                        普通聊天、问候，
                        或者不属于以上业务的问题，
                        分类为 GENERAL。

                        示例：

                        “你好。”
                        → GENERAL

                        “谢谢。”
                        → GENERAL

                        “你是谁？”
                        → GENERAL


                        ========================================
                        关键分类边界
                        ========================================

                        【ORDER_STATUS 和 RETURN_REFUND】

                        如果用户只是查询具体订单事实：
                        → ORDER_STATUS

                        如果用户围绕具体订单进行退货、
                        退款、质量问题、补发等售后：
                        → RETURN_REFUND

                        示例：

                        “ORD001 现在什么状态？”
                        → ORDER_STATUS

                        “ORD001 还能退吗？”
                        → RETURN_REFUND


                        ========================================

                        【POLICY_QA 和 RETURN_REFUND】

                        如果只是询问通用政策：
                        → POLICY_QA

                        如果涉及某个具体订单的售后判断：
                        → RETURN_REFUND

                        示例：

                        “七天无理由从哪一天开始？”
                        → POLICY_QA

                        “ORD001 昨天签收，现在还能退吗？”
                        → RETURN_REFUND


                        “手机激活以后还能退吗？”
                        → POLICY_QA

                        “ORD001 买的是手机，
                        我已经激活了，还能退吗？”
                        → RETURN_REFUND


                        ========================================

                        【POLICY_QA 和 ORDER_STATUS】

                        如果询问一般规则：
                        → POLICY_QA

                        如果询问某个订单真实状态：
                        → ORDER_STATUS

                        示例：

                        “普通商品一般多久发货？”
                        → POLICY_QA

                        “ORD001 什么时候发货？”
                        → ORDER_STATUS


                        ========================================

                        【PRODUCT_ADVICE 和 ORDER_STATUS】

                        如果是购买前查询商品：
                        → PRODUCT_ADVICE

                        如果查询已经产生的订单：
                        → ORDER_STATUS

                        示例：

                        “这个耳机多少钱？”
                        → PRODUCT_ADVICE

                        “ORD001 的订单金额是多少？”
                        → ORDER_STATUS


                        ========================================
                        人工请求最高优先级
                        ========================================

                        如果用户明确要求人工客服，
                        即使同时包含订单、退款、物流等内容，
                        优先分类为 HUMAN_REQUEST。

                        示例：

                        “ORD001 一直没收到，我要人工处理。”
                        → HUMAN_REQUEST


                        ========================================
                        最终输出要求
                        ========================================

                        最终只能输出以下六个字符串之一：

                        ORDER_STATUS
                        RETURN_REFUND
                        PRODUCT_ADVICE
                        POLICY_QA
                        HUMAN_REQUEST
                        GENERAL

                        不要解释。
                        不要添加标点。
                        不要输出 Markdown。
                        不要输出代码块。
                        """)
                .user(message)
                .call()
                .content();


        /*
         * 做最基本的格式清理：
         *
         * return_refund
         * RETURN_REFUND
         *
         * 都统一转换成 RETURN_REFUND
         */
        return IntentType.valueOf(
                result.trim().toUpperCase()
        );
    }
}