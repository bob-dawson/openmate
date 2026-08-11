export default {
  id: "hello.test",
  setup: async (ctx) => {
    console.error("[hello-test] SETUP CALLED")
    return async () => {}
  },
}
