package io.github.bileizhen.pan123x

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.bileizhen.pan123x.core.logging.LogSource

open class PanXApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.logger.i(LogSource.APP, "123PanX 已启动")
    }

    /** Instrumentation supplies isolated storage and clients through the same explicit DI path. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    /** 全局图片加载：走不带认证头的干净图片客户端，淡入过渡。 */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(container.imageHttpClient)) }
        .crossfade(true)
        .build()
}
