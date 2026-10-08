import { describe, expect, it } from 'vitest'
import { consumeChatStream, mapBackendMessages } from './chatStream'

function streamOf(chunks: string[]): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
  return new Response(stream)
}

describe('consumeChatStream', () => {
  it('累积增量并在 final 时以 final 为准，不重复拼接', async () => {
    const deltas: string[] = []
    const result = await consumeChatStream(
      streamOf([
        'event: status\ndata: PROCESSING\n\n',
        'event: translated_delta\ndata: 七天\n\n',
        'event: translated_delta\ndata: 无理由退货\n\n',
        'event: final\ndata: 七天无理由退货。\n\n',
      ]),
      { onDelta: (d) => deltas.push(d) },
    )

    expect(deltas.join('')).toBe('七天无理由退货')
    // final 是权威回答，不与增量拼接
    expect(result.text).toBe('七天无理由退货。')
    expect(result.receivedFinal).toBe(true)
    expect(result.error).toBeNull()
  })

  it('处理分片边界与多行 data', async () => {
    const result = await consumeChatStream(
      streamOf([
        'event: translated_del',
        'ta\ndata: 第一行\ndata: 第二行\n\nevent: final\ndata: 完成\n\n',
      ]),
    )
    expect(result.text).toBe('完成')
  })

  it('收到 error 时不返回半截增量作为成功结果', async () => {
    const result = await consumeChatStream(
      streamOf([
        'event: translated_delta\ndata: 部分内容\n\n',
        'event: error\ndata: 本次请求暂时无法处理\n\n',
      ]),
    )
    expect(result.error).toBe('本次请求暂时无法处理')
    expect(result.receivedFinal).toBe(false)
  })

  it('未收到 final 即断开时标记为未完成', async () => {
    const result = await consumeChatStream(
      streamOf(['event: translated_delta\ndata: 半截\n\n']),
    )
    expect(result.receivedFinal).toBe(false)
    expect(result.error).toBeNull()
  })
})

describe('mapBackendMessages', () => {
  it('只保留 user/assistant 并按原顺序转换 content→text', () => {
    const ui = mapBackendMessages([
      { role: 'user', content: '你好' },
      { role: 'tool', content: '工具结果' },
      { role: 'assistant', content: '您好' },
      { role: 'system', content: '系统' },
    ])
    expect(ui).toEqual([
      { role: 'user', text: '你好' },
      { role: 'assistant', text: '您好' },
    ])
  })

  it('过滤空内容并容忍 null', () => {
    expect(mapBackendMessages(null)).toEqual([])
    expect(mapBackendMessages([{ role: 'user', content: '   ' }])).toEqual([])
  })
})
