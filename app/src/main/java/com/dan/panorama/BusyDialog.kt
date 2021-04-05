package com.dan.panorama

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.panorama.databinding.BusyDialogBinding


class BusyDialog: DialogFragment() {

    companion object {
        private const val FRAGMENT_TAG = "busy"
        private var currentDialog: BusyDialog? = null
        private lateinit var activity: MainActivity

        fun create(activity_: MainActivity) {
            activity = activity_
        }

        fun show(supportFragmentManager: FragmentManager) {
            if (null == currentDialog) {
                val dialog = BusyDialog()
                dialog.isCancelable = false
                dialog.show(supportFragmentManager, FRAGMENT_TAG)
                currentDialog = dialog
            }
        }

        fun dismiss() {
            activity.runOnUiThread {
                try {
                    currentDialog?.dismiss()
                } catch (e: Exception) {
                }
                currentDialog = null
            }
        }

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return BusyDialogBinding.inflate( inflater ).root
    }
}
