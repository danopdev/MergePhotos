package com.dan.mergephotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.DialogFragment
import com.dan.mergephotos.databinding.SettingsDialogBinding


class SettingsDialog(private val activity: MainActivity ) : DialogFragment() {

    companion object {
        private const val DIALOG_TAG = "SETTINGS_DIALOG"
        private const val JPEG_QUALITY_BASE = 60
        private const val JPEG_QIALITY_TICK = 5

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

        val alignedJpegQuality = when {
            activity.settings.jpegQuality > 100 -> 100
            activity.settings.jpegQuality < JPEG_QUALITY_BASE -> JPEG_QUALITY_BASE
            else -> (activity.settings.jpegQuality / JPEG_QIALITY_TICK) * JPEG_QIALITY_TICK //round the value to tick size
        }
        val jpegTick = (alignedJpegQuality - JPEG_QUALITY_BASE) / JPEG_QIALITY_TICK
        binding.seekBarJpegQuality.progress = jpegTick
        binding.txtJpegQuality.text = alignedJpegQuality.toString()

        binding.spinnerPngDepth.setSelection(activity.settings.pngDepth)
        binding.spinnerTiffDepth.setSelection(activity.settings.tiffDepth)
        binding.spinnerEngineDepth.setSelection(activity.settings.engineDepth)

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnOK.setOnClickListener {
            if (binding.radioPng.isChecked) activity.settings.outputType = Settings.OUTPUT_TYPE_PNG
            else if (binding.radioTiff.isChecked) activity.settings.outputType = Settings.OUTPUT_TYPE_TIFF
            else activity.settings.outputType = Settings.OUTPUT_TYPE_JPEG

            activity.settings.jpegQuality = JPEG_QUALITY_BASE + binding.seekBarJpegQuality.progress * JPEG_QIALITY_TICK
            activity.settings.pngDepth = binding.spinnerPngDepth.selectedItemPosition
            activity.settings.tiffDepth = binding.spinnerTiffDepth.selectedItemPosition
            activity.settings.engineDepth = binding.spinnerEngineDepth.selectedItemPosition

            activity.settings.saveProperties()
            dismiss()
        }

        binding.seekBarJpegQuality.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                val jpegQuality = JPEG_QUALITY_BASE + progress * JPEG_QIALITY_TICK
                binding.txtJpegQuality.text = jpegQuality.toString()
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }
        })

        return binding.root
    }
}