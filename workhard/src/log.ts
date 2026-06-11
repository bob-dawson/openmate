export function log(tag: string, ...args: unknown[]) {
  const ts = new Date().toISOString().slice(11, 19)
  process.stderr.write(`[workhard ${ts}] [${tag}] ${args.join(" ")}\n`)
}
