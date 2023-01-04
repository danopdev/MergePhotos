
package com.dan.mergephotos

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import com.dan.mergephotos.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d.*
import org.opencv.core.*
import org.opencv.core.Core.*
import org.opencv.core.CvType.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs.*
import org.opencv.imgproc.Imgproc.*
import org.opencv.photo.Photo.createMergeMertens
import org.opencv.utils.Converters
import org.opencv.xphoto.Xphoto
import java.io.File
import kotlin.concurrent.timer


class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_OPEN_IMAGES = 2

        const val CACHE_IMAGES = "Big"
        const val CACHE_IMAGES_SMALL = "Small"
        const val CACHE_IMAGES_ALIGNED_SUFFIX = ".Aligned"
        const val CACHE_IMAGES_ALIGNED_WITH_MASK_SUFFIX = ".AlignedWithMask"
        const val CACHE_MASK_SUFFIX = ".Mask"
        const val CACHE_IMAGES_AVERAGE_SUFFIX = ".Average"

        const val ALPHA_8_TO_16 = 256.0
        const val ALPHA_16_TO_8 = 1.0 / ALPHA_8_TO_16

        private fun log(msg: String) {
            Log.i("MERGE", msg)
        }

        fun makePanorama(images: List<Mat>, panorama: Mat, mask: Mat?, projection: Int): Boolean {
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            return makePanoramaNative(imagesMat.nativeObj, panorama.nativeObj, mask?.nativeObj ?: 0, projection)
        }

        fun makeLongExposureNearest(
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

        external fun makePanoramaNative(images: Long, panorama: Long, mask: Long, projection: Int): Boolean
        external fun makeLongExposureNearestNative(
            images: Long,
            averageImage: Long,
            outputImage: Long
        ): Boolean
    }

    val settings: Settings by lazy { Settings(this) }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
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

    init {
        BusyDialog.create(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
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
                SettingsDialog.show(this)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && requestCode == INTENT_OPEN_IMAGES) {
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
        BusyDialog.show(supportFragmentManager, "Loading images")

        val imagesBig = mutableListOf<Mat>()
        val imagesSmall = mutableListOf<Mat>()

        runFakeAsync {
            var nameFound = false
            var has8BitsImages = false
            var has16BitsImages = false

            for (uri in uriList) {
                val image = loadImage(uri) ?: continue
                if (null == firstSourceUri) firstSourceUri = uri

                try {
                    if (!nameFound) {
                        DocumentFile.fromSingleUri( applicationContext, uri )?.name?.let { name ->
                            if (name.length > 0) {
                                nameFound = true
                                val fields = name.split('.')
                                outputName = fields[0]
                                log("Output name: ${outputName}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                imagesBig.add(image)

                if (CV_8UC3 == image.type()) has8BitsImages = true
                if (CV_16UC3 == image.type()) has16BitsImages = true
            }

            if (imagesBig.size < 2) {
                showNotEnoughImagesToast()
            } else {
                if (has8BitsImages && has16BitsImages) {
                    for (i in 0 until imagesBig.size) {
                        imagesBig[i] = convertToDepth(imagesBig[i], Settings.DEPTH_16_BITS)
                    }
                }

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
            runOnUiThread {
                l.invoke()
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
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

    private fun exitApp() {
        setResult(0)
        finish()
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(msg)
            .setIcon(android.R.drawable.stat_notify_error)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> exitApp() }
            .show()
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun handleRequestPermissions(grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allowedAll = false
                    break
                }
            }
        }

        if (allowedAll) onPermissionsAllowed()
        else fatalError("You must allow permissions !")
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
        resize(
            image, imageSmall,
            Size(widthSmall.toDouble(), heightSmall.toDouble()),
            0.0, 0.0,
            if (nearest) INTER_NEAREST else INTER_LANCZOS4 )
        return imageSmall
    }

    private fun convertToDepth( image: Mat, depth: Int ) : Mat {
        when( depth ) {
            Settings.DEPTH_8_BITS -> {
                if (CV_16UC3 == image.type()) {
                    val newImage = Mat()
                    image.convertTo(newImage, CV_8UC3, ALPHA_16_TO_8)
                    return newImage
                }
            }

            Settings.DEPTH_16_BITS -> {
                if (CV_8UC3 == image.type()) {
                    val newImage = Mat()
                    image.convertTo(newImage, CV_16UC3, ALPHA_8_TO_16)
                    return newImage
                }
            }
        }

        return image
    }

    private fun loadImage(uri: Uri) : Mat? {
        // Can't create MatOfByte from kotlin ByteArray, but works correctly from java byte[]
        val image = OpenCVLoadImageFromUri.load(uri, contentResolver) ?: return null
        if (image.empty()) return null

        val imageRGB = Mat()

        when(image.type()) {
            CV_8UC3, CV_16UC3 -> cvtColor(image, imageRGB, COLOR_BGR2RGB)
            CV_8UC4, CV_16UC4 -> cvtColor(image, imageRGB, COLOR_BGRA2RGB)
            else -> return null
        }

        return convertToDepth(imageRGB, settings.engineDepth)
    }

    private fun mergePanorama(prefix: String): Pair<List<Mat>, String> {
        log("Panorama: Start")

        //parameters
        val mode = binding.panoramaProjection.selectedItemPosition
        val inpaint = binding.panoramaInpaint.isChecked

        val inputImages = cache[prefix] ?: return Pair(listOf(), "")
        val output = Mat()
        val mask: Mat? = if (inpaint) Mat() else null
        makePanorama(inputImages.toList(), output, mask, mode)
        log("Panorama: ${if (output.empty()) "Failed" else "Success"}")

        val outputList = mutableListOf<Mat>()

        val filePrefix = "panorama_" +
                binding.panoramaProjection.selectedItem.toString() +
                (if (inpaint) "_inpaint" else "")

        if (!output.empty()) {
            if (null != mask) {
                val finalMat = Mat()
                Xphoto.inpaint(output, mask, finalMat, Xphoto.INPAINT_SHIFTMAP)
                if (!finalMat.empty()) outputList.add(finalMat)
            } else {
                outputList.add(output)
            }
        }

        return Pair(outputList.toList(), filePrefix)
    }

    private fun toGrayImage(image: Mat): Mat {
        val grayMat = Mat()
        cvtColor(image, grayMat, COLOR_BGR2GRAY)

        if (CV_16UC1 == grayMat.type()) {
            val grayMat8 = Mat()
            grayMat.convertTo(grayMat8, CV_8UC1, ALPHA_16_TO_8)
            return grayMat8
        }

        return grayMat
    }

    private fun toNormalizedImage(image: Mat): Mat {
        val grayMat = toGrayImage(image)
        val normalizedMat = Mat()
        normalize(grayMat, normalizedMat, 0.0, 255.0, NORM_MINMAX)
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

        log("Align: Start")

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

                val homography = findHomography(matListPoints, matListRefPoints, RANSAC, 5.0)
                val alignedImage = Mat()
                warpPerspective(
                    inputImages[imageIndex], alignedImage, homography,
                    Size(inputImages[0].cols().toDouble(), inputImages[0].rows().toDouble()),
                    INTER_LANCZOS4
                )
                if (!alignedImage.empty()) alignedImages.add(alignedImage)
            }

            cache[prefix + suffix] = alignedImages
        }

        log("Align: End")

        if (alignedImages.size < 2) {
            Toast.makeText(applicationContext, "Failed to align images !", Toast.LENGTH_LONG).show()
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
                inputImages[0].convertTo(floatMat, CV_32FC3)

                for (imageIndex in 1 until inputImages.size) {
                    add(floatMat, inputImages[imageIndex], floatMat, Mat(), CV_32FC3)
                }

                floatMat.convertTo(output, inputImages[0].type(), 1.0 / inputImages.size.toDouble())
            }

            if (!output.empty()) averageImages.add(output)
            cache[cacheKey] = averageImages
        }

        return averageImages
    }

    private fun mergeLongExposure(prefix: String): Pair<List<Mat>, String> {
        log("Long Exposure: Start")

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

        log("Long Exposure: ${if (resultImages.isEmpty()) "Failed" else "Success"}")
        return Pair(
            resultImages,
            "longexposure_" + binding.longexposureAlgorithm.selectedItem.toString()
        )
    }

    private fun mergeHdr(prefix: String): Pair<List<Mat>, String> {
        log("HDR: Start")

        val alignImages = binding.checkBoxAlign.isChecked
        val inputImages = if (alignImages) alignImages(prefix).first else ( cache[prefix] ?: listOf() )
        val output = Mat()

        if (inputImages.size >= 2) {
            val hdrMat = Mat()
            val mergeMertens = createMergeMertens()
            mergeMertens.process(inputImages, hdrMat)

            if (!hdrMat.empty()) {
                hdrMat.convertTo(output, inputImages[0].type(), 255.0)
            }
        }

        log("HDR Exposure: ${if (output.empty()) "Failed" else "Success"}")

        val outputList = if (output.empty()) listOf() else listOf(output)
        return Pair(outputList, "hdr")
    }

    private fun mergePhotos(prefix: String, l: (output: List<Mat>, name: String) -> Unit) {
        val inputImages = cache[prefix]
        if (null == inputImages || inputImages.size < 2) return

        BusyDialog.show(supportFragmentManager, "Merging photos ...")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val merge = binding.spinnerMerge.selectedItemPosition

        runFakeAsync {
            val result: Pair<List<Mat>, String> = when(merge) {
                Settings.MERGE_PANORAMA -> mergePanorama(prefix)
                Settings.MERGE_LONG_EXPOSURE -> mergeLongExposure(prefix)
                Settings.MERGE_HDR -> mergeHdr(prefix)
                Settings.MERGE_ALIGN -> alignImages(prefix)
                else -> Pair(listOf(), "")
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                if (CV_16UC3 == outputImage.type()) {
                    image8BitsPerChannel = Mat()
                    outputImage.convertTo(image8BitsPerChannel, CV_8UC3, ALPHA_16_TO_8)
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

            val outputExtension = settings.outputExtension()

            BusyDialog.show(supportFragmentManager, "Saving")

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
                    cvtColor(outputImage, outputRGB, COLOR_BGR2RGB)

                    var outputDepth = Settings.DEPTH_AUTO

                    if ( Settings.OUTPUT_TYPE_JPEG == settings.outputType
                        || (Settings.OUTPUT_TYPE_PNG == settings.outputType && Settings.DEPTH_8_BITS == settings.pngDepth)
                        || (Settings.OUTPUT_TYPE_TIFF == settings.outputType && Settings.DEPTH_8_BITS == settings.tiffDepth)
                    ) {
                        outputDepth = Settings.DEPTH_8_BITS
                    } else if ( (Settings.OUTPUT_TYPE_PNG == settings.outputType && Settings.DEPTH_16_BITS == settings.pngDepth)
                        || (Settings.OUTPUT_TYPE_TIFF == settings.outputType && Settings.DEPTH_16_BITS == settings.tiffDepth)
                    ) {
                        outputDepth = Settings.DEPTH_16_BITS
                    }

                    File(fileFullPath).parentFile?.mkdirs()

                    val outputParams = MatOfInt()

                    if (Settings.OUTPUT_TYPE_JPEG == settings.outputType) {
                        outputParams.fromArray( IMWRITE_JPEG_QUALITY, settings.jpegQuality )
                    }

                    imwrite(fileFullPath, convertToDepth(outputRGB, outputDepth), outputParams)

                    //copy exif tags
                    firstSourceUri?.let { uri ->
                        ExifTools.copyExif( contentResolver, uri, fileFullPath )
                    }

                    //Add it to gallery
                    val values = ContentValues()
                    @Suppress("DEPRECATION")
                    values.put(MediaStore.Images.Media.DATA, fileFullPath)
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/${outputExtension}")
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

                    showToast("Saved to: ${fileName}")
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

        MaskEditDialog.show(this, images[0], mask) {
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

    private fun onPermissionsAllowed() {
        if (!OpenCVLoader.initDebug()) fatalError("Failed to initialize OpenCV")
        System.loadLibrary("native-lib")

        setContentView(binding.root)

        binding.spinnerMerge.onItemSelectedListener = listenerOnItemSelectedListener
        binding.panoramaProjection.onItemSelectedListener = listenerOnItemSelectedListener
        binding.longexposureAlgorithm.onItemSelectedListener = listenerOnItemSelectedListener

        binding.spinnerMerge.setSelection( if (settings.mergeMode >= binding.spinnerMerge.adapter.count) 0 else settings.mergeMode )
        binding.panoramaProjection.setSelection( if (settings.panoramaProjection >= binding.panoramaProjection.adapter.count) 0 else settings.panoramaProjection )
        binding.longexposureAlgorithm.setSelection( if (settings.longexposureAlgorithm >= binding.longexposureAlgorithm.adapter.count) 0 else settings.longexposureAlgorithm )

        binding.panoramaInpaint.setOnCheckedChangeListener { _, _ -> mergePhotosSmall() }
        binding.checkBoxAlign.setOnCheckedChangeListener { _, _ -> mergePhotosSmall() }
        binding.checkBoxUseMask.setOnCheckedChangeListener { _, _ -> mergePhotosSmall() }
        binding.btnEditMask.setOnClickListener { editMask() }

        if (intent?.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true) {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let { list ->
                val uriList = mutableListOf<Uri>()
                list.forEach { uriList.add( it as Uri ) }
                loadImages( uriList.toList() )
            }
        }
    }
}