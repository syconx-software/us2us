export interface Frame {
  type: string
  id?: string
  serverTimestamp?: number
  envelope?: string
}

/**
 * A WebSocket client on Node's built-in WebSocket (undici), which accepts
 * request headers as a non-standard constructor option. Collects every
 * frame so tests can wait for them.
 */
export class TestSocket {
  readonly frames: Frame[] = []
  readonly closed: Promise<{ code: number, reason: string }>
  private waiters: Array<() => void> = []

  private constructor(private readonly socket: WebSocket) {
    this.closed = new Promise((resolve) => {
      socket.addEventListener('close', event => resolve({ code: event.code, reason: event.reason }))
    })
    socket.addEventListener('message', (event) => {
      this.frames.push(JSON.parse(String(event.data)) as Frame)
      for (const wake of this.waiters.splice(0)) wake()
    })
  }

  static open(url: string, token: string): Promise<TestSocket> {
    const Ctor = WebSocket as unknown as new (url: string, options: { headers: Record<string, string> }) => WebSocket
    const socket = new Ctor(url, { headers: { 'x-auth-token': token } })
    const client = new TestSocket(socket)
    return new Promise((resolve, reject) => {
      socket.addEventListener('open', () => resolve(client), { once: true })
      socket.addEventListener('error', () => reject(new Error('websocket failed to open')), { once: true })
    })
  }

  /** Opens a socket and waits until the backlog has been delivered. */
  static async synced(url: string, token: string): Promise<TestSocket> {
    const client = await TestSocket.open(url, token)
    await client.waitFor(frame => frame.type === 'queue_empty')
    return client
  }

  get messages(): Frame[] {
    return this.frames.filter(frame => frame.type === 'message')
  }

  send(frame: unknown): void {
    this.socket.send(JSON.stringify(frame))
  }

  sendRaw(text: string): void {
    this.socket.send(text)
  }

  ack(ids: string[]): void {
    this.send({ type: 'ack', ids })
  }

  /** Resolves with the first frame (already received or future) that matches. */
  async waitFor(match: (frame: Frame) => boolean, timeoutMs = 10_000): Promise<Frame> {
    await this.until(() => this.frames.some(match), timeoutMs)
    return this.frames.find(match)!
  }

  async waitForMessages(count: number, timeoutMs = 10_000): Promise<Frame[]> {
    await this.until(() => this.messages.length >= count, timeoutMs)
    return this.messages
  }

  private async until(condition: () => boolean, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs
    while (!condition()) {
      const remaining = deadline - Date.now()
      if (remaining <= 0) throw new Error(`timed out; frames so far: ${JSON.stringify(this.frames)}`)
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, remaining)
        this.waiters.push(() => {
          clearTimeout(timer)
          resolve()
        })
      })
    }
  }

  close(): Promise<{ code: number, reason: string }> {
    this.socket.close()
    return this.closed
  }
}
