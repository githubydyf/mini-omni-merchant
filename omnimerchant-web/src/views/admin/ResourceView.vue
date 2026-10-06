<!--
  ResourceView.vue —— 配置驱动的「通用资源页」

  一份组件复用成多个管理页面：
    /admin/customers  → <ResourceView resource="customers" />
    /admin/orders     → <ResourceView resource="orders"    />
    /admin/products   → <ResourceView resource="products"  />
  路由通过 props 传入 resource（见 src/router/index.ts），
  组件再据此在 configs 表里查出「列定义 / 筛选条件 / 接口路径」，
  从而同一套模板渲染出不同页面。

  数据流：
    路由 props.resource
      └→ config = configs[resource]
           ├→ columns / detailFields / statusOptions ...（决定界面长什么样）
           └→ endpoint（决定请求打到哪个接口）
                └→ onMounted / 查询 / 翻页 / 点行 时发起 GET 请求

  注意：切换路由不会重建本组件（AdminLayout 的 router-view 没有 key），
  但三个资源页共用本组件，路由切换时只有 props.resource 变化。
  因此在下方 watch(props.resource)：切换资源即重置筛选/分页并自动重新请求，
  无需手动点「刷新」。
-->
<template>
  <div>
    <!-- ── 顶部筛选条 ──────────────────────────────────────────────
         搜索框、状态下拉是否显示，完全由 config 决定：
         某资源没配 keyword / statusOptions，对应控件就不渲染。      -->
    <FilterBar class="resource-filter">
      <!-- 关键字搜索框：仅在 config.keyword 存在时显示；回车或点「查询」触发 loadData -->
      <a-input
        v-if="config.keyword"
        v-model:value="keyword"
        allow-clear
        :placeholder="config.keyword"
        style="width: 280px"
        @press-enter="loadData"
      />
      <!-- 状态下拉：仅在 config.statusOptions 存在时显示（目前订单页有）；选中即重新请求 -->
      <a-select
        v-if="config.statusOptions"
        v-model:value="status"
        allow-clear
        placeholder="筛选状态"
        style="width: 160px"
        @change="loadData"
      >
        <a-select-option
          v-for="s in config.statusOptions"
          :key="String(s.value)"
          :value="s.value"
        >
          {{ s.label }}
        </a-select-option>
      </a-select>
      <a-button type="primary" @click="loadData">查询</a-button>
      <template #actions>
        <!-- 仅商品页显示：触发后端重建商品向量索引 -->
        <a-button v-if="resource === 'products'" @click="reindexProducts"
          >重建商品索引</a-button
        >
        <!-- 重新拉取当前资源列表 -->
        <a-button @click="loadData">
          <template #icon><ReloadOutlined /></template>刷新
        </a-button>
      </template>
    </FilterBar>

    <!-- ── 数据表格 ──────────────────────────────────────────────
         :columns 由 config.columns 派生，:data-source 是请求回来的 rows。
         :custom-row="resourceRow" 决定某行能否点击（点击开详情抽屉）。
         单元格用 #bodyCell 三种渲染：操作列 / 彩色标签 / 纯文本。   -->
    <a-card :bordered="false">
      <a-table
        :columns="columns"
        :custom-row="resourceRow"
        :data-source="rows"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1000 }"
        row-key="id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <!-- 分支 1：操作列（仅 tickets 配置会生成 actions 列，属死代码） -->
          <template v-if="column.key === 'actions'">
            <a-space>
              <a-button size="small" @click="assignTicket(record)"
                >接管</a-button
              >
              <a-button
                size="small"
                type="primary"
                @click="resolveTicket(record)"
                >解决</a-button
              >
            </a-space>
          </template>
          <!-- 分支 2：column.customTag 为真的列渲染成彩色标签，颜色由 tagColor 判定 -->
          <template v-else-if="column.customTag">
            <a-tag
              :color="tagColor(cellValue(record, column), column.dataIndex)"
            >
              {{ cellDisplay(record, column) }}
            </a-tag>
          </template>
          <!-- 分支 3：其余列渲染纯文本（cellDisplay 负责把枚举值翻成中文） -->
          <template v-else>
            {{ cellDisplay(record, column) }}
          </template>
        </template>
      </a-table>
      <!-- 分页独立于表格（表格自身 pagination 已关闭）；翻页触发 loadData -->
      <a-pagination
        v-model:current="page"
        class="pager"
        :page-size="size"
        :total="total"
        :show-size-changer="false"
        @change="loadData"
      />
    </a-card>

    <!-- ── 详情抽屉 ──────────────────────────────────────────────
         点击表格行后打开，数据来自 GET {endpoint}/{id}（见 openDetail）。
         通用部分：按 detailFields 逐项渲染；orders / products 再追加专属内容。 -->
    <a-drawer v-model:open="detailOpen" :title="detailTitle" width="720px">
      <a-spin :spinning="detailLoading">
        <template v-if="detail">
          <!-- 通用字段清单：字段与标签来自 detailFields（按 resource 取不同 map） -->
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item
              v-for="item in detailFields"
              :key="item.prop"
              :label="item.label"
            >
              <a-tag
                v-if="item.tag"
                :color="tagColor(detail[item.prop], item.prop)"
              >
                {{ displayDetailValue(item.prop) }}
              </a-tag>
              <span v-else>{{ displayDetailValue(item.prop) }}</span>
            </a-descriptions-item>
          </a-descriptions>

          <!-- 订单专属：订单商品明细表 + 物流轨迹时间线 -->
          <template v-if="props.resource === 'orders'">
            <a-divider orientation="left">订单商品</a-divider>
            <a-table
              :columns="orderItemColumns"
              :data-source="orderItems"
              :pagination="false"
              row-key="sku"
              size="small"
            />
            <a-divider orientation="left">物流轨迹</a-divider>
            <a-timeline v-if="trackingItems.length">
              <a-timeline-item
                v-for="(item, index) in trackingItems"
                :key="index"
              >
                <strong>{{
                  displayBusinessValue(item.status, "trackingStatus")
                }}</strong>
                <div class="detail-muted">
                  {{ item.time || "—" }} · {{ item.location || "—" }}
                </div>
                <div>{{ item.desc || item.description || "—" }}</div>
              </a-timeline-item>
            </a-timeline>
            <a-empty v-else description="暂无物流轨迹" />
          </template>

          <!-- 商品专属：商品主图 + 向量索引状态 -->
          <template v-if="props.resource === 'products'">
            <a-divider orientation="left">商品图片与索引</a-divider>
            <a-image
              v-if="detail.featuredImageUrl"
              :src="detail.featuredImageUrl"
              :width="220"
            />
            <a-alert
              class="detail-alert"
              :type="detail.vectorSynced ? 'success' : 'warning'"
              show-icon
              :message="
                detail.vectorSynced
                  ? '商品已完成向量索引'
                  : '商品尚未完成向量索引'
              "
            />
          </template>
        </template>
        <a-empty v-else description="暂无详情" />
      </a-spin>
    </a-drawer>
    <!-- 解决工单弹窗：仅 tickets 用到（当前无路由挂载，属死代码） -->
    <a-modal v-model:open="resolveOpen" title="解决工单" @ok="confirmResolve">
      <a-form layout="vertical"
        ><a-form-item label="解决说明"
          ><a-textarea
            v-model:value="resolveNote"
            :rows="4"
            :maxlength="1000"
            show-count /></a-form-item
      ></a-form>
    </a-modal>
  </div>
