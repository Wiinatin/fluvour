package app.fluvour

import android.app.Application
import app.fluvour.media.ExtractorManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FluvourApp : Application() {
    @Inject lateinit var extractor: ExtractorManager

    override fun onCreate() {
        super.onCreate()
        extractor.checkInBackground()   // keeps NewPipeExtractor current: at most every 6 hours, never blocks startup
    }
}
