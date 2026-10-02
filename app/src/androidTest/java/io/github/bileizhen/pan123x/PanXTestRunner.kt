package io.github.bileizhen.pan123x

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import io.github.bileizhen.pan123x.core.database.AppDatabase
import okhttp3.OkHttpClient
import java.io.IOException

/** UI tests never read user credentials, clear the user's DB, or contact cloud APIs. */
class PanXTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, PanXTestApplication::class.java.name, context)
}

class PanXTestApplication : PanXApplication() {
    override fun createContainer(): AppContainer {
        fun localClient() = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host !in setOf("localhost", "127.0.0.1", "::1")) {
                throw IOException("Instrumentation permits only local test servers")
            }
            chain.proceed(chain.request())
        }.build()
        return AppContainer(this, ContainerOverrides(
            database = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build(),
            preferencesPrefix = "instrumentation-${System.nanoTime()}-",
            restoreCredentials = false,
            apiClient = localClient(),
            transferClient = localClient(),
            qrClient = localClient(),
        ))
    }
}
