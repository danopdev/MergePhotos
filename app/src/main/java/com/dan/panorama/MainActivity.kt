package com.dan.panorama

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dan.panorama.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgcodecs.*
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_stitching.CylindricalWarper
import org.bytedeco.opencv.opencv_stitching.SphericalWarper
import org.bytedeco.opencv.opencv_stitching.PlaneWarper
import org.bytedeco.opencv.opencv_stitching.Stitcher
import java.io.File


class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_OPEN_IMAGES = 2

        const val IMG_WORK_SIZE = 512

        const val PANORAMA_MODE_PLANE = 0
        const val PANORAMA_MODE_CYLINDRICAL = 1
        const val PANORAMA_MODE_SPHERICAL = 2

        const val TMP_FILE = "/storage/emulated/0/Panorama/tmp.png"

        fun matToBitmap(mat: Mat): Bitmap? {
            imwrite(TMP_FILE, mat)
            return BitmapFactory.decodeFile(TMP_FILE)
        }

        fun matToBitmap(mat: UMat): Bitmap? {
            imwrite(TMP_FILE, mat)
            return BitmapFactory.decodeFile(TMP_FILE)
        }

        fun bitmapToMat(bitmap: Bitmap): Mat {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, File(TMP_FILE).outputStream())
            return imread(TMP_FILE)
        }
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

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

    private fun exitApp() {
        setResult(0)
        finish()
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


    private fun loadImage(path: String, small: Boolean): Mat {
        val img = imread(path)
        if (img.empty()) return img

        Log.i("STITCHER", "Load OK: $path")
        if (!small) return img.clone()

        val widthSmall = IMG_WORK_SIZE
        val heightSmall = IMG_WORK_SIZE * img.rows() / img.cols()
        val imgSmall = Mat()
        resize(img, imgSmall, Size(widthSmall, heightSmall))
        return imgSmall
    }

    private fun loadImages( small: Boolean, l: (images: MatVector)->Unit) {
        BusyDialog.show(supportFragmentManager)

        GlobalScope.launch(Dispatchers.IO) {
            val images = MatVector()
            for (i in 1..5) {
                val img = loadImage("/storage/emulated/0/Panorama/$i.jpg", small)
                if (!img.empty()) {
                    images.push_back(img)
                }
            }

            runOnUiThread {
                BusyDialog.dismiss()
                l.invoke(images)
            }
        }
    }

    private fun makePanorama( images: MatVector, mode: Int, l: (panorama: Bitmap?)->Unit ) {
        BusyDialog.show(supportFragmentManager)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        GlobalScope.launch(Dispatchers.IO) {
            Log.i("STITCHER", "Start")
            val panoramaMat = Mat()
            val stitcher = Stitcher.create(Stitcher.PANORAMA)

            when(mode) {
                PANORAMA_MODE_CYLINDRICAL -> stitcher.setWarper(CylindricalWarper())
                PANORAMA_MODE_SPHERICAL -> stitcher.setWarper(SphericalWarper())
                else -> stitcher.setWarper(PlaneWarper())
            }

            val status = stitcher.stitch(images, panoramaMat)
            var panorama: Bitmap? = null

            if (status == Stitcher.OK) {
                Log.i("STITCHER", "Success")
                panorama = matToBitmap(panoramaMat)
            } else {
                Log.i("STITCHER", "Failed")
            }

            runOnUiThread {
                BusyDialog.dismiss()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                l.invoke(panorama)
            }
        }
    }

    private fun onPermissionsAllowed() {
        setContentView(mBinding.root)

        setUseOpenCL(false)

        loadImages(true) { images ->
            makePanorama(images, PANORAMA_MODE_CYLINDRICAL) { panorama ->
                mBinding.imageView.setImageBitmap(panorama)
            }
        }
    }

    private fun measureTime(msg: String, l: ()->Unit) {
        val startTime = System.currentTimeMillis()
        l.invoke()
        val endTime = System.currentTimeMillis()
        Log.i("STITCHER", "$msg: ${(endTime - startTime).toDouble() / 1000.0} second(s)")
    }
}