</template>







<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { ReloadOutlined } from "@ant-design/icons-vue";
import api from "@/api";
import { displayBusinessValue } from "@/utils/display";
import FilterBar from "@/components/admin/FilterBar.vue";
import type { ResourceRecord } from "@/types/contracts";

// 由路由 props 传入的资源标识："customers" | "orders" | "products"（也有 inbox/tickets 配置）
// 组件不认识 URL，只认这个语义值；URL↔资源 的映射由路由负责。
const props = defineProps<{ resource: string }>();

// 列表 / 详情的列定义单元：prop 必须与后端返回的 JSON 字段名一致
type Column = { prop: string; label: string; width?: number; tag?: boolean };
// 一种资源的完整描述：决定界面与请求
type Config = {
  title: string;          // 页面标题
  subtitle: string;       // 页面副标题
  endpoint: string;       // 列表接口路径（会拼在 /api 之后）
  keyword?: string;       // 搜索框占位符；有值才显示搜索框
  statusOptions?: { label: string; value: string | number }[]; // 状态下拉；有值才显示
  columns: Column[];      // 列表列定义
};

// ============================================================
// 资源配置表：全组件唯一的「差异来源」
// 想新增一种资源页面 → 加一条路由 props + 在这里补一份 Config，组件主体无需改。
// 注意：inbox / tickets 当前没有路由挂载（各自有专用页面 InboxView / TicketsView），
//       属于遗留死代码，联调时以 customers / orders / products 为准。
// ============================================================
const configs: Record<string, Config> = {
  // 死代码：统一收件箱实际用的是 InboxView.vue
  inbox: {
    title: "客服工作台",
    subtitle: "按会话状态、意图和客户信息扫描 AI 与人工接管队列。",
    endpoint: "/conversations",
    statusOptions: [
      { label: "AI 处理中", value: 1 },
      { label: "已完成", value: 2 },
      { label: "已升级人工", value: 3 },
      { label: "人工处理中", value: 4 },
      { label: "已关闭", value: 5 },
    ],
    columns: [
      { prop: "customerName", label: "客户", width: 150 },
      { prop: "customerEmail", label: "邮箱", width: 190 },
      { prop: "intentPrimary", label: "意图", width: 140, tag: true },
      { prop: "statusLabel", label: "状态", width: 120, tag: true },
      { prop: "priority", label: "优先级", width: 90 },
      { prop: "messageCount", label: "消息", width: 90 },
      { prop: "totalCostUsd", label: "成本 USD", width: 110 },
      { prop: "startedAt", label: "开始时间", width: 190 },
    ],
  },
  // 客户列表：GET /api/customers，支持关键字搜索；无状态筛选
  customers: {
    title: "客户",
    subtitle: "跨店铺隔离的买家画像、价值分层和黑名单状态。",
    endpoint: "/customers",
    keyword: "搜索邮箱、姓名或手机号",
    columns: [
      { prop: "displayName", label: "客户", width: 150 },
      { prop: "email", label: "邮箱", width: 190 },
      { prop: "countryCode", label: "国家", width: 90 },
      { prop: "languagePref", label: "语言", width: 90 },
      { prop: "customerTier", label: "等级", width: 100, tag: true },
      { prop: "totalOrders", label: "订单数", width: 90 },
      { prop: "totalSpent", label: "消费额", width: 120 },
      { prop: "isBlacklisted", label: "黑名单", width: 100, tag: true },
    ],
  },
  // 订单列表：GET /api/orders，支持关键字 + 状态筛选（status 传英文字符串）
  orders: {
    title: "订单",
    subtitle: "订单缓存、物流轨迹、退款状态和会话关联查询。",
    endpoint: "/orders",
    keyword: "搜索订单号、邮箱或物流号",
    statusOptions: [
      { label: "已支付", value: "paid" },
      { label: "处理中", value: "processing" },
      { label: "已发货", value: "shipped" },
      { label: "已送达", value: "delivered" },
      { label: "已退款", value: "refunded" },
      { label: "已退货", value: "returned" },
      { label: "已取消", value: "cancelled" },
    ],
    columns: [
      { prop: "externalOrderNumber", label: "订单号", width: 120 },
      { prop: "customerEmail", label: "客户邮箱", width: 190 },
      { prop: "orderStatus", label: "订单状态", width: 120, tag: true },
      { prop: "fulfillmentStatus", label: "履约状态", width: 120, tag: true },
      { prop: "totalAmount", label: "金额", width: 100 },
      { prop: "trackingNumber", label: "物流号", width: 150 },
      { prop: "trackingStatus", label: "物流状态", width: 130, tag: true },
      { prop: "estimatedDeliveryAt", label: "预计送达", width: 190 },
    ],
  },
  // 商品列表：GET /api/products，支持关键字搜索；另有「重建索引」等操作
  products: {
    title: "商品",
    subtitle: "商品缓存、库存状态和向量索引准备状态。",
    endpoint: "/products",
    keyword: "搜索商品、SKU 或标签",
    columns: [
      { prop: "title", label: "商品", width: 260 },
      { prop: "defaultSku", label: "SKU", width: 150 },
      { prop: "categoryL1", label: "一级类目", width: 110 },
      { prop: "productType", label: "类型", width: 120 },
      { prop: "price", label: "价格", width: 90 },
      { prop: "totalStock", label: "库存", width: 90 },
      { prop: "stockStatus", label: "库存状态", width: 120, tag: true },
      { prop: "vectorSynced", label: "向量索引", width: 110, tag: true },
    ],
  },
  // 死代码：人工工单实际用的是 TicketsView.vue
  tickets: {
    title: "人工工单",
    subtitle:
      "独立 Helpdesk 工单，承接 AI 升级、人工接管、SLA、CSAT 和关闭原因。",
    endpoint: "/tickets",
    statusOptions: [
      { label: "待分配", value: "OPEN" },
      { label: "处理中", value: "ASSIGNED" },
      { label: "待客户回复", value: "WAITING_CUSTOMER" },
      { label: "待审批", value: "PENDING_APPROVAL" },
      { label: "已解决", value: "RESOLVED" },
      { label: "已关闭", value: "CLOSED" },
    ],
    columns: [
      { prop: "ticketNo", label: "工单号", width: 210 },
      { prop: "conversationUuid", label: "会话", width: 220 },
      { prop: "subject", label: "主题", width: 220 },
      { prop: "intent", label: "意图", width: 130, tag: true },
      { prop: "priority", label: "优先级", width: 90 },
      { prop: "statusLabel", label: "状态", width: 110, tag: true },
      { prop: "slaState", label: "SLA", width: 110, tag: true },
      { prop: "assignedAgentId", label: "客服", width: 110 },
      { prop: "createdAt", label: "创建时间", width: 190 },
    ],
  },
};

