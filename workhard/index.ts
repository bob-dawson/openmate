import type { Plugin, PluginOptions, Hooks } from "@opencode-ai/plugin"
import { parseConfig } from "./src/config.js"
import { createMonitor } from "./src/monitor.js"
import { log } from "./src/log.js"

const WorkHardPlugin: Plugin = async (input, options?: PluginOptions) => {
  const config = parseConfig(options as Record<string, unknown> | undefined)
  const monitor = createMonitor(input, config)

  log("init", `endKeyword="${config.endKeyword}" strategy=${config.permissionStrategy}`)

  return {
    event: async (input) => {
      try {
        monitor.handleEvent(input)
      } catch (err) {
        log("error", "event handler failed", err)
      }
    },
  } satisfies Hooks
}

export default WorkHardPlugin
