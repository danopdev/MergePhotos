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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bytedeco.opencv.global.opencv_core.CV_8UC3
import org.bytedeco.opencv.global.opencv_imgcodecs.*
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_stitching.CylindricalWarper
import org.bytedeco.opencv.opencv_stitching.SphericalWarper
import org.bytedeco.opencv.opencv_stitching.PlaneWarper
import org.bytedeco.opencv.opencv_stitching.Stitcher


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

        fun matToBitmap(mat: Mat): Bitmap? {
            if (CV_8UC3 != mat.type()) return null

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
                    pixels[pixelIndex] = Color.rgb( //Mat is in BGR format
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
            val mat = Mat(height, width, CV_8UC3)
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

    private fun loadImage(path: String, small: Boolean): Mat {
        val img = imread(path)
        if (img.empty()) return img

        Log.i("STITCHER", "Load OK: $path")
        if (!small) return img

        val widthSmall = IMG_WORK_SIZE
        val heightSmall = IMG_WORK_SIZE * img.rows() / img.cols()
        val imgSmall = Mat()
        resize(img, imgSmall, Size(widthSmall, heightSmall))
        return imgSmall
    }

    private fun loadImages( small: Boolean, l: (images: MatVector)->Unit) {
        BusyDialog.show(supportFragmentManager)

        GlobalScope.launch(Dispatchers.Default) {
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

        GlobalScope.launch(Dispatchers.Default) {
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
                l.invoke(panorama)
            }
        }
    }

    private fun loadImageGpu(path: String, small: Boolean): GpuMat? {
        val img = imread(path)
        if (img.empty()) return null

        Log.i("STITCHER", "Load OK: $path")
        val imgGpu = GpuMat(img)
        if (!small) return imgGpu

        val widthSmall = IMG_WORK_SIZE
        val heightSmall = IMG_WORK_SIZE * img.rows() / img.cols()
        val imgSmallGpu = GpuMat()
        resize(imgGpu, imgSmallGpu, Size(widthSmall, heightSmall))
        return imgSmallGpu
    }

    private fun loadImagesGpu( small: Boolean, l: (images: GpuMatVector)->Unit) {
        BusyDialog.show(supportFragmentManager)

        GlobalScope.launch(Dispatchers.Default) {
            val images = GpuMatVector()
            for (i in 1..5) {
                loadImageGpu("/storage/emulated/0/Panorama/$i.jpg", small)?.let{ imgGpu ->
                    if (!imgGpu.empty()) {
                        images.push_back(imgGpu)
                    }
                }
            }

            runOnUiThread {
                BusyDialog.dismiss()
                l.invoke(images)
            }
        }
    }

    private fun makePanoramaGpu( images: GpuMatVector, mode: Int, l: (panorama: Bitmap?)->Unit ) {
        BusyDialog.show(supportFragmentManager)

        GlobalScope.launch(Dispatchers.Default) {
            Log.i("STITCHER", "Start")
            val panoramaMat = GpuMat()
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
                panorama = matToBitmap(Mat(panoramaMat))
            } else {
                Log.i("STITCHER", "Failed")
            }

            runOnUiThread {
                BusyDialog.dismiss()
                l.invoke(panorama)
            }
        }
    }

    private fun onPermissionsAllowed() {
        setContentView(mBinding.root)

        val start = System.currentTimeMillis()

        loadImages(true) { images ->
            makePanorama(images, PANORAMA_MODE_PLANE) { panorama ->
                val end = System.currentTimeMillis()
                Log.i("STITCHER", "Time: ${(end - start).toDouble() / 1000.0} second(s)")
                mBinding.imageView.setImageBitmap(panorama)
            }
        }

        /*
        loadImagesGpu(true) { images ->
            makePanoramaGpu(images, PANORAMA_MODE_PLANE) { panorama ->
                val end = System.currentTimeMillis()
                Log.i("STITCHER", "GPU Time: ${(end - start).toDouble() / 1000.0} second(s)")
                mBinding.imageView.setImageBitmap(panorama)
            }
        }
        */
    }
}