package com.spamshield.android.ui

import android.app.Activity
import android.view.MenuItem

/** Activity whose action-bar "up" arrow simply finishes it. */
abstract class BaseActivity : Activity() {
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
