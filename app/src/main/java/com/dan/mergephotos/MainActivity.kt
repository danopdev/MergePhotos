package com.dan.mergephotos

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import org.opencv.calib3d.Calib3d.RANSAC
import org.opencv.calib3d.Calib3d.findHomography
import org.opencv.core.*
import org.opencv.core.Core.*
import org.opencv.core.CvType.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs.imwrite
import org.opencv.imgproc.Imgproc
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

        const val MERGE_MODE_PANORAMA = 0
        const val MERGE_MODE_LONG_EXPOSURE = 1
        const val MERGE_MODE_HDR = 2
        const val MERGE_MODE_ALIGN = 3

        private fun log(msg: String) {
            Log.i("MERGE", msg)
        }

        fun makePanorama(images: List<Mat>, panorama: Mat, projection: Int): Boolean {
            val images_mat = Converters.vector_Mat_to_Mat(images)
            return makePanoramaNative(images_mat.nativeObj, panorama.nativeObj, projection)
        }

        external fun makePanoramaNative(images: Long, panorama: Long, projection: Int): Boolean
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mSettings: Settings by lazy { Settings(this) }

    private val mImages = mutableListOf<Mat>()
    //cache alignment
    private val mImagesAligned = mutableListOf<Mat>()
    private var mImagesAlignedDone = false

    private val mImagesSmall = mutableListOf<Mat>()
    //cache alignment
    private val mImagesSmallAligned = mutableListOf<Mat>()
    private var mImagesSmallAlignedDone = false

    private var mOutputName = Settings.DEFAULT_NAME

    init {
        BusyDialog.create(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && requestCode == INTENT_OPEN_IMAGES) {
            imagesClear()
            mOutputName = Settings.DEFAULT_NAME
            BusyDialog.show(supportFragmentManager, "Loading images")

            runFakeAsync {
                data?.clipData?.let { clipData ->
                    val count = clipData.itemCount

                    for (i in 0 until count) {
                        try {
                            if (0 == i) {
                                DocumentFile.fromSingleUri(applicationContext, clipData.getItemAt(i).uri)?.name?.let { name ->
                                    if (name.length > 0) {
                                        val fields = name.split('.')
                                        mOutputName = fields[0]
                                        log("Output name: ${mOutputName}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }

                        try {
                            contentResolver.openInputStream(clipData.getItemAt(i).uri)?.let { inputStream ->
                                BitmapFactory.decodeStream(inputStream)?.let{ bitmap ->
                                    imageAppend(bitmap)
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }

                if (mImages.size < 2) {
                    imagesClear()
                    showNotEnoughImagesToast()
                } else {
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
        mImages.clear()
        mImagesSmall.clear()
        mImagesAligned.clear()
        mImagesSmallAligned.clear()
        mImagesAlignedDone = false
        mImagesSmallAlignedDone = false
    }

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val imgRGBA = Mat()
        Utils.bitmapToMat(bitmap, imgRGBA)
        if (imgRGBA.empty()) return imgRGBA
        val img = Mat()
        Imgproc.cvtColor(imgRGBA, img, Imgproc.COLOR_BGRA2BGR)
        return img
    }

    private fun imageAppend(bitmap: Bitmap) {
        val img = bitmapToMat(bitmap)
        log("Load OK")
        if (img.empty()) return

        val widthSmall: Int
        val heightSmall: Int

        if (img.rows() < img.cols()) {
            widthSmall = Settings.IMG_SIZE_SMALL
            heightSmall = Settings.IMG_SIZE_SMALL * img.rows() / img.cols()
        } else {
            widthSmall = Settings.IMG_SIZE_SMALL * img.cols() / img.rows()
            heightSmall = Settings.IMG_SIZE_SMALL
        }

        val imgSmall = Mat()
        resize(img, imgSmall, Size(widthSmall.toDouble(), heightSmall.toDouble()))

        mImages.add(img)
        mImagesSmall.add(imgSmall)
    }

    private fun mergePanorama(small: Boolean, projection: Int): List<Mat> {
        log("Panorama: Start")
        val inputImages = if (small) mImagesSmall else mImages
        val output = Mat()
        makePanorama(inputImages.toList(), output, projection)
        log("Panorama: ${ if (output.empty()) "Failed" else "Success" }")
        return if (output.empty()) listOf() else listOf(output)
    }

    private fun toGrayImage(image: Mat): Mat {
        val grayMat = Mat()
        cvtColor(image, grayMat, COLOR_BGR2GRAY)
        return grayMat
    }

    private fun toNormalizedImage(image: Mat): Mat {
        val grayMat = toGrayImage(image)
        val normalizedMat = Mat()
        normalize(grayMat, normalizedMat, 0.0, 255.0, NORM_MINMAX)
        return normalizedMat
    }

    private fun orbDetectAndCompute( orbDetector: ORB, image: Mat): Pair<MutableList<KeyPoint>, Mat> {
        val normalizedImage = toNormalizedImage(image)
        val keyPoints = MatOfKeyPoint()
        val descriptors = Mat()
        orbDetector.detectAndCompute(normalizedImage, Mat(), keyPoints, descriptors)
        return Pair(keyPoints.toList(), descriptors)
    }

    private fun alignImages(small: Boolean): MutableList<Mat> {
        log("Align: Start")

        val outputImages = if (small) mImagesSmallAligned else mImagesAligned
        val alreadyAligned = if (small) mImagesSmallAlignedDone else mImagesAlignedDone
        if (alreadyAligned) return outputImages

        val inputImages = if (small) mImagesSmall else mImages

        outputImages.add(inputImages[0])

        val orbDetector = ORB.create(5000)
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

            val homography = findHomography( matListPoints, matListRefPoints, RANSAC )
            val alignedImage = Mat()
            warpPerspective(inputImages[imageIndex], alignedImage, homography, Size(inputImages[0].cols().toDouble(), inputImages[0].rows().toDouble()))
            if (!alignedImage.empty()) outputImages.add(alignedImage)
        }

        log("Align: End")

        if (outputImages.size < 2) {
            Toast.makeText(applicationContext, "Failed to align images !", Toast.LENGTH_LONG).show()
        }

        if (small)
            mImagesSmallAlignedDone = true
        else
            mImagesAlignedDone = true

        return outputImages
    }

    private fun mergeLongExposure(small: Boolean): List<Mat> {
        log("Long Exposure: Start")

        val alignedImages = alignImages(small)
        val output = Mat()

        if (alignedImages.size >= 2) {
            val floatMat = Mat()
            alignedImages[0].convertTo(floatMat, CV_32FC3)

            for (imageIndex in 1 until alignedImages.size) {
                Core.add(floatMat, alignedImages[imageIndex], floatMat, Mat(), CV_32FC3)
            }

            val multiplyValue = 1.0 / alignedImages.size.toDouble()
            Core.multiply(floatMat, Scalar(multiplyValue, multiplyValue,multiplyValue), floatMat)

            floatMat.convertTo(output, CV_8UC3)
        }

        log("Long Exposure: ${ if (output.empty()) "Failed" else "Success" }")
        return if (output.empty()) listOf() else listOf(output)
    }

    private fun mergeHdr(small: Boolean): List<Mat> {
        log("HDR: Start")

        val alignedImages = alignImages(small)
        val output = Mat()

        if (alignedImages.size >= 2) {
            val hdrMat = Mat()
            val mergeMertens = createMergeMertens()
            mergeMertens.process(alignedImages, hdrMat)

            if (!hdrMat.empty()) {
                Core.multiply(hdrMat, Scalar(255.0, 255.0,255.0), hdrMat)
                hdrMat.convertTo(output, CV_8UC3)
            }
        }

        log("HDR Exposure: ${ if (output.empty()) "Failed" else "Success" }")
        return if (output.empty()) listOf() else listOf(output)
    }

    private fun mergePhotos(small: Boolean, l: (output: List<Mat>, name: String) -> Unit) {
        val images = if (small) mImagesSmall else mImages
        if (images.size <= 1) {
            showNotEnoughImagesToast()
            l.invoke(listOf(), "")
            return
        }

        BusyDialog.show(supportFragmentManager, "Merging photos ...")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mergeMode = mBinding.spinnerMergeMode.selectedItemPosition
        val panoramaProjection = mBinding.spinnerProjection.selectedItemPosition

        runFakeAsync {
            var output = listOf<Mat>()
            var name = ""
            when(mergeMode) {
                MERGE_MODE_PANORAMA -> {
                    output = mergePanorama(small, panoramaProjection)
                    name = "panorama_" + mBinding.spinnerProjection.selectedItem.toString().toLowerCase()
                }

                MERGE_MODE_LONG_EXPOSURE -> {
                    output = mergeLongExposure(small)
                    name = "longexposure"
                }

                MERGE_MODE_HDR -> {
                    output = mergeHdr(small)
                    name = "hdr"
                }

                MERGE_MODE_ALIGN -> {
                    output = alignImages(small)
                    name = "aligned"
                }
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            l.invoke(output, name)
            BusyDialog.dismiss()
        }
    }

    private fun mergePhotosSmall() {
        mergePhotos(true) { outputImages, _ ->
            if (outputImages.isEmpty()) {
                setBitmap(null)
            } else {
                val outputImage = outputImages[0]
                val bitmap = Bitmap.createBitmap(outputImage.cols(), outputImage.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(outputImage, bitmap)
                setBitmap(bitmap)
            }
        }
    }

    private fun mergePhotosBig() {
        mergePhotos(false) { outputImages, name ->
            BusyDialog.show(supportFragmentManager, "Saving")

            for (outputImage in outputImages) {
                try {
                    var fileName = "${mOutputName}_${name}.png"
                    var fileFullPath = Settings.SAVE_FOLDER + "/" + fileName
                    var counter = 0
                    while (File(fileFullPath).exists() && counter < 998) {
                        counter++
                        val counterStr = "%03d".format(counter)
                        fileName = "${mOutputName}_${name}_${counterStr}.png"
                        fileFullPath = Settings.SAVE_FOLDER + "/" + fileName
                    }

                    val outputRGB = Mat()
                    Imgproc.cvtColor(outputImage, outputRGB, Imgproc.COLOR_BGR2RGB)

                    File(fileFullPath).parentFile?.mkdirs()
                    imwrite(fileFullPath, outputRGB)
                    showToast("Saved to: ${fileName}")

                    //Add it to gallery
                    val values = ContentValues()
                    @Suppress("DEPRECATION")
                    values.put(MediaStore.Images.Media.DATA, fileFullPath)
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
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
            mBinding.imageView.setImageBitmap(bitmap)
        } else {
            showToast("Failed to merge photos")
            mBinding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        mBinding.imageView.resetZoom()
    }

    private fun showMergeModeParams() {
        mBinding.paramsProjection.isVisible = MERGE_MODE_PANORAMA == mBinding.spinnerMergeMode.selectedItemPosition
    }

    private fun onPermissionsAllowed() {
        if (!OpenCVLoader.initDebug()) fatalError("Failed to initialize OpenCV")
        System.loadLibrary("native-lib")

        setContentView(mBinding.root)

        try {
            mBinding.spinnerProjection.setSelection(mSettings.panoramaMode)
        } catch (e: Exception) {
        }

        showMergeModeParams()

        mBinding.spinnerProjection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (mImages.size >= 2) {
                    mergePhotosSmall()
                }

                mSettings.panoramaMode = mBinding.spinnerProjection.selectedItemPosition
                mSettings.saveProperties()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        mBinding.spinnerMergeMode.onItemSelectedListener = object  : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                showMergeModeParams()

                if (mImages.size >= 2) {
                    mergePhotosSmall()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }
    }
}