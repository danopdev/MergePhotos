package com.dan.mergephotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.dan.mergephotos.databinding.SettingsDialogBinding


class SettingsDialog(private val activity: MainActivity ) : DialogFragment() {

    companion object {
        private const val DIALOG_TAG = "SETTINGS_DIALOG"

        fun show(activity: MainActivity ) {
            with( SettingsDialog( activity ) ) {
                isCancelable = false
                show(activity.supportFragmentManager, DIALOG_TAG)
            }
        }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SettingsDialogBinding.inflate( inflater )

        binding.radioPng.isChecked = Settings.OUTPUT_TYPE_PNG == activity.settings.outputType
        binding.radioTiff.isChecked = Settings.OUTPUT_TYPE_TIFF == activity.settings.outputType
        binding.radioJpeg.isChecked = ! (binding.radioPng.isChecked || binding.radioTiff.isChecked)

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnOK.setOnClickListener {
            if (binding.radioPng.isChecked) activity.settings.outputType = Settings.OUTPUT_TYPE_PNG
            else if (binding.radioTiff.isChecked) activity.settings.outputType = Settings.OUTPUT_TYPE_TIFF
            else activity.settings.outputType = Settings.OUTPUT_TYPE_JPEG
            activity.settings.saveProperties()
            dismiss()
        }

        return binding.root
    }
}