// 当前资源的配置；资源不存在时兜底到 inbox（避免 config.value 为空）
const config = computed(() => configs[props.resource] || configs.inbox);

// 把 config.columns 翻译成 antd 表格列格式；tickets 额外追加「操作」列
const columns = computed(() => {
  const base = config.value.columns.map((col) => ({
    title: col.label,
    dataIndex: col.prop,
    key: col.prop,
    width: col.width,
    ellipsis: true,
    customTag: col.tag,          // 透传给 #bodyCell，决定该列渲染成标签还是文本
  }));
  if (props.resource === "tickets") {
    base.push({
      title: "操作",
      dataIndex: "actions",
      key: "actions",
      width: 150,
      ellipsis: false,
      customTag: false,
    });
  }
  return base;
});

// ============================================================
// 组件状态（ref）
// 注意：这些状态不知道当前是哪个资源，资源由 props.resource → config 决定。
// 切换资源时由下方 watch 统一重置（筛选/分页/详情），避免旧值串到新资源。
// ============================================================
const keyword = ref("");                              // 搜索关键字
const status = ref<string | number | null>(null);     // 状态筛选值（各资源类型不同）
const rows = ref<ResourceRecord[]>([]);               // 当前页列表数据
const total = ref(0);                                 // 总条数（供分页用）
const page = ref(1);                                  // 当前页
const size = ref(20);                                 // 每页条数（固定不可改）
const loading = ref(false);                           // 列表加载中
const detailOpen = ref(false);                        // 详情抽屉是否打开
const detailLoading = ref(false);                     // 详情加载中
const detail = ref<ResourceRecord | null>(null);      // 详情数据
const resolveOpen = ref(false);                       // 解决工单弹窗（tickets 用，死代码）
const resolveRow = ref<ResourceRecord | null>(null);  // 待解决的工单行
const resolveNote = ref("");                          // 解决说明

