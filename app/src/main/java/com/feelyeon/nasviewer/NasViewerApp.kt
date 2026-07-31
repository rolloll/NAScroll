package com.feelyeon.nasviewer

import android.app.Application
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Replaces Glide's default network stack (a plain HttpURLConnection fetcher with a
 * ~2.5s timeout) with OkHttp and much longer timeouts. The NAS is reached over a
 * bandwidth-limited home connection, so page images routinely take longer than that
 * to arrive — with the default timeout, slow-but-successful loads were being treated
 * as failures. The capped dispatcher also keeps 24-image chapters from opening dozens
 * of simultaneous transfers that would otherwise all crawl at once.
 */
class NasViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dispatcher = Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 2
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .build()
        Glide.get(this).registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(client))
    }
}
