package cafe.kankipro.vpn

import android.app.Application

/**
 * The application of the Robolectric tests: Robolectric has no Android Keystore, so the encrypted storage gets a
 * software key before anything opens it (the real KankiApp opens it in onCreate). The rest is what KankiApp does,
 * without the VPN engine's always-on hook.
 */
class TestApp : Application() {
    override fun onCreate() {
        useSoftKeys()
        super.onCreate()
        Motion.init(this)
        Seal.init(this)
    }
}
