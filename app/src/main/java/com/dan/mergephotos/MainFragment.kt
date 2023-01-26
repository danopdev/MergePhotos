package com.dan.mergephotos

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import com.dan.mergephotos.databinding.MainFragmentBinding
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.video.Video.calcOpticalFlowPyrLK
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.INTER_LANCZOS4
import org.opencv.imgproc.Imgproc.INTER_NEAREST
import org.opencv.photo.Photo
import org.opencv.utils.Converters
import java.io.File
import kotlin.concurrent.timer

class MainFragment(activity: MainActivity) : AppFragment(activity) {
    companion object {
        private const val INTENT_OPEN_IMAGES = 2

        private const val CACHE_IMAGES = "Big"
        private const val CACHE_IMAGES_SMALL = "Small"
        private const val CACHE_IMAGES_ALIGNED_SUFFIX = ".Aligned"
        private const val CACHE_MASK_SUFFIX = ".Mask"
        private const val CACHE_IMAGES_AVERAGE_SUFFIX = ".Average"

        private fun makePanorama(images: List<Mat>, panorama: Mat, projection: Int): Boolean {
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            return makePanoramaNative(imagesMat.nativeObj, panorama.nativeObj, projection)
        }

        private fun makeLongExposureNearest(
            images: List<Mat>,
            averageImage: Mat,
            outputImage: Mat
        ): Boolean {
            if (images.size < 3) return false
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            return makeLongExposureNearestNative(
                imagesMat.nativeObj,
                averageImage.nativeObj,
                outputImage.nativeObj
            )
        }

        private fun makeLongExposureLightOrDark(
            images: List<Mat>,
            outputImage: Mat,
            light: Boolean
        ): Boolean {
            if (images.size < 3) return false
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            return makeLongExposureLightOrDarkNative(
                imagesMat.nativeObj,
                outputImage.nativeObj,
                light
            )
        }

        private external fun makePanoramaNative(images: Long, panorama: Long, projection: Int): Boolean
        private external fun makeLongExposureNearestNative(images: Long, averageImage: Long, outputImage: Long): Boolean
        private external fun makeLongExposureLightOrDarkNative(images: Long, outputImage: Long, light: Boolean): Boolean

        fun show(activity: MainActivity) {
            activity.pushView("Merge Photos", MainFragment(activity))
        }
    }

    private lateinit var binding: MainFragmentBinding
    private val cache = mutableMapOf<String, MutableList<Mat>>()
    private var outputName = Settings.DEFAULT_NAME
    private var firstSourceUri: Uri? = null

    private val listenerOnItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
            when(parent) {
                binding.spinnerMerge -> {
                    binding.panoramaOptions.isVisible = Settings.MERGE_PANORAMA == position
                    binding.longexposureOptions.isVisible = Settings.MERGE_LONG_EXPOSURE == position
                    binding.alignOptions.isVisible = Settings.MERGE_LONG_EXPOSURE == position || Settings.MERGE_HDR == position || Settings.MERGE_ALIGN == position
                }
            }

