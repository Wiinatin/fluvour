package app.fluvour

import android.app.Application
import app.fluvour.data.SettingsRepository
import app.fluvour.media.ClipIndexScheduler
import app.fluvour.media.ExtractorManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FluvourApp : Application() {
    @Inject lateinit var extractor: ExtractorManager
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Thai word list for the word splitter (read on first use, in the background thread that splits the text).
        app.fluvour.reading.WordSplitter.thaiLexiconLoader = {
            assets.open("lexicon/th.tsv").bufferedReader().useLines { app.fluvour.reading.ThaiLexicon.parse(it) }
        }
        if (app.fluvour.audio.VoiceBudget(this).enabled) app.fluvour.audio.VoiceAheadScheduler.schedule(this)   // voices made ahead in the background
        app.fluvour.media.ClipAheadScheduler.schedule(this)   // due clip cards saved ahead (only works while the card front is clip)
        extractor.checkInBackground()   // keeps NewPipeExtractor current: at most every 6 hours, never blocks startup
        // Background clip library: scheduled while the setting is on (default), cancelled when it is turned off.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.settings.map { it.clipLibrary }.distinctUntilChanged().collect { on ->
                if (on) ClipIndexScheduler.schedule(this@FluvourApp) else ClipIndexScheduler.cancel(this@FluvourApp)
            }
        }
    }
}
