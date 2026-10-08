import { consumeSse } from './sse'

/** 后端 SSE 事件名（与 ChatStreamEvent 保持一致，不得改名）。 */
export const SSE_STATUS = 'status'
export const SSE_DELTA = 'translated_delta'
export const SSE_FINAL = 'final'
export const SSE_ERROR = 'error'

export interface ChatStreamHandlers {
  /** 收到状态事件（例如 PROCESSING）。 */
  onStatus?: (data: string) => void
  /** 收到内容增量（用于实时展示）。 */
  onDelta?: (delta: string) => void
  /** 收到最终权威回答（整个流只应出现一次）。 */
  onFinal?: (finalText: string) => void
}

export interface ChatStreamResult {
  /** 最终展示文本：以 final 为准；无 final 时回退为累计增量。 */
  text: string
  /** 是否收到过 final。 */
  receivedFinal: boolean
  /** 服务端是否返回 error 事件。 */
  error: string | null
}

/**
 * 消费一次 Chat SSE 流。
 *
 * <p>关键规则（对应任务六）：
 * <ul>
 *   <li>{@code final} 是权威回答：收到后用它覆盖增量累计，避免"增量 + final 重复拼接"；</li>
 *   <li>未收到 final 就断开：视为失败，不把半截增量当成已完成回复；</li>
 *   <li>收到 {@code error}：视为失败，不返回 partial 文本作为成功结果。</li>
 * </ul>
 */
export async function consumeChatStream(
  response: Response,
  handlers: ChatStreamHandlers = {},
): Promise<ChatStreamResult> {
  let deltaText = ''
  let finalText: string | null = null
  let receivedFinal = false
  let error: string | null = null

  await consumeSse(response, ({ event, data }) => {
    if (event === SSE_STATUS || event === 'done' || data === '[DONE]') {
      if (event === SSE_STATUS) handlers.onStatus?.(data)
      return
    }
    if (event === SSE_ERROR) {
      error = data || '未知错误'
      return
    }
    if (event === SSE_FINAL) {
      receivedFinal = true
      finalText = data
      handlers.onFinal?.(data)
      return
    }
    if (event === SSE_DELTA || event === 'message') {
      deltaText += data
      handlers.onDelta?.(data)
    }
  })

  // final 优先；异常时不回退增量，避免把失败的部分回复当作完成
  if (error) {
    return { text: finalText ?? '', receivedFinal, error }
  }
  if (receivedFinal) {
    return { text: finalText ?? '', receivedFinal: true, error: null }
  }
  return { text: deltaText, receivedFinal: false, error: null }
}

/** 后端 chat_message 记录 → 前端可展示消息。 */
export interface BackendMessage {
  role?: string
  content?: string
}

export interface UiMessage {
  role: string
  text: string
}

/**
 * 把持久化的 chat_message 列表转换成前端消息。
 *
 * <p>只保留 user / assistant 两种角色；其余（system / tool）不在对话视图中展示。
 * 过滤空内容，避免出现空气泡。
 */
export function mapBackendMessages(messages: BackendMessage[] | null | undefined): UiMessage[] {
  if (!messages) return []
  return messages
    .filter((m) => m && (m.role === 'user' || m.role === 'assistant'))
    .filter((m) => typeof m.content === 'string' && m.content.trim().length > 0)
    .map((m) => ({ role: m.role as string, text: m.content as string }))
}
