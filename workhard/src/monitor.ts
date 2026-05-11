import type { WorkHardConfig } from "./config.js"
import { buildContinuePrompt, shouldAllowPermission } from "./config.js"
import { log } from "./log.js"
import type { PluginInput, Hooks } from "@opencode-ai/plugin"

interface BusEvent {
  id: string
  type: string
  properties: Record<string, any>
}

interface SessionState {
  finished: boolean
  idleRetries: number
  idleInProgress: boolean
}

export function createMonitor(input: PluginInput, config: WorkHardConfig) {
  const sessions = new Map<string, SessionState>()
  const serverUrl = input.serverUrl
  const headers: Record<string, string> = { "Content-Type": "application/json" }
  const directory = input.directory

  function getSession(sessionID: string): SessionState {
    let s = sessions.get(sessionID)
    if (!s) {
      s = { finished: false, idleRetries: 0, idleInProgress: false }
      sessions.set(sessionID, s)
    }
    return s
  }

  async function apiFetch(path: string, options?: RequestInit) {
    const base = serverUrl.toString().replace(/\/$/, "")
    const fullUrl = new URL(base + path)
    if (directory) fullUrl.searchParams.set("directory", directory)
    try {
      const resp = await fetch(fullUrl.toString(), { ...options, headers: { ...headers, ...(options?.headers ?? {}) } })
      return resp
    } catch (err) {
      log("api", `fetch error: ${path}`, err)
      return null
    }
  }

  async function handlePermissionAsked(event: BusEvent) {
    const p = event.properties
    const sessionID = p.sessionID as string
    const requestID = p.id as string
    const permission = p.permission as string

    log("permission", `${permission} (${(p.patterns as string[])?.join(", ")}) session=${sessionID}`)

    if (shouldAllowPermission(config, permission)) {
      log("permission", `→ allow`)
      await apiFetch(`/permission/${requestID}/reply`, {
        method: "POST",
        body: JSON.stringify({ reply: "always" }),
      })
    } else {
      log("permission", `→ deny + feedback`)
      await apiFetch(`/permission/${requestID}/reply`, {
        method: "POST",
        body: JSON.stringify({ reply: "reject", message: config.denyPrompt }),
      })
    }
  }

  async function handleQuestionAsked(event: BusEvent) {
    const p = event.properties
    const requestID = p.id as string
    const sessionID = p.sessionID as string
    const questions = (p.questions as Array<{ header: string; options: Array<{ label: string }>; custom?: boolean }>) ?? []

    log("question", `session=${sessionID} questions=${questions.length}`)

    if (questions.length === 0) {
      await apiFetch(`/question/${requestID}/reply`, {
        method: "POST",
        body: JSON.stringify({ answers: [[config.questionReply]] }),
      })
      return
    }

    const answers = questions.map((q) => {
      if (q.custom !== false && q.options.length === 0) {
        return [config.questionReply]
      }
      return [q.options[0]?.label ?? config.questionReply]
    })

    log("question", `→ reply`, answers)
    await apiFetch(`/question/${requestID}/reply`, {
      method: "POST",
      body: JSON.stringify({ answers }),
    })
  }

  async function handleSessionIdle(sessionID: string) {
    const state = getSession(sessionID)
    if (state.finished) return
    if (state.idleInProgress) return

    state.idleInProgress = true
    try {
      const lastText = await getLastAssistantText(sessionID)
      if (lastText && lastText.includes(config.endKeyword)) {
        state.finished = true
        log("monitor", `✓ end keyword "${config.endKeyword}" detected, stopping`, `session=${sessionID}`)
        return
      }

      state.idleRetries++
      if (state.idleRetries > config.maxIdleRetries) {
        log("monitor", `max idle retries (${config.maxIdleRetries}) exceeded, stopping`)
        state.finished = true
        return
      }

      log("monitor", `idle #${state.idleRetries}, sending continue prompt`)
      await delay(config.idleDelayMs)

      if (state.finished) return

      const prompt = buildContinuePrompt(config)
      await sendPrompt(sessionID, prompt)
    } catch (err) {
      log("monitor", "idle handler error", err)
    } finally {
      state.idleInProgress = false
    }
  }

  async function getLastAssistantText(sessionID: string): Promise<string | null> {
    const resp = await apiFetch(`/session/${sessionID}/message?limit=5`)
    if (!resp || !resp.ok) return null
    try {
      const messages = (await resp.json()) as Array<{
        role: string
        parts?: Array<{ type: string; text?: string; content?: string }>
      }>
      for (let i = messages.length - 1; i >= 0; i--) {
        const msg = messages[i]
        if (msg.role !== "assistant") continue
        const texts: string[] = []
        for (const part of msg.parts ?? []) {
          if (part.type === "text" && (part.text || part.content)) {
            texts.push(part.text ?? part.content ?? "")
          }
        }
        return texts.join("\n")
      }
    } catch {
      return null
    }
    return null
  }

  async function sendPrompt(sessionID: string, text: string) {
    log("prompt", `→ session=${sessionID}: ${text.slice(0, 80)}...`)
    await apiFetch(`/session/${sessionID}/prompt_async`, {
      method: "POST",
      body: JSON.stringify({
        parts: [{ type: "text", text }],
      }),
    })
  }

  function handleEvent(raw: { event: unknown }) {
    const event = raw.event as BusEvent
    if (!event || !event.type) return

    switch (event.type) {
      case "permission.asked": {
        void handlePermissionAsked(event)
        break
      }
      case "question.asked": {
        void handleQuestionAsked(event)
        break
      }
      case "session.idle": {
        const sessionID = (event.properties as any).sessionID as string
        if (sessionID) void handleSessionIdle(sessionID)
        break
      }
      case "session.status": {
        const props = event.properties as { sessionID: string; status: { type: string } }
        if (props.status?.type === "idle" && props.sessionID) {
          void handleSessionIdle(props.sessionID)
        }
        break
      }
      case "session.error": {
        const props = event.properties as { sessionID?: string; error?: any }
        log("error", `session=${props.sessionID}`, props.error?.message ?? props.error)
        break
      }
    }
  }

  return { handleEvent }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
