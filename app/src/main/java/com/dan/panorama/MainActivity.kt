package com.dan.panorama

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dan.panorama.databinding.ActivityMainBinding
import org.bytedeco.opencv.global.opencv_imgcodecs.*
import org.bytedeco.opencv.global.opencv_imgproc.WARP_FILL_OUTLIERS
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_stitching.CylindricalWarper
import org.bytedeco.opencv.opencv_stitching.Stitcher
import org.bytedeco.opencv.opencv_stitching.WarperCreator
import org.opencv.core.CvType


class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_OPEN_IMAGES = 2

        const val IMG_WORK_SIZE = 256

        fun matToBitmap(mat: Mat): Bitmap? {
            if (CvType.CV_8UC3 != mat.type()) return null

            val width = mat.cols()
            val height = mat.rows()
            val pixels = IntArray(width * height )
            var pixelIndex = 0
            var srcIndex: Long
            val matArrayData = mat.arrayData()
            val matArrayLineLength = mat.arrayStep()

            for (line in 0 until height) {
                srcIndex = matArrayLineLength * line
                for (column in 0 until width) {
                    pixels[pixelIndex] = Color.rgb(
                            matArrayData.getInt(srcIndex+2),
                            matArrayData.getInt(srcIndex+1),
                            matArrayData.getInt(srcIndex) )
                    srcIndex += 3L
                    pixelIndex++
                }
            }

            return Bitmap.createBitmap( pixels, mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888 )
        }

        fun bitmapToMat(bitmap: Bitmap): Mat {
            val width = bitmap.width
            val height = bitmap.height
            val mat = Mat(height, width, CvType.CV_8UC3)
            var matIndex: Long
            val matArrayData = mat.arrayData()
            val matArrayLineLength = mat.arrayStep()

            for (line in 0 until height) {
                matIndex = matArrayLineLength * line
                for (column in 0 until width) {
                    val pixelColor = bitmap.getPixel(column, line)
                    matArrayData.putInt(matIndex, Color.blue(pixelColor))
                    matArrayData.putInt(matIndex+1, Color.green(pixelColor))
                    matArrayData.putInt(matIndex+2, Color.red(pixelColor))
                    matIndex += 3L
                }
            }

            return mat
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

    private fun onPermissionsAllowed() {
        setContentView(mBinding.root)

        /*
        val images = MatVector()

        for (i in 1..5) {
            val img = imread("/storage/emulated/0/Panorama/$i.jpg")
            if (img.empty()) {
                Log.i("STITCHER", "Load FAILED: $i")
                continue
            }

            Log.i("STITCHER", "Load OK: $i")
            val widthSmall = IMG_WORK_SIZE
            val heightSmall = IMG_WORK_SIZE * img.rows() / img.cols()

            val imgSmall = Mat()
            resize(img, imgSmall, Size(widthSmall, heightSmall))
            Log.i("STITCHER", "Resized OK: $i")
            images.push_back(imgSmall)
        }

        Log.i("STITCHER", "Start")
        val panorama = Mat()
        val stitcher = Stitcher.create(Stitcher.PANORAMA)
        stitcher.setWarper(CylindricalWarper())
        stitcher.setInterpolationFlags(WARP_FILL_OUTLIERS)
        val status = stitcher.stitch(images, panorama)

        if (status == Stitcher.OK) {
            Log.i("STITCHER", "Success")
            matToBitmap(panorama)?.let { bitmap ->
                mBinding.imageView.setImageBitmap(bitmap)
            }
        } else {
            Log.i("STITCHER", "Failed")
        }
         */
    }
}