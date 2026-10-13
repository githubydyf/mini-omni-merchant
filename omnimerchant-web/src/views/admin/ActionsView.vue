<template>
  <div>
    <MetricStrip :items="metrics" /><PageToolbar class="section-row"
      ><a-select
        v-model:value="status"
        allow-clear
        placeholder="全部状态"
        style="width: 180px"
        @change="load"
        ><a-select-option value="1">待审批</a-select-option
        ><a-select-option value="2">审核中</a-select-option
        ><a-select-option value="3">已批准</a-select-option
        ><a-select-option value="4">已拒绝</a-select-option><a-select-option value="5">已执行/历史完成</a-select-option></a-select
      ><template #actions
        ><a-alert
          type="warning"
          show-icon
          message="取消和改址可单独执行本地操作；退款待人工处理"
        /><a-button type="primary" @click="createOpen = true">登记本地订单申请</a-button><a-button :loading="loading" @click="load"
          ><template #icon><ReloadOutlined /></template>刷新</a-button
        ></template
      ></PageToolbar
    ><DataTableShell
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :error="error"
      :pagination="pagination"
      :scroll="{ x: 1080 }"
      @retry="load"
      @change="onTableChange"
      ><template #bodyCell="{ column, record }"
        ><template v-if="column.key === 'request'"
          ><a-button type="link" class="link-cell" @click="open(record)">{{
            record.requestNo
          }}</a-button
          ><small
            >{{ record.actionType }} · {{ record.externalOrderNumber }}</small
          ></template
        ><template v-else-if="column.key === 'status'"
          ><StatusTag
            :status="record.status"
            :label="record.statusLabel" /></template
        ><template v-else-if="column.key === 'execution'">{{ executing === `${record.source}:${record.id}` ? "正在执行本地操作" : record.execution?.label || "未提供执行凭据" }}</template><template v-else-if="column.key === 'amount'">{{
          money(record.amount, record.currency)
        }}</template
        ><template v-else-if="column.key === 'createdAt'">{{
          date(record.createdAt)
        }}</template
        ><template v-else-if="column.key === 'actions'"
          ><a-space
            ><a-popconfirm
              title="批准申请后仍需授权人员单独执行；退款等待人工处理。继续？"
              @confirm="decide(record, 'approve')"
              ><a-button
                size="small"
                type="primary"
                :disabled="finished(record)"
                >批准申请</a-button
              ></a-popconfirm
            ><a-popconfirm
              title="确认拒绝该请求？"
              @confirm="decide(record, 'reject')"
              ><a-button size="small" danger :disabled="finished(record)"
                >拒绝</a-button
              ></a-popconfirm
            ><a-popconfirm v-if="record.execution?.executable && canExecute" title="确认执行已批准的本地订单操作？支付状态不会修改。" @confirm="execute(record)">
              <a-button size="small" :loading="executing === `${record.source}:${record.id}`">执行已批准操作</a-button>
            </a-popconfirm></a-space
          ></template
        ></template
      ></DataTableShell
    ><DetailDrawer :open="drawer" title="审批详情" @close="drawer = false"
      ><a-alert
        type="warning"
        show-icon
        message="审批与执行分开记录"
        description="本地取消、改址需要明确执行。退款未接入支付渠道；退货待寄回验收；补发待人工安排。"
      /><a-descriptions
        v-if="current"
        :column="1"
        bordered
        size="small"
        class="drawer-block"
        ><a-descriptions-item label="请求号">{{
          current.requestNo
        }}</a-descriptions-item
        ><a-descriptions-item label="订单">{{
          current.externalOrderNumber
        }}</a-descriptions-item
        ><a-descriptions-item label="执行阶段">{{ current.execution?.label || "历史记录未提供执行凭据" }}</a-descriptions-item><a-descriptions-item label="真实订单状态">{{ currentOrder?.orderStatus || "—" }} / 支付：{{ currentOrder?.paymentStatus || "—" }}</a-descriptions-item><a-descriptions-item label="客户">{{
          current.customerEmail
        }}</a-descriptions-item
        ><a-descriptions-item label="风险原因">{{
          current.riskReason || "—"
        }}</a-descriptions-item
        ><a-descriptions-item label="申请内容">
          <pre>{{ current.requestedPayload || "—" }}</pre>
        </a-descriptions-item></a-descriptions
      ><div class="drawer-block"><h3>审批与执行历史</h3><a-empty v-if="!history.length" description="历史申请没有新增审计记录" /><div v-for="entry in history" :key="entry.id" class="history-entry"><strong>{{ eventLabel(entry.eventType) }} · {{ entry.state === 'EXECUTED' ? '本地执行成功' : entry.state === 'FAILED' ? '执行失败，订单修改已回滚' : entry.state === 'REJECTED' ? '已拒绝' : '已批准' }}</strong><p>操作人 {{ entry.actorId }} · {{ date(entry.createdAt) }}</p><p>{{ entry.note }}</p><details v-if="entry.beforeSnapshot"><summary>查看订单修改前后记录</summary><pre>{{ entry.beforeSnapshot }}</pre><pre>{{ entry.afterSnapshot }}</pre></details></div></div></DetailDrawer
    >
    <a-modal v-model:open="createOpen" title="登记本地订单申请" :confirm-loading="creating" @ok="createRequest">
      <a-form layout="vertical"><a-form-item label="订单号"><a-input v-model:value="localForm.orderNumber" placeholder="例如 #1001" /></a-form-item>
        <a-form-item label="申请动作"><a-select v-model:value="localForm.actionType"><a-select-option value="CANCEL_ORDER">取消订单</a-select-option><a-select-option value="ADDRESS_CHANGE">修改收货地址</a-select-option></a-select></a-form-item>
        <a-form-item label="客户申请原因"><a-textarea v-model:value="localForm.reason" /></a-form-item>
        <template v-if="localForm.actionType === 'ADDRESS_CHANGE'"><a-form-item v-for="field in addressFields" :key="field.key" :label="field.label"><a-input v-model:value="localForm.newAddress[field.key]" /></a-form-item></template>
        <a-alert message="登记后先审批，再由有权限的人员执行；只修改本地订单。" type="info" />
      </a-form>
    </a-modal>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, reactive } from "vue";
