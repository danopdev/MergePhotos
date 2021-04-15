package com.dan.panorama

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
import androidx.documentfile.provider.DocumentFile
import com.dan.panorama.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs.imwrite
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.resize
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

        //const val PROJECTION_PLANE = 0
        //const val PROJECTION_CYLINDRICAL = 1
        //const val PROJECTION_SPHERICAL = 2

        fun makePanorama(images: List<Mat>, panorama: Mat, projection: Int): Boolean {
            val images_mat = Converters.vector_Mat_to_Mat(images)
            return makePanoramaNative(images_mat.nativeObj, panorama.nativeObj, projection)
        }

        external fun makePanoramaNative(images: Long, panorama: Long, projection: Int): Boolean
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mSettings: Settings by lazy { Settings(this) }
    private val mImages = mutableListOf<Mat>()
    private val mImagesSmall = mutableListOf<Mat>()
    private var mOutputName = Settings.PANORAMA_DEFAULT_NAME

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

            R.id.savePanorama -> {
                makePanoramaBig()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && requestCode == INTENT_OPEN_IMAGES) {
            imagesClear()
            mOutputName = Settings.PANORAMA_DEFAULT_NAME
            BusyDialog.show(supportFragmentManager, "Loading images")

            runFakeAsync {
                data?.clipData?.let { clipData ->
                    var count = clipData.itemCount

                    for (i in 0 until count) {
                        try {
                            if (0 == i) {
                                DocumentFile.fromSingleUri(applicationContext, clipData.getItemAt(i).uri)?.name?.let { name ->
                                    if (name.length > 0) {
                                        val fields = name.split('.')
                                        mOutputName = fields[0] + "_panorama"
                                        Log.i("STITCHER", "Output name: ${mOutputName}")
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
                    makePanoramaSmall()
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
        Log.i("STITCHER", "Load OK")
        if (img.empty()) return

        var widthSmall: Int
        var heightSmall: Int

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

    private fun makePanorama(images: MutableList<Mat>, l: (panorama: Mat) -> Unit) {
        if (images.size <= 1) {
            showNotEnoughImagesToast()
            l.invoke(Mat())
            return
        }

        BusyDialog.show(supportFragmentManager, "Stitching")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val projection = mBinding.spinnerProjection.selectedItemPosition

        runFakeAsync {
            val panorama = Mat()
            Log.i("STITCHER", "Start")

            if (Companion.makePanorama(images.toList(), panorama, projection)) {
                Log.i("STITCHER", "Success")
            } else {
                Log.i("STITCHER", "Failed")
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            l.invoke(panorama)
            BusyDialog.dismiss()
        }
    }

    private fun makePanoramaSmall() {
        makePanorama(mImagesSmall) { panorama ->
            if (panorama.empty()) {
                setBitmap(null)
            } else {
                val bitmap = Bitmap.createBitmap(panorama.cols(), panorama.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(panorama, bitmap)
                setBitmap(bitmap)
            }
        }
    }

    private fun makePanoramaBig() {
        makePanorama(mImages) { panorama ->
            BusyDialog.show(supportFragmentManager, "Saving")

            try {
                var fileName = mOutputName + ".png"
                var fileFullPath = Settings.SAVE_FOLDER + "/" + fileName
                var counter = 0
                while (File(fileFullPath).exists() && counter < 998) {
                    counter++
                    fileName = mOutputName + "_%03d".format(counter) + ".png"
                    fileFullPath = Settings.SAVE_FOLDER + "/" + fileName
                }

                var panoramaRGB = Mat()
                Imgproc.cvtColor(panorama, panoramaRGB, Imgproc.COLOR_BGR2RGB)

                File(fileFullPath).parentFile?.mkdirs()
                imwrite(fileFullPath, panoramaRGB)
                showToast("Saved to: ${fileName}")

                //Add the panorama to gallery
                val values = ContentValues()
                @Suppress("DEPRECATION")
                values.put(MediaStore.Images.Media.DATA, fileFullPath)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                showToast("Failed to save panorama")
            }

            panorama.release()
            BusyDialog.dismiss()
        }
    }

    private fun setBitmap(bitmap: Bitmap?) {
        if (null != bitmap) {
            mBinding.imageView.setImageBitmap(bitmap)
        } else {
            showToast("Failed to create panorama")
            mBinding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        mBinding.imageView.resetZoom()
    }

    private fun onPermissionsAllowed() {
        if (!OpenCVLoader.initDebug()) fatalError("Failed to initialize OpenCV")
        System.loadLibrary("native-lib")

        setContentView(mBinding.root)

        try {
            mBinding.spinnerProjection.setSelection(mSettings.panoramaMode)
        } catch (e: Exception) {
        }

        mBinding.spinnerProjection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (mImages.size >= 2) {
                    makePanoramaSmall()
                }

                mSettings.panoramaMode = mBinding.spinnerProjection.selectedItemPosition
                mSettings.saveProperties()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }
    }
}