// 列表请求序号：切换资源/连续查询时，只有最后一次请求的结果能写回，
// 避免先发的旧资源响应晚到、覆盖新资源数据（竞态）。
let loadToken = 0;

// 哪些资源支持「点行看详情」：只有这三种有详情接口
const detailEndpoints: Record<string, string> = {
  customers: "/customers",
  orders: "/orders",
  products: "/products",
};

// 详情抽屉标题：按资源取不同字段拼标题
const detailTitle = computed(() => {
  if (!detail.value) return "详情";
  if (props.resource === "orders")
    return `订单 ${detail.value.externalOrderNumber || detail.value.id}`;
  if (props.resource === "products") return detail.value.title || "商品详情";
  if (props.resource === "customers")
    return detail.value.displayName || detail.value.email || "客户详情";
  return "详情";
});

// 详情抽屉要展示的字段清单：三种资源各一份 map，决定 label 与是否用标签渲染
const detailFields = computed(() => {
  const map: Record<string, Column[]> = {
    customers: [
      { prop: "displayName", label: "客户" },
      { prop: "email", label: "邮箱" },
      { prop: "phone", label: "手机号" },
      { prop: "countryCode", label: "国家" },
      { prop: "languagePref", label: "语言" },
      { prop: "customerTier", label: "等级", tag: true },
      { prop: "totalOrders", label: "订单数" },
      { prop: "totalSpent", label: "累计消费" },
      { prop: "lastOrderAt", label: "最近下单" },
      { prop: "isBlacklisted", label: "黑名单", tag: true },
    ],
    orders: [
      { prop: "externalOrderNumber", label: "订单号" },
      { prop: "customerEmail", label: "客户邮箱" },
      { prop: "customerName", label: "客户姓名" },
      { prop: "orderStatus", label: "订单状态", tag: true },
      { prop: "paymentStatus", label: "支付状态", tag: true },
      { prop: "fulfillmentStatus", label: "履约状态", tag: true },
      { prop: "totalAmount", label: "订单金额" },
      { prop: "refundedAmount", label: "已退款金额" },
      { prop: "trackingNumber", label: "物流号" },
      { prop: "trackingCarrier", label: "承运商" },
      { prop: "trackingStatus", label: "物流状态", tag: true },
      { prop: "estimatedDeliveryAt", label: "预计送达" },
      { prop: "placedAt", label: "下单时间" },
    ],
    products: [
      { prop: "title", label: "商品" },
      { prop: "defaultSku", label: "SKU" },
      { prop: "brand", label: "品牌" },
      { prop: "categoryL1", label: "一级类目" },
      { prop: "categoryL2", label: "二级类目" },
      { prop: "productType", label: "类型" },
      { prop: "price", label: "价格" },
      { prop: "totalStock", label: "库存" },
      { prop: "stockStatus", label: "库存状态", tag: true },
      { prop: "ratingAvg", label: "评分" },
      { prop: "ratingCount", label: "评价数" },
      { prop: "vectorSynced", label: "向量索引", tag: true },
      { prop: "updatedAt", label: "更新时间" },
    ],
  };
  return map[props.resource] || [];
});

