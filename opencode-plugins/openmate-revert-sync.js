import { Plugin } from "@opencode-ai/plugin"
import path from "node:path"
import os from "node:os"

let DatabaseSync = null
try {
  const sqlite = await import("node:sqlite")
  DatabaseSync = sqlite.DatabaseSync
} catch (_) {}

export default Plugin.define({
  id: "openmate.revert-sync",
  setup: async (ctx) => {
    console.log("[openmate-revert-sync] setup starting")

    if (!DatabaseSync) {
      console.error("[openmate-revert-sync] node:sqlite not available")
      return
    }

    const home = os.homedir()
    const dbPath = path.join(home, ".openmate", "bridge.db")
    const db = new DatabaseSync(dbPath)
    db.exec(`
      CREATE TABLE IF NOT EXISTS revert_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        event_type TEXT NOT NULL,
        message_id TEXT,
        timestamp INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_revert_log_session_ts
        ON revert_log(session_id, timestamp);
    `)
    const insertStmt = db.prepare(
      "INSERT INTO revert_log (session_id, event_type, message_id, timestamp) VALUES (?, ?, ?, ?)"
    )
    console.log("[openmate-revert-sync] connected to", dbPath)

    const iterable = ctx.event.subscribe()
    const iterator = iterable[Symbol.asyncIterator]()
    const loop = (async () => {
      while (true) {
        const { value: event, done } = await iterator.next()
        if (done) break
        if (
          event.type !== "session.revert.staged" &&
          event.type !== "session.revert.cleared" &&
          event.type !== "session.revert.committed"
        ) continue
        try {
          const eventType = event.type.replace("session.revert.", "")
          const props = event.properties || {}
          const sessionId = props.sessionID || ""
          let messageId = null
          if (event.type === "session.revert.staged") {
            messageId = props.revert?.messageID || null
          } else if (event.type === "session.revert.committed") {
            messageId = props.to || null
          }
          insertStmt.run(sessionId, eventType, messageId, Date.now())
          console.log("[openmate-revert-sync] wrote revert:", eventType, sessionId, messageId)
        } catch (e) {
          console.error("[openmate-revert-sync] insert failed:", e.message)
        }
      }
    })()

    return async () => {
      iterator.return?.()
      await loop.catch(() => {})
      db.close()
      console.log("[openmate-revert-sync] cleaned up")
    }
  },
})
