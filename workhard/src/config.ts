export interface WorkHardConfig {
  endKeyword: string
  autoAnswerQuestion: boolean
  questionReply: string
  autoApprovePermission: boolean
  permissionStrategy: "allow-all" | "allow-safe" | "deny-all"
  safePermissions: string[]
  denyPrompt: string
  continuePrompt: string | null
  maxIdleRetries: number
  idleDelayMs: number
  autoContinue: boolean
  retryOnStepFailed: boolean
  retryPatterns: string[]
  retryDelayMs: number
}

const DEFAULT_CONFIG: WorkHardConfig = {
  endKeyword: "DONE",
  autoAnswerQuestion: false,
  questionReply: "按你推荐的进行",
  autoApprovePermission: false,
  permissionStrategy: "allow-all",
  safePermissions: [],
  denyPrompt: "我不能授予你相关权限，请使用其他方法完成任务",
  continuePrompt: null,
  maxIdleRetries: 50,
  idleDelayMs: 3000,
  autoContinue: false,
  retryOnStepFailed: true,
  retryPatterns: ["EngineInternalError", "ServiceIsBusyError", "NotEnoughCvError", "Xunfei request failed"],
  retryDelayMs: 15000,
}

export function parseConfig(raw: Record<string, unknown> | undefined): WorkHardConfig {
  if (!raw) return { ...DEFAULT_CONFIG }
  return {
    endKeyword: asString(raw.endKeyword) ?? DEFAULT_CONFIG.endKeyword,
    autoAnswerQuestion: asBool(raw.autoAnswerQuestion) ?? DEFAULT_CONFIG.autoAnswerQuestion,
    questionReply: asString(raw.questionReply) ?? DEFAULT_CONFIG.questionReply,
    autoApprovePermission: asBool(raw.autoApprovePermission) ?? DEFAULT_CONFIG.autoApprovePermission,
    permissionStrategy: asPermissionStrategy(raw.permissionStrategy) ?? DEFAULT_CONFIG.permissionStrategy,
    safePermissions: asStringArray(raw.safePermissions) ?? DEFAULT_CONFIG.safePermissions,
    denyPrompt: asString(raw.denyPrompt) ?? DEFAULT_CONFIG.denyPrompt,
    continuePrompt: raw.continuePrompt === null ? null : (asString(raw.continuePrompt) ?? DEFAULT_CONFIG.continuePrompt),
    maxIdleRetries: asNumber(raw.maxIdleRetries) ?? DEFAULT_CONFIG.maxIdleRetries,
    idleDelayMs: asNumber(raw.idleDelayMs) ?? DEFAULT_CONFIG.idleDelayMs,
    autoContinue: asBool(raw.autoContinue) ?? DEFAULT_CONFIG.autoContinue,
    retryOnStepFailed: asBool(raw.retryOnStepFailed) ?? DEFAULT_CONFIG.retryOnStepFailed,
    retryPatterns: asStringArray(raw.retryPatterns) ?? DEFAULT_CONFIG.retryPatterns,
    retryDelayMs: asNumber(raw.retryDelayMs) ?? DEFAULT_CONFIG.retryDelayMs,
  }
}

export function buildContinuePrompt(config: WorkHardConfig): string {
  if (config.continuePrompt) return config.continuePrompt
  return `请检查所有任务是否全部完成，若没有完成继续，若完成回复 ${config.endKeyword}`
}

export function shouldRetryStepFailed(config: WorkHardConfig, errorMessage: string): boolean {
  if (!config.retryOnStepFailed) return false
  return config.retryPatterns.some((p) => errorMessage.includes(p))
}

const SAFE_PERMISSIONS = new Set(["read", "glob", "grep", "lsp", "webfetch", "websearch", "list"])

export function shouldAllowPermission(config: WorkHardConfig, permission: string): boolean {
  if (config.permissionStrategy === "allow-all") return true
  if (config.permissionStrategy === "deny-all") return false
  if (config.permissionStrategy === "allow-safe") {
    if (config.safePermissions.length > 0) return config.safePermissions.includes(permission)
    return SAFE_PERMISSIONS.has(permission)
  }
  return true
}

function asString(v: unknown): string | undefined {
  return typeof v === "string" ? v : undefined
}

function asNumber(v: unknown): number | undefined {
  return typeof v === "number" ? v : undefined
}

function asBool(v: unknown): boolean | undefined {
  return typeof v === "boolean" ? v : undefined
}

function asStringArray(v: unknown): string[] | undefined {
  return Array.isArray(v) && v.every((x) => typeof x === "string") ? v : undefined
}

function asPermissionStrategy(v: unknown): WorkHardConfig["permissionStrategy"] | undefined {
  if (v === "allow-all" || v === "allow-safe" || v === "deny-all") return v
  return undefined
}