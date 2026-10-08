<template>
  <div class="chat-layout">
    <aside class="chat-sidebar">
      <div class="brand" @click="router.push('/admin')">
        <h2>OmniMerchant</h2>
        <span class="version">智能客服测试台</span>
      </div>
      <div class="new-chat-btn">
        <a-button block type="primary" :loading="creating" @click="startNewChat">
          <template #icon><PlusOutlined /></template>
          新对话
        </a-button>
      </div>
      <div class="conversation-list">
        <button
          v-for="conversation in conversations"
          :key="conversation.uuid"
          class="conv-item"
          :class="{ active: conversation.uuid === currentConvId }"
          type="button"
          @click="switchConversation(conversation.uuid)"
        >
          <span class="conv-title">{{ conversation.title || '新对话' }}</span>
          <span class="conv-time">{{ conversation.time }}</span>
        </button>
        <a-empty v-if="!conversations.length" description="暂无对话" />
      </div>
      <div class="sidebar-footer">
        <a-button type="link" @click="router.push('/admin')">
          <template #icon><SettingOutlined /></template>
          管理后台
        </a-button>
      </div>
    </aside>

    <main class="chat-main">
      <header class="chat-header">
        <div class="header-controls">
          <a-input-number
            v-model:value="tenantId"
            :min="1"
            :controls="false"
            style="width: 120px"
            placeholder="租户ID"
            @change="onTenantChange"
          />
          <span class="tenant-hint">开发测试租户（如 1001）</span>
          <a-select
            v-model:value="selectedIntent"
            style="width: 200px"
            :options="intentOptions"
          />
        </div>
        <a-tag :color="streaming ? 'gold' : 'green'">{{ streaming ? '回复中' : '就绪' }}</a-tag>
      </header>

      <div ref="msgContainer" class="messages-container">
        <div v-if="messages.length === 0 && !streaming" class="welcome">
          <h3>智能客服对话测试</h3>
          <p>这里会调用真实后台链路：保存消息、按所选意图编排 Agent、调用订单/物流/商品/政策工具，并流式返回回答。</p>
          <div class="examples">
            <button type="button" @click="sendMessage('退货期限是多少天？')">退货期限是多少天？</button>
            <button type="button" @click="sendMessage('查询订单 #1001 的状态。')">查询订单 #1001 的状态</button>
            <button type="button" @click="sendMessage('推荐100美元以内的商品。')">推荐100美元以内的商品</button>
          </div>
        </div>

        <MessageBubble
          v-for="(msg, index) in messages"
          :key="index"
          :role="msg.role"
          :text="msg.text"
          :tool-calls="msg.toolCalls"
        />
        <MessageBubble v-if="streaming" role="assistant" :text="streamText" />
        <div ref="scrollAnchor"></div>
      </div>

      <footer class="chat-input">
        <a-input-search
          v-model:value="inputText"
          enter-button="发送"
          placeholder="输入消息测试智能客服（中文）"
          size="large"
          :disabled="!tenantId || !currentConvId || streaming"
          :loading="streaming"
          @search="sendMessage()"
        />
      </footer>
    </main>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, SettingOutlined } from '@ant-design/icons-vue'
import api from '@/api'
import MessageBubble from '@/components/MessageBubble.vue'
import { getStoredTenantId, setStoredTenantId } from '@/utils/tenant'
import { consumeChatStream, mapBackendMessages } from '@/utils/chatStream'
import { httpErrorMessage } from '@/utils/httpError'
import type { ChatMessage } from '@/types/contracts'

const router = useRouter()

/**
 * 显式意图选择：本阶段不做自动 Triage，测试台必须显式指定意图，
 * 否则后端会落到 triage（无可用工具）而无法路由。
 */
const INTENTS = [
  'ORDER_STATUS',
  'LOGISTICS',
  'PRODUCT_ADVICE',
  'RETURN_REFUND',
  'CANCEL_ORDER',
  'ADDRESS_CHANGE',
  'POLICY_QA',
  'COMPLAINT',
  'HUMAN_REQUEST',
]
const intentOptions = INTENTS.map((value) => ({ value, label: value }))

const tenantId = ref<number | null>(getStoredTenantId() ?? 1001)
const selectedIntent = ref('POLICY_QA')
const conversations = ref<{ uuid: string; title: string; time: string }[]>([])
const currentConvId = ref('')
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const streaming = ref(false)
const creating = ref(false)
const streamText = ref('')
const scrollAnchor = ref<HTMLElement>()

function onTenantChange() {
  setStoredTenantId(tenantId.value)
  currentConvId.value = ''
  messages.value = []
  conversations.value = []
  void loadConversations()
}

/** 真实创建会话：由后端生成 UUID 并落库（不再前端本地生成）。 */
async function startNewChat() {
  if (!tenantId.value) {
    message.warning('请先填写租户 ID')
    return
  }
  creating.value = true
  try {
    const res = await api.post('/conversations', { tenantId: tenantId.value, channel: 'WEB' })
    const vo = res.data
    currentConvId.value = vo.conversationUuid
    messages.value = []
    streamText.value = ''
    conversations.value.unshift({
      uuid: vo.conversationUuid,
      title: '新对话',
      time: new Date().toLocaleTimeString('zh-CN'),
    })
  } catch (error: unknown) {
    message.error(`创建会话失败：${httpErrorMessage(error, '网络错误')}`)
  } finally {
    creating.value = false
  }
}

/** 切换会话：从数据库重新加载历史消息。 */
async function switchConversation(uuid: string) {
  currentConvId.value = uuid
  streamText.value = ''
  messages.value = []
  try {
    const res = await api.get(`/conversations/${uuid}/messages`)
    messages.value = mapBackendMessages(res.data)
  } catch (error: unknown) {
    message.error(`加载历史消息失败：${httpErrorMessage(error, '网络错误')}`)
  }
  await nextTick()
  scrollToBottom()
}

