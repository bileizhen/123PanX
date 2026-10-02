package io.github.bileizhen.pan123x.core.network

import okhttp3.Interceptor
import okhttp3.Response

/** 提供当前会话的 authorization 值（形如 "Bearer xxx"）；未登录时返回 null。 */
fun interface AuthorizationProvider {
    fun current(): String?
}

/**
 * 仅在有 token 时附加 `authorization` 头，对应参考源 `session.py` _build_headers。
 * 值本身由 AccountManager 维护，本拦截器不解析、不记录。
 */
class AuthInterceptor(private val provider: AuthorizationProvider) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Login verification supplies credentials only for this request.
        if (chain.request().header("authorization") != null) return chain.proceed(chain.request())
        val authorization = provider.current()
            ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("authorization", authorization)
            .build()
        return chain.proceed(request)
    }
}
