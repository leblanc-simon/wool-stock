package io.leblanc.woolstock

import android.app.Application

class WoolStockApp : Application() {
    val container by lazy {
        androidAppContainer(this, BuildConfig.DEFAULT_API_URL, BuildConfig.VERSION_NAME, allowCleartext = BuildConfig.DEBUG)
    }
}