            mergePhotosSmall()
        }

        override fun onNothingSelected(parent: AdapterView<*>) {
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.main_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.loadImages -> {
                startActivityToOpenImages()
                return true
            }

            R.id.save -> {
                mergePhotosBig()
                return true
            }

            R.id.settings -> {
                SettingsFragment.show(activity)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == AppCompatActivity.RESULT_OK && requestCode == INTENT_OPEN_IMAGES) {
            data?.clipData?.let { clipData ->
                val uriList = mutableListOf<Uri>()
                val count = clipData.itemCount
                for (i in 0 until count) {
                    uriList.add(clipData.getItemAt(i).uri)
                }

                loadImages(uriList.toList())
            }
        }
    }

    private fun loadImages( uriList: List<Uri> ) {
        imagesClear()
        outputName = Settings.DEFAULT_NAME
        firstSourceUri = null
        BusyDialog.show(/*supportFragmentManager*/ requireFragmentManager(), "Loading images")

        val imagesBig = mutableListOf<Mat>()
        val imagesSmall = mutableListOf<Mat>()

        runFakeAsync {
            var nameFound = false

            for (uri in uriList) {
                val image = loadImage(uri) ?: continue
                if (null == firstSourceUri) firstSourceUri = uri

                try {
                    if (!nameFound) {
                        DocumentFile.fromSingleUri( requireContext(), uri )?.name?.let { name ->
                            if (name.isNotEmpty()) {
                                nameFound = true
                                val fields = name.split('.')
                                outputName = fields[0]
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                imagesBig.add(image)
            }

            if (imagesBig.size < 2) {
                showNotEnoughImagesToast()
            } else {
                for (image in imagesBig) {
                    imagesSmall.add(createSmallImage(image))
                }

                if (imagesBig.size < 2) {
                    showNotEnoughImagesToast()
                } else {
                    cache[CACHE_IMAGES] = imagesBig
                    cache[CACHE_IMAGES_SMALL] = imagesSmall
                    mergePhotosSmall()
                }
            }

            BusyDialog.dismiss()
        }
    }

    private fun runFakeAsync(l: () -> Unit) {
        timer(null, false, 500, 500) {
            this.cancel()
            activity.runOnUiThread {
                l.invoke()
            }
        }
    }

    private fun showNotEnoughImagesToast() {
        showToast("You must select at least 2 images")
    }

    private fun startActivityToOpenImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_TITLE, "Select images")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        startActivityForResult(intent, INTENT_OPEN_IMAGES)
    }

    private fun imagesClear() {
        cache.clear()
    }

    private fun createSmallImage(image: Mat, nearest: Boolean = false) : Mat {
        val widthSmall: Int
        val heightSmall: Int

        if (image.rows() < image.cols()) {
            widthSmall = Settings.IMG_SIZE_SMALL
            heightSmall = Settings.IMG_SIZE_SMALL * image.rows() / image.cols()
        } else {
            widthSmall = Settings.IMG_SIZE_SMALL * image.cols() / image.rows()
            heightSmall = Settings.IMG_SIZE_SMALL
        }

        if (image.cols() <= widthSmall && image.rows() <= heightSmall) return image

        val imageSmall = Mat()
        Imgproc.resize(
            image, imageSmall,
            Size(widthSmall.toDouble(), heightSmall.toDouble()),
            0.0, 0.0,
            if (nearest) INTER_NEAREST else INTER_LANCZOS4
        )
        return imageSmall
    }

    private fun loadImage(uri: Uri) : Mat? {
        val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (null == bitmap)  return null

        val image = Mat()
        Utils.bitmapToMat(bitmap, image)
        if (image.empty()) return null

        val imageRGB = Mat()
        Imgproc.cvtColor(
            image,
            imageRGB,
            Imgproc.COLOR_RGBA2RGB)

        return imageRGB
    }

    private fun mergePanorama(prefix: String): Pair<List<Mat>, String> {
        //parameters
        val mode = binding.panoramaProjection.selectedItemPosition

        val inputImages = cache[prefix] ?: return Pair(listOf(), "")
        val output = Mat()
        makePanorama(inputImages.toList(), output, mode)

        val outputList = mutableListOf<Mat>()
        val filePrefix = "panorama_" + binding.panoramaProjection.selectedItem.toString()

        if (!output.empty()) {
            outputList.add(output)
        }

        return Pair(outputList.toList(), filePrefix)
    }

    private fun alignImages(prefix: String): Pair<List<Mat>, String> {
        val inputImages = cache[prefix] ?: mutableListOf()

        var alignedImages = cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX]
        if (null == alignedImages) {
            val masks = cache[prefix + CACHE_MASK_SUFFIX]
            val mask = if (null != masks && masks.isNotEmpty()) masks[0] else Mat()

            alignedImages = mutableListOf()
            alignedImages.add(inputImages[0])

            val firstFramePts = MatOfPoint()
            val firstFrameGray = Mat()

            Imgproc.cvtColor(inputImages[0], firstFrameGray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.goodFeaturesToTrack(firstFrameGray, firstFramePts,200,0.01, 30.0, mask)

            val firstFramePts2f = MatOfPoint2f()
            firstFramePts2f.fromList(firstFramePts.toList())

            for (imageIndex in 1 until inputImages.size) {
                val frameGray = Mat()
                Imgproc.cvtColor(inputImages[imageIndex], frameGray, Imgproc.COLOR_RGB2GRAY)

                val framePts2f = MatOfPoint2f()
                val status = MatOfByte()
                val err = MatOfFloat()
                calcOpticalFlowPyrLK(firstFrameGray, frameGray, firstFramePts2f, framePts2f, status, err)

                // Filter only valid points
                val firstFramePts2fList = firstFramePts2f.toList()
                val framePts2fList = framePts2f.toList()
                val statusList = status.toList()

                val firstFramePts2fFilteredList = mutableListOf<Point>()
                val framePts2fFilteredList = mutableListOf<Point>()

                for( i in firstFramePts2fList.indices ) {
                    if (0.toByte() != statusList[i]) {
                        firstFramePts2fFilteredList.add(firstFramePts2fList[i])
                        framePts2fFilteredList.add(framePts2fList[i])
                    }
                }

                // Find transformation matrix
                val firstFramePtsMat = MatOfPoint2f()
                val framePtsMat = MatOfPoint2f()

                firstFramePtsMat.fromList(firstFramePts2fFilteredList)
                framePtsMat.fromList(framePts2fFilteredList)

                val t = Calib3d.estimateAffinePartial2D(framePtsMat, firstFramePtsMat)
                if (t.empty()) continue //failed to align

                val alignedFrame = Mat()
                Imgproc.warpAffine(inputImages[imageIndex], alignedFrame, t, inputImages[imageIndex].size(), INTER_LANCZOS4)
                if (alignedFrame.empty()) continue //failed to warp !

                alignedImages.add(alignedFrame)
            }

            cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX] = alignedImages
        }

        if (alignedImages.size < 2) {
            showToast( "Failed to align images !")
        }

        return Pair(alignedImages, "align")
    }

    private fun calculateAverage(prefix: String): List<Mat> {
        val alignImages = binding.checkBoxAlign.isChecked
        val cacheKey = prefix + CACHE_IMAGES_AVERAGE_SUFFIX
        var averageImages = cache[cacheKey]

        if (null == averageImages) {
            averageImages = mutableListOf()
            val inputImages = if (alignImages) alignImages(prefix).first else (cache[prefix] ?: listOf())
            val output = Mat()

            if (inputImages.size >= 2) {
                val floatMat = Mat()
                inputImages[0].convertTo(floatMat, CvType.CV_16UC3)

                for (imageIndex in 1 until inputImages.size) {
                    Core.add(floatMat, inputImages[imageIndex], floatMat, Mat(), CvType.CV_16UC3)
                }

                floatMat.convertTo(output, inputImages[0].type(), 1.0 / inputImages.size.toDouble())
            }

            if (!output.empty()) averageImages.add(output)
            cache[cacheKey] = averageImages
        }

        return averageImages
    }

    private fun mergeLongExposure(prefix: String): Pair<List<Mat>, String> {
        val alignImages = binding.checkBoxAlign.isChecked
        val mode = binding.longexposureAlgorithm.selectedItemPosition
        var resultImages: List<Mat> = listOf()

        when(mode) {
            Settings.LONG_EXPOSURE_AVERAGE -> {
                val averageImages = calculateAverage(prefix)
                resultImages = averageImages
            }

            Settings.LONG_EXPOSURE_NEAREST_TO_AVERAGE -> {
                val averageImages = calculateAverage(prefix)
                if (averageImages.isNotEmpty()) {
                    val inputImages = if (alignImages) cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX] else (cache[prefix] ?: listOf())
                    if (null != inputImages && inputImages.isNotEmpty()) {
                        val outputImage = Mat()

                        if (makeLongExposureNearest(inputImages, averageImages[0], outputImage)) {
                            if (!outputImage.empty()) {
                                resultImages = listOf(outputImage)
                            }
                        }
                    }
                }
            }

            Settings.LONG_EXPOSURE_LIGHT, Settings.LONG_EXPOSURE_DARK -> {
                val inputImages = if (alignImages) alignImages(prefix).first else (cache[prefix] ?: listOf())
                if (inputImages.isNotEmpty()) {
                    val outputImage = Mat()
                    if (makeLongExposureLightOrDark(inputImages, outputImage, Settings.LONG_EXPOSURE_LIGHT == mode)) {
                        if (!outputImage.empty()) {
                            resultImages = listOf(outputImage)
                        }
                    }
                }
            }
        }

        return Pair(
            resultImages,
            "longexposure_" + binding.longexposureAlgorithm.selectedItem.toString()
        )
    }

    private fun mergeHdr(prefix: String): Pair<List<Mat>, String> {
        val alignImages = binding.checkBoxAlign.isChecked
        val inputImages = if (alignImages) alignImages(prefix).first else ( cache[prefix] ?: listOf() )
        val output = Mat()

        if (inputImages.size >= 2) {
            val hdrMat = Mat()
            val mergeMertens = Photo.createMergeMertens()
            mergeMertens.process(inputImages, hdrMat)

            if (!hdrMat.empty()) {
                hdrMat.convertTo(output, inputImages[0].type(), 255.0)
            }
        }

        val outputList = if (output.empty()) listOf() else listOf(output)
        return Pair(outputList, "hdr")
    }

    private fun mergePhotos(prefix: String, l: (output: List<Mat>, name: String) -> Unit) {
        val inputImages = cache[prefix]
        if (null == inputImages || inputImages.size < 2) return

        BusyDialog.show(requireFragmentManager(), "Merging photos ...")
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val merge = binding.spinnerMerge.selectedItemPosition

        runFakeAsync {
            val result: Pair<List<Mat>, String> = when(merge) {
                Settings.MERGE_PANORAMA -> mergePanorama(prefix)
                Settings.MERGE_LONG_EXPOSURE -> mergeLongExposure(prefix)
                Settings.MERGE_HDR -> mergeHdr(prefix)
                Settings.MERGE_ALIGN -> alignImages(prefix)
                else -> Pair(listOf(), "")
            }

            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            l.invoke(result.first, result.second)
            BusyDialog.dismiss()
        }
    }

    private fun mergePhotosSmall() {
        mergePhotos(CACHE_IMAGES_SMALL) { outputImages, _ ->
            if (outputImages.isEmpty()) {
                setBitmap(null)
            } else {
                val outputImage = outputImages[0]
                val bitmap = Bitmap.createBitmap(
                    outputImage.cols(),
                    outputImage.rows(),
                    Bitmap.Config.ARGB_8888
                )

                Utils.matToBitmap(outputImage, bitmap)
                setBitmap(bitmap)
            }
        }
    }

    private fun mergePhotosBig() {
        mergePhotos(CACHE_IMAGES) { outputImages, name ->
            settings.mergeMode = binding.spinnerMerge.selectedItemPosition
            settings.panoramaProjection = binding.panoramaProjection.selectedItemPosition
            settings.longexposureAlgorithm = binding.longexposureAlgorithm.selectedItemPosition
            settings.saveProperties()

            val outputExtension = Settings.EXT_JPEG

            BusyDialog.show(requireFragmentManager(), "Saving")

            for (outputImage in outputImages) {
                var fileName = "${outputName}_${name}.${outputExtension}"
                var file = File(Settings.SAVE_FOLDER, fileName)
                var counter = 0
                while (file.exists() && counter < 998) {
                    counter++
                    val counterStr = "%03d".format(counter)
                    fileName = "${outputName}_${name}_${counterStr}.${outputExtension}"
                    file = File(Settings.SAVE_FOLDER, fileName)
                }

                try {
                    file.parentFile?.mkdirs()

                    val bitmap = Bitmap.createBitmap( outputImage.width(), outputImage.height(), Bitmap.Config.ARGB_8888 )
                    Utils.matToBitmap(outputImage, bitmap)
                    val outputStream = file.outputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, outputStream)
                    outputStream.close()

                    //copy exif tags
                    firstSourceUri?.let { uri ->
                        ExifTools.copyExif(activity.contentResolver, uri, file)
                    }

                    //Add it to gallery
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)

                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (file.exists()) {
                    showToast("Saved to: $fileName")
                } else {
                    showToast("Save failed")
                }
            }
            BusyDialog.dismiss()
        }
    }

    private fun setBitmap(bitmap: Bitmap?) {
        if (null != bitmap) {
            binding.imageView.setBitmap(bitmap)
        } else {
            showToast("Failed to merge photos")
            binding.imageView.setBitmap(null)
        }
    }

    private fun cleanUpAlignedImages() {
        cache.remove(CACHE_IMAGES + CACHE_IMAGES_ALIGNED_SUFFIX)
        cache.remove(CACHE_IMAGES_SMALL + CACHE_IMAGES_ALIGNED_SUFFIX)
        cache.remove(CACHE_IMAGES + CACHE_IMAGES_AVERAGE_SUFFIX)
        cache.remove(CACHE_IMAGES_SMALL + CACHE_IMAGES_AVERAGE_SUFFIX)
    }

    private fun editMask() {
        val images = cache[CACHE_IMAGES]
        if (null == images || images.isEmpty()) return
        if (!binding.checkBoxAlign.isChecked) return

        var mask = Mat()
        val masks = cache[CACHE_IMAGES + CACHE_MASK_SUFFIX]
        if (null != masks && masks.isNotEmpty()) {
            mask = masks[0]
        }

        MaskEditFragment.show(activity, images[0], mask) {
            cleanUpAlignedImages()
            val maskSmall = createSmallImage(mask, true)
            cache[CACHE_IMAGES + CACHE_MASK_SUFFIX] = mutableListOf(mask)
            cache[CACHE_IMAGES_SMALL + CACHE_MASK_SUFFIX] = mutableListOf(maskSmall)
            mergePhotosSmall()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(inflater)

        binding.spinnerMerge.onItemSelectedListener = listenerOnItemSelectedListener
        binding.panoramaProjection.onItemSelectedListener = listenerOnItemSelectedListener
        binding.longexposureAlgorithm.onItemSelectedListener = listenerOnItemSelectedListener

        binding.spinnerMerge.setSelection( if (settings.mergeMode >= binding.spinnerMerge.adapter.count) 0 else settings.mergeMode )
        binding.panoramaProjection.setSelection( if (settings.panoramaProjection >= binding.panoramaProjection.adapter.count) 0 else settings.panoramaProjection )
        binding.longexposureAlgorithm.setSelection( if (settings.longexposureAlgorithm >= binding.longexposureAlgorithm.adapter.count) 0 else settings.longexposureAlgorithm )

        binding.checkBoxAlign.setOnCheckedChangeListener { _, isChecked ->
            binding.btnEditMask.isEnabled = isChecked
            cleanUpAlignedImages()
            mergePhotosSmall()
        }
        binding.btnEditMask.setOnClickListener { editMask() }

        if (activity.intent?.action == Intent.ACTION_SEND_MULTIPLE && activity.intent.type?.startsWith("image/") == true) {
            activity.intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let { list ->
                val uriList = mutableListOf<Uri>()
                list.forEach { uriList.add( it as Uri) }
                loadImages( uriList.toList() )
            }
        }

        setHasOptionsMenu(true)

        return binding.root
    }
}