package com.lilac.anime.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lilac.anime.MainActivity

/** Receives the three custom Android PiP actions. */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            MainActivity.PIP_ACTION_BACKGROUND_AUDIO,
            MainActivity.PIP_ACTION_PLAY_PAUSE,
            MainActivity.PIP_ACTION_NEXT -> MainActivity.handlePipAction(context, action)
        }
    }
}
