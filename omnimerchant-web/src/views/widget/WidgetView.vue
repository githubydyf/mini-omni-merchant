<template>
  <div class="widget-shell">
    <section class="widget">
      <header class="widget-header">
        <div class="widget-brand">
          <span class="widget-mark">OM</span>
          <div>
            <h1>{{ session?.storeName || '在线客服' }}</h1>
            <p>{{ session?.welcomeMessage || '请告诉我们需要帮助的问题。' }}</p>
          </div>
        </div>
        <a-tag :color="statusColor">{{ statusText }}</a-tag>
      </header>

      <div v-if="!connected" class="session-form">
        <div class="session-title"><h2>开始咨询</h2><p>订单查询时会核对下单邮箱。</p></div>
        <a-input v-model:value="tenantCode" size="large" placeholder="店铺编码" />
        <a-input v-model:value="customerEmail" size="large" placeholder="下单邮箱（可选）" />
        <a-input v-model:value="customerName" size="large" placeholder="你的称呼（可选）" />
        <a-button block type="primary" :loading="starting" @click="startSession">开始咨询</a-button>
      </div>

      <div v-else class="chat">
        <div ref="messagesEl" class="messages">
          <div v-for="msg in messages" :key="msg.messageUuid" class="msg" :class="msg.role">
            <small class="message-source">{{ sourceLabel(msg) }}</small>
            <div class="msg-body" v-html="render(msg.content)"></div>
          </div>
          <div v-if="streaming && streamText && !persistedAi && !humanMode && !closed" class="msg assistant">
            <small class="message-source">AI 客服</small>
            <div class="msg-body" v-html="render(streamText || '正在回复...')"></div>
          </div>
        </div>
        <div v-if="syncNotice" class="sync-notice">{{ syncNotice }}</div>
        <div class="composer">
          <a-input-search
            v-model:value="input"
            enter-button="发送"
            placeholder="请输入你的问题"
            :disabled="streaming || closed"
            :loading="streaming"
            @search="send()"
          />
          <a-button v-if="closed" block @click="resetSession">会话已关闭，开始新的咨询</a-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
import { message } from 'ant-design-vue'
import { consumeSse } from '@/utils/sse'
import { renderSafeMarkdown } from '@/utils/markdown'
import type { WidgetSession } from '@/types/contracts'