// 订单商品明细表的列定义（固定）
const orderItemColumns = [
  { title: "SKU", dataIndex: "sku", width: 160 },
  { title: "商品", dataIndex: "title", ellipsis: true },
  { title: "数量", dataIndex: "quantity", width: 90 },
  { title: "价格", dataIndex: "price", width: 90 },
];

// 从详情的 orderItems / trackingHistory 字段解析出列表（可能是数组或 JSON 字符串）
const orderItems = computed(() => parseJsonList(detail.value?.orderItems));
const trackingItems = computed(() =>
  parseJsonList(detail.value?.trackingHistory),
);

// 按列 dataIndex 从行对象取原始字段值
function cellValue(record: ResourceRecord, column: { dataIndex: string }) {
  return record[column.dataIndex];
}

// 判定标签颜色：部分字段有专门规则，其余按关键字（异常/完成/处理中）兜底
function tagColor(value: unknown, prop: string) {
  const text = String(value);
  if (prop === "isBlacklisted") return value ? "error" : "success";
  if (prop === "stockStatus")
    return value === "low_stock"
      ? "warning"
      : value === "out_of_stock"
        ? "error"
        : "success";
  if (prop === "vectorSynced") return value ? "success" : "warning";
  if (text.includes("exception") || text.includes("cancelled")) return "error";
  if (
    text.includes("delivered") ||
    text.includes("resolved") ||
    text.includes("完成")
  )
    return "success";
  if (text.includes("processing") || text.includes("处理中"))
    return "processing";
  return "blue";
}