import { message } from "ant-design-vue";
import { ReloadOutlined } from "@ant-design/icons-vue";
import api from "@/api";
import { useAuthStore } from "@/stores/auth";
import { getStoredTenantId } from "@/utils/tenant";
import MetricStrip from "@/components/admin/MetricStrip.vue";
import PageToolbar from "@/components/admin/PageToolbar.vue";
import DataTableShell from "@/components/admin/DataTableShell.vue";
import StatusTag from "@/components/admin/StatusTag.vue";
import DetailDrawer from "@/components/admin/DetailDrawer.vue";
import type { ActionRequest, TablePageChange } from "@/types/contracts";
import { httpErrorMessage } from "@/utils/httpError";
const auth = useAuthStore();
const canExecute = computed(() => auth.platformAdmin || (auth.claims?.tenantPermissions?.[String(getStoredTenantId())] || []).includes('action:execute'));
const executing = ref('');
const history = ref<{ id: number; eventType: string; state: string; actorId: number; createdAt: string; note?: string; beforeSnapshot?: string; afterSnapshot?: string }[]>([]);
const currentOrder = ref<{ orderStatus?: string; paymentStatus?: string } | null>(null);
const createOpen = ref(false), creating = ref(false);
const localForm = reactive({ orderNumber: '', actionType: 'CANCEL_ORDER', reason: '', newAddress: { recipient: '', phone: '', address: '', country: 'CN', state: '', zip: '' } as Record<string, string> });
const addressFields = [{ key: 'recipient', label: '收件人' }, { key: 'phone', label: '手机号' }, { key: 'address', label: '详细收货地址' }, { key: 'state', label: '省市' }, { key: 'zip', label: '六位邮编' }];
const rows = ref<ActionRequest[]>([]),
  loading = ref(false),
  error = ref(""),
  status = ref<string>(),
  total = ref(0),
  page = ref(1),
  size = ref(20),
  drawer = ref(false),
  current = ref<ActionRequest | null>(null);
