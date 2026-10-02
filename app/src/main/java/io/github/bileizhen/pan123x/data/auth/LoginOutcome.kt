package io.github.bileizhen.pan123x.data.auth

/** 登录结果：失败只携带用户可读文案，不透出异常与 code 细节。 */
sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data class Failure(val userMessage: String) : LoginOutcome
}
