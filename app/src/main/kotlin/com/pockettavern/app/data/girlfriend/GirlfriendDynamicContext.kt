package com.pockettavern.app.data.girlfriend

/**
 * 小女友动态上下文 — 供 ChatViewModel 在 pureChat 模式下发消息时注入。
 * GirlfriendViewModel 在每次状态变化时调用 [update]。
 */
object GirlfriendDynamicContext {

    @Volatile
    var stateTag: String = ""
        private set

    /** 小女友专属的用户称呼，不读取酒馆 Persona。 */
    @Volatile
    var userName: String = "老大"
        private set

    fun update(state: GirlfriendState, permissions: GirlfriendPromptBuilder.DevicePermissions) {
        stateTag = GirlfriendPromptBuilder.buildStateTag(state, permissions)
        userName = state.petName.ifBlank { "老大" }
    }
}
