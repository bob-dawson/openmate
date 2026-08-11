export default {
  id: "test-hello",
  effect: (host) => {
    console.error("[test-hello] plugin loaded successfully")
    return undefined
  },
}