type WidgetMessage = { messageUuid: string; seqNo: number; role: string; source?: string; content: string; createdAt?: string }
const CACHE_KEY = 'omni_widget_session'
const tenantCode = ref(''), customerEmail = ref(''), customerName = ref('')
const session = ref<WidgetSession | null>(null)
const customerSessionToken = ref('')
const connected = ref(false), starting = ref(false), streaming = ref(false), closed = ref(false)
const input = ref(''), streamText = ref(''), syncNotice = ref('')
const persistedAi = ref(false), humanMode = ref(false)
let sentAfterSeq = 0, sendWasHuman = false, handoffDuringSend = false
const messages = ref<WidgetMessage[]>([])
const messagesEl = ref<HTMLElement>()
let cursor = 0, epoch = 0, disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
let queryAbort: AbortController | undefined, sendAbort: AbortController | undefined
let polling: Promise<void> | undefined
const statusText = computed(() => closed.value ? '会话已关闭' : streaming.value ? '回复中' : connected.value ? '已连接' : '未连接')
const statusColor = computed(() => closed.value ? 'default' : streaming.value ? 'gold' : connected.value ? 'green' : 'default')
function render(text: string) { return renderSafeMarkdown(text || '') }
function sourceLabel(msg: WidgetMessage) {
  if (msg.role === 'user') return '我'
  if (msg.source === 'HUMAN') return '人工客服'
  if (msg.source === 'AI') return 'AI 客服'
  return '客服（历史来源未登记）'
}
function stopSync() {
  if (timer) clearTimeout(timer)
  timer = undefined
  queryAbort?.abort()
}
function expired() { return !session.value?.expiresAt || Date.parse(session.value.expiresAt) <= Date.now() }
function scheduleSync() {
  if (disposed || !connected.value) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => { await syncMessages(); scheduleSync() }, 2500)
}
async function syncMessages() {
  if (!connected.value || !session.value) return
  if (expired()) { message.warning('会话已过期，请重新开始咨询'); resetSession(); return }
  if (polling) return polling
  const currentEpoch = epoch, uuid = session.value.conversationUuid, token = customerSessionToken.value
  polling = (async () => {
    try {
      let more = true
      while (more && currentEpoch === epoch && !disposed) {
        const controller = new AbortController()
        queryAbort = controller
        const deadline = setTimeout(() => controller.abort(), 12000)
        let resp: Response
        try {
          resp = await fetch(`/api/widget/messages?conversationUuid=${encodeURIComponent(uuid)}&afterSeqNo=${cursor}`, {
            headers: { Authorization: `Bearer ${token}` }, signal: controller.signal,
          })
        } finally { clearTimeout(deadline) }
        const body = await readJson(resp)
        if (currentEpoch !== epoch || disposed) return
        if (resp.status === 401 || resp.status === 403 || ['401', '403'].includes(body.code)) {
          message.warning(body.message || '会话授权已失效，请重新开始咨询'); resetSession(); return
        }
        if (!resp.ok || body.code !== '200') throw new Error(body.message || '消息同步失败')
        const received: WidgetMessage[] = body.data?.messages || []
        const ids = new Set(messages.value.map(m => m.messageUuid))
        for (const msg of received) {
          if (streaming.value && msg.source === 'AI' && msg.seqNo > sentAfterSeq) persistedAi.value = true
          if (!ids.has(msg.messageUuid)) { messages.value.push(msg); ids.add(msg.messageUuid) }
          cursor = Math.max(cursor, msg.seqNo)
        }
        messages.value.sort((a, b) => a.seqNo - b.seqNo)
        closed.value = [5, 6].includes(body.data?.conversationStatus)
        humanMode.value = [3, 4].includes(body.data?.conversationStatus)
        if (streaming.value && !sendWasHuman && (body.data?.conversationStatus === 4 || closed.value)) {
          handoffDuringSend = true
          sendAbort?.abort()
        }
        syncNotice.value = ''
        more = Boolean(body.data?.hasMore) && received.length > 0
      }
      await nextTick(); scrollToBottom()
    } catch (error: unknown) {
      if (currentEpoch === epoch && !disposed) syncNotice.value = '消息同步暂时中断，连接恢复后将自动补齐'
    } finally { queryAbort = undefined }
  })().finally(() => { polling = undefined })
  return polling
}
async function startSession() {
  if (!tenantCode.value.trim()) { message.warning('请先填写店铺编码'); return }
  starting.value = true
  resetSession()
  try {
    const resp = await fetch('/api/widget/session', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantCode: tenantCode.value.trim(), customerEmail: customerEmail.value.trim(), customerName: customerName.value.trim(), language: 'zh' }),
    })
    const body = await readJson(resp)
    if (!resp.ok || body.code !== '200') throw new Error(body.message || '无法创建会话')
    if (!body.data?.customerSessionToken) throw new Error('服务未返回客户会话令牌，请刷新后重试')
    if (disposed) return
    session.value = body.data
    customerSessionToken.value = body.data.customerSessionToken
    connected.value = true
    sessionStorage.setItem(CACHE_KEY, JSON.stringify(body.data))
    await syncMessages(); scheduleSync()
  } catch (error: unknown) { message.error(widgetError(error, '无法创建会话')) }
  finally { starting.value = false }
}
async function send(text?: string) {
  const userText = (text || input.value).trim()
  if (!userText || streaming.value || closed.value || !session.value) return
  if (expired()) { message.warning('会话已过期，请重新开始咨询'); resetSession(); return }
  streaming.value = true
  if (polling) await polling
  if (!session.value) { streaming.value = false; return }
  const currentEpoch = epoch, active = session.value
  sentAfterSeq = cursor; persistedAi.value = false; sendWasHuman = humanMode.value; handoffDuringSend = false
  input.value = ''; streamText.value = ''
  const controller = new AbortController(); sendAbort = controller
  try {
    const resp = await fetch('/api/widget/chat/stream', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${customerSessionToken.value}` },
      body: JSON.stringify({ tenantCode: active.tenantCode, conversationUuid: active.conversationUuid, message: userText }),
      signal: controller.signal,
    })
    if (!resp.ok || !resp.headers.get('content-type')?.includes('text/event-stream')) {
      const body = await readJson(resp)
      if (resp.status === 401) resetSession()
      throw new Error(body.message || '发送失败')
    }
    // 用户气泡只来自真实消息 UUID；查询与流式可以并行，正式回复出现后隐藏临时气泡。
    await syncMessages()
    let streamError = ''
    await consumeSse(resp, ({ event, data }) => {
      if (currentEpoch !== epoch || disposed) return
      if (event === 'error') { streamError = data || '服务暂时不可用'; return }
      if (event === 'final') streamText.value = data
      else if (event === 'translated_delta' || event === 'message') streamText.value += data
    })
    if (streamError) message.error(streamError)
  } catch (error: unknown) {
    if (currentEpoch === epoch && !disposed && !handoffDuringSend) message.error(widgetError(error, '发送失败，连接恢复后可查询已保存消息'))
  } finally {
    if (currentEpoch === epoch) {
      streaming.value = false; streamText.value = ''
      await syncMessages(); scheduleSync()
    }
    if (sendAbort === controller) sendAbort = undefined
  }
}
function resetSession() {
  epoch++; stopSync(); sendAbort?.abort()
  connected.value = false; session.value = null; customerSessionToken.value = ''
  messages.value = []; cursor = 0; closed.value = false; streaming.value = false
  streamText.value = ''; syncNotice.value = ''; humanMode.value = false; persistedAi.value = false
  sessionStorage.removeItem(CACHE_KEY)
}
function scrollToBottom() { messagesEl.value?.scrollTo({ top: messagesEl.value.scrollHeight, behavior: 'smooth' }) }
async function readJson(resp: Response) { try { return await resp.json() } catch { return {} } }
function widgetError(error: unknown, fallback: string) {
  const text = error instanceof Error ? error.message : ''
  if (/Failed to fetch|NetworkError|Load failed|aborted/i.test(text)) return '连接服务失败，恢复后会自动同步已保存消息，请勿重复发送'
  return text || fallback
}
onMounted(async () => {
  try {
    const cached = JSON.parse(sessionStorage.getItem(CACHE_KEY) || 'null') as WidgetSession | null
    if (cached?.customerSessionToken && Date.parse(cached.expiresAt || '') > Date.now()) {
      session.value = cached; tenantCode.value = cached.tenantCode
      customerSessionToken.value = cached.customerSessionToken; connected.value = true
      await syncMessages(); scheduleSync()
    } else sessionStorage.removeItem(CACHE_KEY)
  } catch { sessionStorage.removeItem(CACHE_KEY) }
})
onBeforeUnmount(() => { disposed = true; epoch++; stopSync(); sendAbort?.abort() })
</script>

<style scoped>
.message-source { color: #667085; font-size: 11px; }
.sync-notice { padding: 6px 12px; color: #b54708; font-size: 12px; }
.widget-shell {
  background: #f2f5f9;
  display: grid;
  min-height: 100vh;
  padding: 24px;
  place-items: center;
}

.widget {
  background: #fff;
  border: 1px solid #dfe5ed;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  height: min(720px, calc(100vh - 48px));
  overflow: hidden;
  width: min(440px, 100%);
  box-shadow: 0 12px 32px rgba(16, 24, 40, 0.08);
}

.widget-header {
  align-items: center;
  border-bottom: 1px solid #eef0f3;
  display: flex;
  gap: 12px;
  justify-content: space-between;
  padding: 16px 18px;
}

.widget-brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
.widget-mark { width: 34px; height: 34px; flex: 0 0 34px; display: grid; place-items: center; border-radius: 7px; background: #1677ff; color: #fff; font-size: 11px; font-weight: 800; }

.widget-header h1 {
  color: #1f2937;
  font-size: 15px;
  margin: 0;
}

.widget-header p {
  color: #667085;
  max-width: 270px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  margin: 3px 0 0;
}

.session-form {
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 26px 22px;
}

.session-title { margin-bottom: 4px; }
.session-title h2 { margin: 0; color: #111827; font-size: 18px; }
.session-title p { margin: 5px 0 0; color: #667085; font-size: 12px; }

.chat {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding: 18px 16px;
}

.msg {
  background: #f4f6f8;
  border-radius: 8px;
  color: #1f2937;
  line-height: 1.6;
  max-width: 82%;
  padding: 10px 12px;
}

.msg.user {
  align-self: flex-end;
  background: #1677ff;
  color: #fff;
}

.msg.assistant {
  align-self: flex-start;
}

.composer {
  border-top: 1px solid #eef0f3;
  padding: 12px 14px 14px;
}

@media (max-width: 640px) {
  .widget-shell {
    padding: 0;
  }

  .widget {
    border-radius: 0;
    height: 100vh;
  }

  .widget-header {
    flex-direction: column;
  }
}
</style>
