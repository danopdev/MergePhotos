package com.dan.mergephotos

import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.dan.mergephotos.databinding.SettingsFragmentBinding


class SettingsFragment(private val activity: MainActivity ) : Fragment() {

    companion object {
        private const val JPEG_QUALITY_BASE = 60
        private const val JPEG_QUALITY_TICK = 5

        fun show(activity: MainActivity ) {
            activity.pushView("Settings", SettingsFragment( activity ) )
        }
    }

    private lateinit var binding: SettingsFragmentBinding

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.ok_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.ok -> {
                if (binding.radioPng.isChecked) activity.settings.outputType = Settings.OUTPUT_TYPE_PNG
                else if (binding.radioTiff.isChecked) activity.settings.outputType = Settings.OUTPUT_TYPE_TIFF
                else activity.settings.outputType = Settings.OUTPUT_TYPE_JPEG

                activity.settings.jpegQuality = JPEG_QUALITY_BASE + binding.seekBarJpegQuality.progress * JPEG_QUALITY_TICK
                activity.settings.pngDepth = binding.spinnerPngDepth.selectedItemPosition
                activity.settings.tiffDepth = binding.spinnerTiffDepth.selectedItemPosition
                activity.settings.engineDepth = binding.spinnerEngineDepth.selectedItemPosition

                activity.settings.saveProperties()

                activity.popView()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = SettingsFragmentBinding.inflate( inflater )

        binding.radioPng.isChecked = Settings.OUTPUT_TYPE_PNG == activity.settings.outputType
        binding.radioTiff.isChecked = Settings.OUTPUT_TYPE_TIFF == activity.settings.outputType
        binding.radioJpeg.isChecked = ! (binding.radioPng.isChecked || binding.radioTiff.isChecked)

        val alignedJpegQuality = when {
            activity.settings.jpegQuality > 100 -> 100
            activity.settings.jpegQuality < JPEG_QUALITY_BASE -> JPEG_QUALITY_BASE
            else -> (activity.settings.jpegQuality / JPEG_QUALITY_TICK) * JPEG_QUALITY_TICK //round the value to tick size
        }
        val jpegTick = (alignedJpegQuality - JPEG_QUALITY_BASE) / JPEG_QUALITY_TICK
        binding.seekBarJpegQuality.progress = jpegTick
        binding.txtJpegQuality.text = alignedJpegQuality.toString()

        binding.spinnerPngDepth.setSelection(activity.settings.pngDepth)
        binding.spinnerTiffDepth.setSelection(activity.settings.tiffDepth)
        binding.spinnerEngineDepth.setSelection(activity.settings.engineDepth)

        binding.seekBarJpegQuality.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, p2: Boolean) {
                val jpegQuality = JPEG_QUALITY_BASE + progress * JPEG_QUALITY_TICK
                binding.txtJpegQuality.text = jpegQuality.toString()
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }
        })

        setHasOptionsMenu(true)

        return binding.root
    }
}