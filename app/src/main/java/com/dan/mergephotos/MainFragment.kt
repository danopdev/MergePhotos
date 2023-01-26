package com.dan.mergephotos

import android.content.Intent
import android.graphics.Bitmap
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
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
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
        private const val CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX = ".AlignedWithMask"
        private const val CACHE_MASK_SUFFIX = ".Mask"
        private const val CACHE_IMAGES_AVERAGE_SUFFIX = ".Average"

        const val ALPHA_8_TO_16 = 256.0
        const val ALPHA_16_TO_8 = 1.0 / ALPHA_8_TO_16

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

        private external fun makePanoramaNative(images: Long, panorama: Long, projection: Int): Boolean
        private external fun makeLongExposureNearestNative(images: Long, averageImage: Long, outputImage: Long): Boolean

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
            if (nearest) Imgproc.INTER_NEAREST else Imgproc.INTER_LANCZOS4
        )
        return imageSmall
    }

    private fun loadImage(uri: Uri) : Mat? {
        // Can't create MatOfByte from kotlin ByteArray, but works correctly from java byte[]
        val image = OpenCVLoadImageFromUri.load(uri, activity.contentResolver) ?: return null
        if (image.empty()) return null

        val imageRGB = Mat()

        when(image.type()) {
            CvType.CV_8UC3 -> Imgproc.cvtColor(
                image,
                imageRGB,
                Imgproc.COLOR_BGR2RGB
            )
            CvType.CV_8UC4 -> Imgproc.cvtColor(
                image,
                imageRGB,
                Imgproc.COLOR_BGRA2RGB
            )
            else -> return null
        }

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

    private fun toGrayImage(image: Mat): Mat {
        val grayMat = Mat()
        Imgproc.cvtColor(image, grayMat, Imgproc.COLOR_BGR2GRAY)

        if (CvType.CV_16UC1 == grayMat.type()) {
            val grayMat8 = Mat()
            grayMat.convertTo(grayMat8, CvType.CV_8UC1, ALPHA_16_TO_8)
            return grayMat8
        }

        return grayMat
    }

    private fun toNormalizedImage(image: Mat): Mat {
        val grayMat = toGrayImage(image)
        val normalizedMat = Mat()
        Core.normalize(grayMat, normalizedMat, 0.0, 255.0, Core.NORM_MINMAX)
        return normalizedMat
    }

    private fun orbDetectAndCompute(orbDetector: ORB, image: Mat, mask: Mat): Pair<MutableList<KeyPoint>, Mat> {
        val normalizedImage = toNormalizedImage(image)
        val keyPoints = MatOfKeyPoint()
        val descriptors = Mat()
        orbDetector.detectAndCompute(normalizedImage, mask, keyPoints, descriptors)
        return Pair(keyPoints.toList(), descriptors)
    }

    private fun alignImages(prefix: String): Pair<List<Mat>, String> {
        val inputImages = cache[prefix] ?: mutableListOf()

        val masks = cache[prefix + CACHE_MASK_SUFFIX]
        val useMask = binding.checkBoxUseMask.isChecked && null != masks && masks.isNotEmpty()
        val suffix = if (useMask) CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX else CACHE_IMAGES_ALIGNED_SUFFIX

        var alignedImages = cache[prefix + suffix]
        if (null == alignedImages) {
            val mask = if (useMask && null != masks && masks.isNotEmpty()) masks[0] else Mat()

            alignedImages = mutableListOf()

            alignedImages.add(inputImages[0])
            val orbDetector = ORB.create()
            val matcher = BFMatcher.create()
            val refInfo = orbDetectAndCompute(orbDetector, inputImages[0], mask)
            val refKeyPoints = refInfo.first
            val refDescriptors = refInfo.second

            for (imageIndex in 1 until inputImages.size) {
                val info = orbDetectAndCompute(orbDetector, inputImages[imageIndex], mask)
                val keyPoints = info.first
                val descriptors = info.second

                val matches = MatOfDMatch()
                matcher.match(descriptors, refDescriptors, matches)
                val listMatches = matches.toList().sortedBy { it.distance }
                val usedSize = listMatches.size * 80 / 100
                if (usedSize < 10) continue

                val listPoints = mutableListOf<Point>()
                val listRefPoints = mutableListOf<Point>()
                for (matchIndex in 0 until usedSize) {
                    val queryIdx = listMatches[matchIndex].queryIdx
                    val trainIdx = listMatches[matchIndex].trainIdx
                    listPoints.add(keyPoints[queryIdx].pt)
                    listRefPoints.add(refKeyPoints[trainIdx].pt)
                }

                val matListPoints = MatOfPoint2f()
                matListPoints.fromList(listPoints)

                val matListRefPoints = MatOfPoint2f()
                matListRefPoints.fromList(listRefPoints)

                val homography =
                    Calib3d.findHomography(matListPoints, matListRefPoints, Calib3d.RANSAC, 5.0)
                val alignedImage = Mat()
                Imgproc.warpPerspective(
                    inputImages[imageIndex], alignedImage, homography,
                    Size(inputImages[0].cols().toDouble(), inputImages[0].rows().toDouble()),
                    Imgproc.INTER_LANCZOS4
                )
                if (!alignedImage.empty()) alignedImages.add(alignedImage)
            }

            cache[prefix + suffix] = alignedImages
        }

        if (alignedImages.size < 2) {
            showToast( "Failed to align images !")
        }

        return Pair(alignedImages, "align")
    }

    private fun calculateAverage(prefix: String): List<Mat> {
        val alignImages = binding.checkBoxAlign.isChecked
        val useMask = alignImages && binding.checkBoxUseMask.isChecked

        val cacheKeySuffix = when {
            useMask -> CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX
            alignImages -> CACHE_IMAGES_ALIGNED_SUFFIX
            else -> ""
        }
        val cacheKey = prefix + CACHE_IMAGES_AVERAGE_SUFFIX + cacheKeySuffix
        var averageImages = cache[cacheKey]

        if (null == averageImages) {
            averageImages = mutableListOf()
            val inputImages = if (alignImages) alignImages(prefix).first else (cache[prefix] ?: listOf())
            val output = Mat()

            if (inputImages.size >= 2) {
                val floatMat = Mat()
                inputImages[0].convertTo(floatMat, CvType.CV_32FC3)

                for (imageIndex in 1 until inputImages.size) {
                    Core.add(floatMat, inputImages[imageIndex], floatMat, Mat(), CvType.CV_32FC3)
                }

                floatMat.convertTo(output, inputImages[0].type(), 1.0 / inputImages.size.toDouble())
            }

            if (!output.empty()) averageImages.add(output)
            cache[cacheKey] = averageImages
        }

        return averageImages
    }

    private fun mergeLongExposure(prefix: String): Pair<List<Mat>, String> {
        val averageImages = calculateAverage(prefix)
        val mode = binding.longexposureAlgorithm.selectedItemPosition
        var resultImages: List<Mat> = listOf()

        when(mode) {
            Settings.LONG_EXPOSURE_AVERAGE -> resultImages = averageImages

            Settings.LONG_EXPOSURE_NEAREST_TO_AVERAGE -> {
                if (averageImages.isNotEmpty()) {
                    val alignedImages = cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX]
                    if (null != alignedImages) {
                        val outputImage = Mat()

                        if (makeLongExposureNearest(
                                alignedImages,
                                averageImages[0],
                                outputImage
                            )
                        ) {
                            if (!outputImage.empty()) {
                                resultImages = listOf(outputImage)
                            }
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

                //make sure it's 8 bits per channel
                val image8BitsPerChannel: Mat
                if (CvType.CV_16UC3 == outputImage.type()) {
                    image8BitsPerChannel = Mat()
                    outputImage.convertTo(image8BitsPerChannel, CvType.CV_8UC3, ALPHA_16_TO_8)
                } else {
                    image8BitsPerChannel = outputImage
                }

                Utils.matToBitmap(image8BitsPerChannel, bitmap)
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
                try {
                    var fileName = "${outputName}_${name}.${outputExtension}"
                    var fileFullPath = Settings.SAVE_FOLDER + "/" + fileName
                    var counter = 0
                    while (File(fileFullPath).exists() && counter < 998) {
                        counter++
                        val counterStr = "%03d".format(counter)
                        fileName = "${outputName}_${name}_${counterStr}.${outputExtension}"
                        fileFullPath = Settings.SAVE_FOLDER + "/" + fileName
                    }

                    val outputRGB = Mat()
                    Imgproc.cvtColor(outputImage, outputRGB, Imgproc.COLOR_BGR2RGB)

                    File(fileFullPath).parentFile?.mkdirs()

                    val outputParams = MatOfInt()
                    outputParams.fromArray(Imgcodecs.IMWRITE_JPEG_QUALITY, settings.jpegQuality )
                    Imgcodecs.imwrite( fileFullPath, outputRGB, outputParams )

                    //copy exif tags
                    firstSourceUri?.let { uri ->
                        ExifTools.copyExif( activity.contentResolver, uri, fileFullPath )
                    }

                    //Add it to gallery
                    MediaScannerConnection.scanFile(context, arrayOf(fileFullPath), null, null)

                    showToast("Saved to: $fileName")
                } catch (e: Exception) {
                    showToast("Failed to save")
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

    private fun editMask() {
        val images = cache[CACHE_IMAGES]
        if (null == images || images.isEmpty()) return
        if (!binding.checkBoxAlign.isChecked) return
        if (!binding.checkBoxUseMask.isChecked) return

        var mask = Mat()
        val masks = cache[CACHE_IMAGES + CACHE_MASK_SUFFIX]
        if (null != masks && masks.isNotEmpty()) {
            mask = masks[0]
        }

        MaskEditFragment.show(activity, images[0], mask) {
            val maskSmall = createSmallImage(mask, true)
            cache.remove(CACHE_IMAGES + CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX)
            cache.remove(CACHE_IMAGES_SMALL + CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX)
            cache.remove(CACHE_IMAGES + CACHE_IMAGES_AVERAGE_SUFFIX + CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX)
            cache.remove(CACHE_IMAGES_SMALL + CACHE_IMAGES_AVERAGE_SUFFIX + CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX)
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

        binding.checkBoxAlign.setOnCheckedChangeListener { _, _ -> mergePhotosSmall() }
        binding.checkBoxUseMask.setOnCheckedChangeListener { _, _ -> mergePhotosSmall() }
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