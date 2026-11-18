package com.laksh.forge.data.wearables.whoop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.laksh.forge.MainActivity

/**
 * Receives forge://whoop/callback from the browser. It shows nothing: it hands the URI to
 * WhoopAuth (whose own scope finishes the token exchange) and brings Forge's existing task back.
 * CLEAR_TOP | SINGLE_TOP pops the Custom Tab sitting above MainActivity without recreating
 * MainActivity, so the user lands back on the Wearables screen they left.
 */
class WhoopCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null &&
            data.scheme == WhoopConfig.REDIRECT_SCHEME &&
            data.host == WhoopConfig.REDIRECT_HOST
        ) {
            WhoopAuth.onRedirect(applicationContext, data)
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
