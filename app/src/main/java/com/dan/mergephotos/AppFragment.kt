package com.dan.mergephotos

import androidx.fragment.app.Fragment

open class AppFragment(val activity: MainActivity) : Fragment() {
    val settings = activity.settings

    open fun onBack(homeButton: Boolean) {
    }

    fun showToast(message: String) {
        activity.showToast(message)
    }
}