const columns = [
  { title: "请求", key: "request", width: 210 },
  { title: "客户", dataIndex: "customerEmail", width: 190 },
  { title: "金额", key: "amount", width: 100 },
  { title: "风险原因", dataIndex: "riskReason", ellipsis: true },
  { title: "审批状态", key: "status", width: 110 },
  { title: "执行阶段", key: "execution", width: 250 },
  { title: "创建时间", key: "createdAt", width: 160 },
  { title: "操作", key: "actions", fixed: "right", width: 310 },
];
const metrics = computed(() => [
  {
    key: "pending",
    label: "待审批",
    value: rows.value.filter((x) =>
      ["1", "2", "PENDING_APPROVAL", "REQUESTED", "NEEDS_APPROVAL"].includes(String(x.status)),
    ).length,
    note: "当前页",
  },
  {
    key: "high",
    label: "高风险",
    value: rows.value.filter((x) => x.riskReason).length,
    note: "需人工复核",
  },
  {
    key: "approved",
    label: "已批准",
    value: rows.value.filter((x) =>
      ["3", "APPROVED_MANUAL"].includes(String(x.status)),
    ).length,
    note: "仍需执行或人工处理",
  },
  { key: "external", label: "支付退款", value: "未接入", note: "批准不代表到账" },
]);
const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
}));
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await api.get("/actions", {
      params: { status: status.value, page: page.value, size: size.value },
    });
    rows.value = r.data?.records || [];
    total.value = r.data?.total || 0;
  } catch (cause: unknown) {
    error.value = httpErrorMessage(cause, "加载失败");
  } finally {
    loading.value = false;
  }
}
function onTableChange(p: TablePageChange) {
  page.value = p.current || 1;
  size.value = p.pageSize || 20;
  void load();
}
async function open(v: ActionRequest) {
  current.value = v; drawer.value = true; history.value = []; currentOrder.value = null;
  try {
    const [audit, orders] = await Promise.all([api.get(`/actions/${v.source}/${v.id}/history`), api.get('/orders', { params: { keyword: v.externalOrderNumber, size: 100 } })]);
    if (current.value !== v) return;
    history.value = audit.data || [];
    currentOrder.value = (orders.data?.records || []).find((o: { externalOrderNumber: string }) => o.externalOrderNumber === v.externalOrderNumber) || null;
  } catch (cause) { message.error(httpErrorMessage(cause, '详情读取失败')); }
}
function eventLabel(type: string) { return type === 'EXECUTION' ? '执行' : type === 'APPROVED' ? '审批批准' : '审批拒绝'; }
async function execute(v: ActionRequest) {
  executing.value = `${v.source}:${v.id}`;
  try {
    const r = await api.post(`/actions/${v.source}/${v.id}/execute`);
    message.success(r.data?.note || '本地操作已执行'); await load();
  } catch (cause) { message.error(httpErrorMessage(cause, '执行失败')); await load(); }
  finally { executing.value = ''; }
}
async function createRequest() {
  creating.value = true;
  try { await api.post('/actions/local-requests', localForm); message.success('申请已登记，等待审批'); createOpen.value = false; await load(); }
  catch (cause) { message.error(httpErrorMessage(cause, '登记失败')); }
  finally { creating.value = false; }
}
function finished(v: ActionRequest) {
  return [
    "3",
    "4",
    "5",
    "APPROVED_MANUAL",
    "REJECTED",
    "EXECUTED",
    "FAILED",
  ].includes(String(v.status));
}
async function decide(v: ActionRequest, action: "approve" | "reject") {
  try { await api.post(`/actions/${v.source}/${v.id}/${action}`, {
    note: action === "approve" ? "人工批准，仅内部记录" : "人工拒绝",
  });
  message.success(action === "approve" ? "已批准内部请求" : "已拒绝");
  await load(); } catch (cause) { message.error(httpErrorMessage(cause, "审批失败")); }
}
function money(v: number | string | null | undefined, c?: string) {
  return v === null || v === undefined ? "—" : `${c || ""} ${v}`;
}
function date(v: string) {
  return v ? new Date(v).toLocaleString("zh-CN") : "—";
}
onMounted(load);
</script>
<style scoped>
.history-entry { border-bottom: 1px solid #eee; padding: 10px 0; }
.link-cell {
  height: auto;
  padding: 0;
  font-weight: 650;
}
.link-cell + small {
  display: block;
  color: #98a2b3;
}
.drawer-block {
  margin-top: 16px;
}
pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