/** 会话列表：复用真实 /api/conversations 结果（不再依赖未实现的 /api/tenants）。 */
async function loadConversations() {
  if (!tenantId.value) return
  try {
    const res = await api.get('/conversations', {
      params: { tenantId: tenantId.value, page: 1, size: 50 },
    })
    const records = res.data?.records || []
    conversations.value = records.map((c: Record<string, unknown>) => ({
      uuid: String(c.conversationUuid),
      title: c.intentPrimary ? String(c.intentPrimary) : (c.customerName ? String(c.customerName) : '会话'),
      time: String(c.lastMessageAt || c.startedAt || '').replace('T', ' ').slice(5, 16),
    }))
  } catch (error: unknown) {
    message.error(`加载会话列表失败：${httpErrorMessage(error, '网络错误')}`)
  }
}

async function sendMessage(text?: string) {
  const userText = (text || inputText.value).trim()
  if (!userText || streaming.value) return
  if (!currentConvId.value) {
    await startNewChat()
    if (!currentConvId.value) return
  }

  messages.value.push({ role: 'user', text: userText })
  inputText.value = ''
  streaming.value = true
  streamText.value = ''

  await nextTick()
  scrollToBottom()

  try {
    // EventSource 不支持 POST + JSON，这里必须用 fetch + ReadableStream
    const resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': String(tenantId.value),
      },
      body: JSON.stringify({
        conversationUuid: currentConvId.value,
        message: userText,
        intent: selectedIntent.value,
      }),
    })

    if (!resp.ok) {
      // 校验失败 / 会话不存在 / 状态不允许等：后端返回 JSON 业务错误
      throw new Error((await readErrorDetail(resp)) || `HTTP ${resp.status}`)
    }
    if (!resp.body) {
      throw new Error('响应流为空')
    }

    const result = await consumeChatStream(resp, {
      onDelta: (delta) => {
        streamText.value += delta
        void scrollToBottom()
      },
      onFinal: (finalText) => {
        // final 为权威回答：直接覆盖增量累计，避免 "增量 + final" 重复拼接
        streamText.value = finalText
      },
    })

    if (result.error) {
      throw new Error(result.error)
    }
    if (!result.receivedFinal) {
      throw new Error('连接意外中断，未收到完整回复')
    }

    messages.value.push({ role: 'assistant', text: result.text })
    streamText.value = ''
    await nextTick()
    scrollToBottom()

    const conversation = conversations.value.find((item) => item.uuid === currentConvId.value)
    if (conversation && conversation.title === '新对话') {
      conversation.title = userText.slice(0, 30) + (userText.length > 30 ? '...' : '')
    }
  } catch (error: unknown) {
    // 失败：不把半截增量写成已完成回复；从数据库重新加载，保持与持久化一致
    message.error(`智能客服请求失败：${httpErrorMessage(error, '网络错误')}`)
    streamText.value = ''
    await switchConversation(currentConvId.value)
  } finally {
    streaming.value = false
  }
}

async function readErrorDetail(resp: Response): Promise<string> {
  try {
    const body = await resp.json()
    return body?.message || ''
  } catch {
    return ''
  }
}

function scrollToBottom() {
  scrollAnchor.value?.scrollIntoView({ behavior: 'smooth' })
}

onMounted(async () => {
  setStoredTenantId(tenantId.value)
  await loadConversations()
})
</script>

<style scoped>
.chat-layout {
  background: #f3f6fb;
  display: flex;
  height: 100vh;
}

.chat-sidebar {
  background: #fff;
  border-right: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 280px;
}

.brand {
  border-bottom: 1px solid #eef0f3;
  cursor: pointer;
  padding: 20px;
}

.brand h2 {
  color: #1677ff;
  font-size: 18px;
  margin: 0;
}

.version {
  color: #8c8c8c;
  font-size: 12px;
}

.new-chat-btn {
  padding: 12px 16px;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px;
}

.conv-item {
  background: transparent;
  border: 0;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
  padding: 10px 12px;
  text-align: left;
  width: 100%;
}

.conv-item:hover,
.conv-item.active {
  background: #f0f5ff;
}

.conv-title {
  color: #1f2937;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  color: #9ca3af;
  font-size: 11px;
}

.sidebar-footer {
  border-top: 1px solid #eef0f3;
  display: flex;
  justify-content: space-between;
  padding: 12px;
}

.chat-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.chat-header {
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  justify-content: space-between;
  padding: 10px 20px;
}

.header-controls {
  align-items: center;
  display: flex;
  gap: 10px;
}

.tenant-hint {
  color: #94a3b8;
  font-size: 12px;
}

.messages-container {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
  padding: 20px;
}

.welcome {
  color: #6b7280;
  margin: auto;
  max-width: 760px;
  text-align: center;
}

.welcome h3 {
  color: #1f2937;
  font-size: 24px;
  margin-bottom: 8px;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  margin-top: 18px;
}

.examples button {
  background: #fff;
  border: 1px solid #d9e4f5;
  border-radius: 18px;
  color: #1f4d8f;
  cursor: pointer;
  font-size: 13px;
  padding: 8px 14px;
}

.examples button:hover {
  background: #f0f6ff;
  border-color: #9ec3ff;
}

.chat-input {
  background: #fff;
  border-top: 1px solid #e5e7eb;
  flex-shrink: 0;
  padding: 16px 20px;
}

@media (max-width: 860px) {
  .chat-sidebar {
    display: none;
  }

  .chat-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
    height: auto;
    padding: 12px;
  }
}
</style>
