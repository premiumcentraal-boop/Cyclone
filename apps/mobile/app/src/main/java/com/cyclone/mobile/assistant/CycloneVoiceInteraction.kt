package com.cyclone.mobile.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.cyclone.mobile.MainActivity
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayUserAction

/** System-owned assistant binding. Cyclone never intercepts raw hardware key events. */
class CycloneVoiceInteractionService : VoiceInteractionService()

class CycloneVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        CycloneVoiceInteractionSession(this)
}

private class CycloneVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        // The visible UI is Cyclone's existing accessibility overlay, not a second assistant window.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (OverlayChromeRuntime.isAttached()) {
            OverlayChromeRuntime.dispatch(OverlayUserAction.ASK_CYCLONE)
            OverlayChromeRuntime.beginVoiceInput()
        } else {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        hide()
    }
}
