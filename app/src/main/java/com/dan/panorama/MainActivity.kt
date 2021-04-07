package com.dan.panorama

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.dan.panorama.databinding.ActivityMainBinding
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgcodecs.*
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_stitching.CylindricalWarper
import org.bytedeco.opencv.opencv_stitching.SphericalWarper
import org.bytedeco.opencv.opencv_stitching.PlaneWarper
import org.bytedeco.opencv.opencv_stitching.Stitcher
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
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mSettings: Settings by lazy { Settings(this) }
    private val mImages = MatVector()
    private val mImagesSmall = MatVector()
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
                                        Log.i("STITCHER","Output name: ${mOutputName}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }

                        try {
                            contentResolver.openInputStream(clipData.getItemAt(i).uri)?.let { inputStream ->
                                BitmapFactory.decodeStream( inputStream )?.let{ bitmap ->
                                    imageAppend(bitmap)
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }

                if (mImages.size() < 2) {
                    imagesClear()
                    showNotEnoughImagesToast()
                } else {
                    makePanoramaSmall()
                }
                BusyDialog.dismiss()
            }
        }
    }

    private fun runFakeAsync(l: ()->Unit) {
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

    private fun matToBitmap(mat: Mat): Bitmap? {
        var bitmap: Bitmap? = null
        val tmpFile = File(cacheDir, Settings.TMP_FILE_NAME)
        val tmpAbsolutePath = tmpFile.absolutePath

        if (mat.empty()) return null

        try {
            imwrite(tmpAbsolutePath, mat)
            bitmap = BitmapFactory.decodeFile(tmpAbsolutePath)
        } catch (e: Exception) {
        }

        try {
            tmpFile.delete()
        } catch (e: Exception) {
        }

        return bitmap
    }

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        var mat: Mat? = null
        val tmpFile = File(cacheDir, Settings.TMP_FILE_NAME)
        val tmpAbsolutePath = tmpFile.absolutePath

        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, tmpFile.outputStream())
            mat = imread(tmpAbsolutePath)
        } catch (e: Exception) {
        }

        try {
            tmpFile.delete()
        } catch (e: Exception) {
        }

        return mat ?: Mat()
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
        else exitApp()
    }

    private fun imagesClear() {
        mImages.clear()
        mImagesSmall.clear()
    }

    private fun imageAppend(bitmap: Bitmap) {
        val img = bitmapToMat(bitmap)
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
        resize(img, imgSmall, Size(widthSmall, heightSmall))

        img.addref()
        mImages.push_back(img)
        mImagesSmall.push_back(imgSmall)
    }

    private fun makePanorama( images: MatVector, l: (panorama: Mat)->Unit ) {
        if (images.size() <= 1) {
            showNotEnoughImagesToast()
            l.invoke(Mat())
            return
        }

        BusyDialog.show(supportFragmentManager, "Stitching")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val projection = mBinding.spinnerProjection.selectedItemPosition

        runFakeAsync {
            Log.i("STITCHER", "Start")
            val panorama = Mat()
            val stitcher = Stitcher.create(Stitcher.PANORAMA)

            when(projection) {
                Settings.PANORAMA_MODE_CYLINDRICAL -> stitcher.setWarper(CylindricalWarper())
                Settings.PANORAMA_MODE_SPHERICAL -> stitcher.setWarper(SphericalWarper())
                else -> stitcher.setWarper(PlaneWarper())
            }

            val status = stitcher.stitch(images, panorama)

            if (status == Stitcher.OK) {
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
            setBitmap(matToBitmap(panorama))
            panorama.release()
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

                File(fileFullPath).parentFile?.mkdirs()
                imwrite(fileFullPath, panorama)
                showToast("Saved to: ${fileName}")
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
        setUseOpenCL(false)
        setContentView(mBinding.root)

        try {
            mBinding.spinnerProjection.setSelection(mSettings.panoramaMode)
        } catch (e: Exception) {
        }

        mBinding.spinnerProjection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (mImages.size() >= 2) {
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