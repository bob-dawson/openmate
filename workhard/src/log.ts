export function log(tag: string, ...args: unknown[]) {
  const ts = new Date().toISOString().slice(11, 19)
  console.log(`[workhard ${ts}] [${tag}]`, ...args)
}
