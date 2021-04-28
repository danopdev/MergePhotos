package com.dan.mergephotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.mergephotos.databinding.BusyDialogBinding


class BusyDialog(private val title: String): DialogFragment() {

    companion object {
        private const val FRAGMENT_TAG = "busy"
        private var currentDialog: BusyDialog? = null
        private lateinit var activity: MainActivity
        private var counter = 0

        fun create(activity_: MainActivity) {
            activity = activity_
        }

        fun show(supportFragmentManager: FragmentManager, title: String) {
            try {
                val currentDialog = this.currentDialog
                if (null == currentDialog) {
                    val dialog = BusyDialog(title)
                    dialog.isCancelable = false
                    dialog.show(supportFragmentManager, FRAGMENT_TAG)
                    this.currentDialog = dialog
                    counter = 1
                } else {
                    currentDialog.setTitle(title)
                    counter++
                }
            } catch (e: Exception) {
            }
        }

        fun dismiss(all: Boolean = false) {
            activity.runOnUiThread {
                if( counter <= 1 || all) {
                    try {
                        currentDialog?.dismiss()
                    } catch (e: Exception) {
                    }
                    currentDialog = null
                    counter = 0
                } else {
                    counter--
                }
            }
        }

    }

    lateinit var binding: BusyDialogBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = BusyDialogBinding.inflate( inflater )
        binding.title.setText(title)
        this.binding = binding
        return binding.root
    }

    fun setTitle(title: String) {
        binding.title.setText(title)
    }
}