// 单元格显示：把后端原始值（如 paid）翻成中文（已支付）
function cellDisplay(record: ResourceRecord, column: { dataIndex: string }) {
  return displayBusinessValue(cellValue(record, column), column.dataIndex);
}

// 详情字段显示：同样走枚举翻译
function displayDetailValue(prop: string) {
  if (!detail.value) return "—";
  return displayBusinessValue(detail.value[prop], prop);
}

// 容错解析：字段可能是数组、JSON 字符串或逗号串，统一成数组
function parseJsonList(value: unknown): ResourceRecord[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  try {
    const parsed = JSON.parse(String(value));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

// a-table 的 custom-row：只有支持详情的资源才让整行可点击并打开详情
function resourceRow(record: ResourceRecord) {
  if (!detailEndpoints[props.resource]) return {};
  return {
    style: { cursor: "pointer" },
    onClick: () => openDetail(record),
  };
}

// ── 详情请求：GET {endpoint}/{id} ──
async function openDetail(record: ResourceRecord) {
  const endpoint = detailEndpoints[props.resource];
  if (!endpoint || !record?.id) return;
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    const res = await api.get(`${endpoint}/${record.id}`);
    detail.value = res.data || null;
  } finally {
    detailLoading.value = false;
  }
}

// ── 列表请求（核心）：GET {config.endpoint}?page&size&keyword&status ──
// endpoint 是响应式的（config.value），所以它总是「当前资源」的接口路径。
async function loadData() {
  const token = ++loadToken;
  loading.value = true;
  try {
    // 只把有值的条件拼进 query，避免传空字符串
    const params: { page: number; size: number; keyword?: string; status?: string | number } = { page: page.value, size: size.value };
    if (keyword.value) params.keyword = keyword.value;
    if (
      status.value !== null &&
      status.value !== undefined &&
      status.value !== ""
    )
      params.status = status.value;
    const res = await api.get(config.value.endpoint, { params });
    // 已有更新的请求发出，丢弃本次结果
    if (token !== loadToken) return;
    // 后端统一返回 { code, data: { records, total } }，拦截器已取出 data
    rows.value = res.data?.records || [];
    total.value = res.data?.total || 0;
  } finally {
    if (token === loadToken) loading.value = false;
  }
}

// ── 写入类操作（仅商品页 / tickets 使用）──

// 触发后端重建商品向量索引，随后刷新列表
async function reindexProducts() {
  const res = await api.post("/products/reindex");
  const queued = res.data?.queued;
  message.success(
    queued === null || queued === undefined
      ? "重建商品索引请求已提交"
      : `已标记 ${queued} 个商品待重建索引`,
  );
  loadData();
}

// 接管工单（tickets 用，死代码）
async function assignTicket(row: ResourceRecord) {
  await api.post(`/tickets/${row.id}/assign`, { note: "后台人工接管" });
  message.success("已接管工单");
  loadData();
}

// 打开「解决工单」弹窗（tickets 用，死代码）
async function resolveTicket(row: ResourceRecord) {
  resolveRow.value = row;
  resolveNote.value = "";
  resolveOpen.value = true;
}

// 提交解决工单（tickets 用，死代码）：校验说明非空后 POST resolve
async function confirmResolve() {
  if (!resolveRow.value || !resolveNote.value.trim()) {
    message.warning("请填写解决说明");
    return;
  }
  await api.post(`/tickets/${resolveRow.value.id}/resolve`, {
    note: resolveNote.value.trim(),
  });
  resolveOpen.value = false;
  message.success("工单已解决");
  loadData();
}

// 资源切换（customers ⇄ orders ⇄ products 共用本组件）：重置筛选、分页与详情后自动重新请求。
// 这是三个资源页之间点击切换能自动刷新数据的关键。
watch(
  () => props.resource,
  () => {
    keyword.value = "";
    status.value = null;
    page.value = 1;
    detailOpen.value = false;
    detail.value = null;
    loadData();
  },
);

// 进入页面自动拉一次列表（首次挂载；后续切换资源由上面的 watch 负责）
onMounted(loadData);
</script>

<style scoped>
.resource-filter {
  margin-bottom: 14px;
}

.detail-muted {
  color: #667085;
  font-size: 12px;
  margin: 3px 0;
}

.detail-alert {
  margin-top: 12px;
}
</style>
