package cz.majkey.pocasicesko

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.monetization.AdsController
import cz.majkey.pocasicesko.monetization.PremiumBillingController
import cz.majkey.pocasicesko.ui.WeatherApp

class MainActivity : ComponentActivity() {
    private var adsController: AdsController? = null
    private var premiumBillingController: PremiumBillingController? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adsController = if (BuildConfig.MONETIZATION_CONFIGURED) AdsController(this) else null
        premiumBillingController =
            if (BuildConfig.PAYMENTS_ENABLED) PremiumBillingController(this) else null
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WeatherApp(
                repository = remember { WeatherRepository(applicationContext) },
                adsController = adsController,
                premiumBillingController = premiumBillingController,
                paymentsEnabled = BuildConfig.PAYMENTS_ENABLED,
                onLanguage = { tag -> AppLocale.set(this, tag) },
            )
        }
        premiumBillingController?.start()
        adsController?.start()
    }

    override fun onResume() {
        super.onResume()
        premiumBillingController?.start()
    }

    override fun onDestroy() {
        premiumBillingController?.close()
        super.onDestroy()
    }
}
