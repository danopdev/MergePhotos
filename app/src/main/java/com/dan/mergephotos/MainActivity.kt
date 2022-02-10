package com.dan.mergephotos

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.SeekBar
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
import org.opencv.calib3d.Calib3d.RANSAC
import org.opencv.calib3d.Calib3d.findHomography
import org.opencv.core.*
import org.opencv.core.Core.*
import org.opencv.core.CvType.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs.*
import org.opencv.imgproc.Imgproc.*
import org.opencv.photo.Photo.createMergeMertens
import org.opencv.utils.Converters
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
        const val CACHE_IMAGES_AVERAGE_SUFFIX = ".Average"

        private fun log(msg: String) {
            Log.i("MERGE", msg)
        }

        fun makePanorama(images: List<Mat>, panorama: Mat, projection: Int): Boolean {
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            return makePanoramaNative(imagesMat.nativeObj, panorama.nativeObj, projection)
        }

        fun makeLongExposureMergeWithDistance(
            images: List<Mat>,
            averageImage: Mat,
            outputImage: Mat,
            farthestThreshold: Int
        ): Boolean {
            if (images.size < 3) return false
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            return makeLongExposureMergeWithDistanceNative(
                imagesMat.nativeObj,
                averageImage.nativeObj,
                outputImage.nativeObj,
                farthestThreshold
            )
        }

        external fun makePanoramaNative(images: Long, panorama: Long, projection: Int): Boolean
        external fun makeLongExposureMergeWithDistanceNative(
            images: Long,
            averageImage: Long,
            outputImage: Long,
            farthestThreshold: Int
        ): Boolean
    }

    val settings: Settings by lazy { Settings(this) }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val cache = mutableMapOf<String, MutableList<Mat>>()
    private var outputName = Settings.DEFAULT_NAME

    private val listenerOnItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
            when(parent) {
                binding.spinnerMerge -> {
                    binding.panoramaOptions.isVisible = Settings.MERGE_PANORAMA == position
                    binding.longexposureOptions.isVisible = Settings.MERGE_LONG_EXPOSURE == position
                }

                binding.longexposureAlgorithm -> {
                    binding.longexposureFarthestThreshold.isVisible =
                        binding.longexposureAlgorithm.selectedItemPosition == Settings.LONG_EXPOSURE_FARTHEST_FROM_AVERAGE
                }
            }

            mergePhotosSmall()
        }

        override fun onNothingSelected(parent: AdapterView<*>) {
        }
    }

    private val listenerOnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
        }

        override fun onStartTrackingTouch(p0: SeekBar?) {
        }

        override fun onStopTrackingTouch(p0: SeekBar?) {
            mergePhotosSmall()
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
            imagesClear()
            outputName = Settings.DEFAULT_NAME
            BusyDialog.show(supportFragmentManager, "Loading images")

            val imagesBig = mutableListOf<Mat>()
            val imagesSmall = mutableListOf<Mat>()

            runFakeAsync {
                data?.clipData?.let { clipData ->
                    val count = clipData.itemCount

                    for (i in 0 until count) {
                        try {
                            if (0 == i) {
                                DocumentFile.fromSingleUri(
                                    applicationContext,
                                    clipData.getItemAt(i).uri
                                )?.name?.let { name ->
                                    if (name.length > 0) {
                                        val fields = name.split('.')
                                        outputName = fields[0]
                                        log("Output name: ${outputName}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }

                        val image = loadImage(clipData.getItemAt(i).uri) ?: continue
                        imagesBig.add(image)
                        imagesSmall.add(createSmallImage(image))
                    }
                }

                if (imagesBig.size < 2) {
                    showNotEnoughImagesToast()
                } else {
                    cache[CACHE_IMAGES] = imagesBig
                    cache[CACHE_IMAGES_SMALL] = imagesSmall
                    mergePhotosSmall()
                }

               BusyDialog.dismiss()
            }
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

    private fun createSmallImage(image: Mat) : Mat {
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
        resize(image, imageSmall, Size(widthSmall.toDouble(), heightSmall.toDouble()))
        return imageSmall
    }

    private fun loadImage(uri: Uri) : Mat? {
        // Can't create MatOfByte from kotlin ByteArray, but works correctly from java byte[]
        val image = OpenCVLoadImageFromUri.load(uri, contentResolver) ?: return null
        if (image.empty()) return null

        var imageBGA = Mat()

        when(image.type()) {
            CV_8UC3, CV_16UC3 -> imageBGA = image
            CV_8UC4, CV_16UC4 -> cvtColor(image, imageBGA, COLOR_BGRA2BGR)
            else -> return null
        }

        val imageRGB = Mat()
        cvtColor(imageBGA, imageRGB, COLOR_BGR2RGB)
        return imageRGB
    }

    private fun mergePanorama(prefix: String): Pair<List<Mat>, String> {
        log("Panorama: Start")
        val mode = binding.panoramaProjection.selectedItemPosition
        val inputImages = cache[prefix] ?: return Pair(listOf(), "")
        val output = Mat()
        makePanorama(inputImages.toList(), output, mode)
        log("Panorama: ${if (output.empty()) "Failed" else "Success"}")
        val outputList = if (output.empty()) listOf() else listOf(output)
        return Pair(outputList, "panorama_" + binding.panoramaProjection.selectedItem.toString())
    }

    private fun toGrayImage(image: Mat): Mat {
        val grayMat = Mat()
        cvtColor(image, grayMat, COLOR_BGR2GRAY)

        if (CV_16UC1 == grayMat.type()) {
            val grayMat8 = Mat()
            grayMat.convertTo(grayMat8, CV_8UC1, 1.0 / 256.0)
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

    private fun orbDetectAndCompute(orbDetector: ORB, image: Mat): Pair<MutableList<KeyPoint>, Mat> {
        val normalizedImage = toNormalizedImage(image)
        val keyPoints = MatOfKeyPoint()
        val descriptors = Mat()
        orbDetector.detectAndCompute(normalizedImage, Mat(), keyPoints, descriptors)
        return Pair(keyPoints.toList(), descriptors)
    }

    private fun alignImages(prefix: String): Pair<List<Mat>, String> {
        val inputImages = cache[prefix] ?: mutableListOf()

        log("Align: Start")

        var alignedImages = cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX]
        if (null == alignedImages) {
            alignedImages = mutableListOf()

            alignedImages.add(inputImages[0])
            val orbDetector = ORB.create()
            val matcher = BFMatcher.create(NORM_HAMMING, true)
            val refInfo = orbDetectAndCompute(orbDetector, inputImages[0])
            val refKeyPoints = refInfo.first
            val refDescriptors = refInfo.second

            for (imageIndex in 1 until inputImages.size) {
                val info = orbDetectAndCompute(orbDetector, inputImages[imageIndex])
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

                val homography = findHomography(matListPoints, matListRefPoints, RANSAC)
                val alignedImage = Mat()
                warpPerspective(
                    inputImages[imageIndex], alignedImage, homography, Size(
                        inputImages[0].cols().toDouble(), inputImages[0].rows().toDouble()
                    )
                )
                if (!alignedImage.empty()) alignedImages.add(alignedImage)
            }

            cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX] = alignedImages
        }

        log("Align: End")

        if (alignedImages.size < 2) {
            Toast.makeText(applicationContext, "Failed to align images !", Toast.LENGTH_LONG).show()
        }

        return Pair(alignedImages, "align")
    }

    private fun calculateAverage(prefix: String): List<Mat> {
        var averageImages = cache[prefix + CACHE_IMAGES_AVERAGE_SUFFIX]

        if (null == averageImages) {
            averageImages = mutableListOf()
            val alignedImages = alignImages(prefix).first
            val output = Mat()

            if (alignedImages.size >= 2) {
                val floatMat = Mat()
                alignedImages[0].convertTo(floatMat, CV_32FC3)

                for (imageIndex in 1 until alignedImages.size) {
                    add(floatMat, alignedImages[imageIndex], floatMat, Mat(), CV_32FC3)
                }

                val multiplyValue = 1.0 / alignedImages.size.toDouble()
                multiply(floatMat, Scalar(multiplyValue, multiplyValue, multiplyValue), floatMat)

                floatMat.convertTo(output, CV_8UC3)
            }

            if (!output.empty()) averageImages.add(output)
            cache[prefix + CACHE_IMAGES_AVERAGE_SUFFIX] = averageImages
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

            Settings.LONG_EXPOSURE_NEAREST_TO_AVERAGE, Settings.LONG_EXPOSURE_FARTHEST_FROM_AVERAGE -> {
                if (!averageImages.isEmpty()) {
                    val alignedImages = cache[prefix + CACHE_IMAGES_ALIGNED_SUFFIX]
                    if (null != alignedImages) {
                        val outputImage = Mat()
                        val farthestThreshold =
                            if (Settings.LONG_EXPOSURE_NEAREST_TO_AVERAGE == mode) -1 else binding.longexposureFarthestThreshold.progress

                        if (makeLongExposureMergeWithDistance(
                                alignedImages,
                                averageImages[0],
                                outputImage,
                                farthestThreshold
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

        val alignedImages = alignImages(prefix).first
        val output = Mat()

        if (alignedImages.size >= 2) {
            val hdrMat = Mat()
            val mergeMertens = createMergeMertens()
            mergeMertens.process(alignedImages, hdrMat)

            if (!hdrMat.empty()) {
                multiply(hdrMat, Scalar(255.0, 255.0, 255.0), hdrMat)
                hdrMat.convertTo(output, CV_8UC3)
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
                var image8BitsPerChannel: Mat
                if (CV_16UC3 == outputImage.type()) {
                    image8BitsPerChannel = Mat()
                    outputImage.convertTo(image8BitsPerChannel, CV_8UC3,1.0 / 256.0)
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

                    var outputRGB = Mat()
                    cvtColor(outputImage, outputRGB, COLOR_BGR2RGB)

                    val support16BitsPerChannel = (Settings.OUTPUT_TYPE_PNG == settings.outputType || Settings.OUTPUT_TYPE_TIFF == settings.outputType)
                    if (!support16BitsPerChannel && outputRGB.type() == CV_16UC3) {
                        val outputRGB8 = Mat()
                        outputRGB.convertTo(outputRGB8, CV_8UC3, 1.0 / 256.0)
                        outputRGB = outputRGB8
                    }

                    File(fileFullPath).parentFile?.mkdirs()

                    val outputParams = MatOfInt()

                    if (Settings.OUTPUT_TYPE_JPEG == settings.outputType) {
                        outputParams.fromArray( IMWRITE_JPEG_QUALITY, settings.jpegQuality )
                    }

                    imwrite(fileFullPath, outputRGB, outputParams)
                    showToast("Saved to: ${fileName}")

                    //Add it to gallery
                    val values = ContentValues()
                    @Suppress("DEPRECATION")
                    values.put(MediaStore.Images.Media.DATA, fileFullPath)
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/${outputExtension}")
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } catch (e: Exception) {
                    showToast("Failed to save")
                }
            }
            BusyDialog.dismiss()
        }
    }

    private fun setBitmap(bitmap: Bitmap?) {
        if (null != bitmap) {
            binding.imageView.setImageBitmap(bitmap)
        } else {
            showToast("Failed to merge photos")
            binding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        binding.imageView.resetZoom()
    }

    private fun onPermissionsAllowed() {
        if (!OpenCVLoader.initDebug()) fatalError("Failed to initialize OpenCV")
        System.loadLibrary("native-lib")

        setContentView(binding.root)

        binding.spinnerMerge.onItemSelectedListener = listenerOnItemSelectedListener
        binding.panoramaProjection.onItemSelectedListener = listenerOnItemSelectedListener
        binding.longexposureAlgorithm.onItemSelectedListener = listenerOnItemSelectedListener
        binding.longexposureFarthestThreshold.setOnSeekBarChangeListener(
            listenerOnSeekBarChangeListener
        )

        binding.spinnerMerge.setSelection(settings.mergeMode)
        binding.panoramaProjection.setSelection(settings.panoramaProjection)
        binding.longexposureAlgorithm.setSelection(settings.longexposureAlgorithm)
    }
}