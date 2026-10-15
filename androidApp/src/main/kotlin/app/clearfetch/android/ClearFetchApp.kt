package app.clearfetch.android

import android.app.Application
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.CompletableDeferred

class ClearFetchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 번들된 yt-dlp/ffmpeg를 앱 저장소로 푸는 작업은 느리므로 백그라운드에서 한다.
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                engineReady.complete(Unit)
            } catch (e: Exception) {
                engineReady.completeExceptionally(e)
            }
        }.start()
    }

    companion object {
        val engineReady = CompletableDeferred<Unit>()
    }
}
