package com.dan.mergephotos

import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import com.dan.mergephotos.databinding.SettingsFragmentBinding


class SettingsFragment(activity: MainActivity ) : AppFragment(activity) {

    companion object {
        private const val JPEG_QUALITY_BASE = 60

        fun show(activity: MainActivity ) {
            activity.pushView("Settings", SettingsFragment( activity ) )
        }
    }

    private lateinit var binding: SettingsFragmentBinding

    override fun onBack(homeButton: Boolean) {
        if (!homeButton) return

        settings.jpegQuality = JPEG_QUALITY_BASE + (100 - JPEG_QUALITY_BASE) * binding.seekBarJpegQuality.progress / binding.seekBarJpegQuality.max

        activity.settings.saveProperties()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = SettingsFragmentBinding.inflate( inflater )

        val jpegQualityProgress = when {
            settings.jpegQuality >= 100 -> binding.seekBarJpegQuality.max
            settings.jpegQuality < JPEG_QUALITY_BASE -> 0
            else -> (settings.jpegQuality - JPEG_QUALITY_BASE) * binding.seekBarJpegQuality.max / (100 - JPEG_QUALITY_BASE)
        }

        binding.seekBarJpegQuality.progress = jpegQualityProgress
        binding.txtJpegQuality.text = settings.jpegQuality.toString()

        binding.seekBarJpegQuality.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                val jpegQuality = JPEG_QUALITY_BASE + (100 - JPEG_QUALITY_BASE) * progress / binding.seekBarJpegQuality.max
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