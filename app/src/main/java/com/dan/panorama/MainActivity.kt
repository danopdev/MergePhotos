package com.dan.panorama

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Toast
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

        const val IMG_SIZE_SMALL = 512

        const val PANORAMA_MODE_PLANE = 0
        const val PANORAMA_MODE_CYLINDRICAL = 1
        const val PANORAMA_MODE_SPHERICAL = 2

        const val TMP_FILE_NAME = "tmp.png"

    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mImages = MatVector()
    private val mImagesSmall = MatVector()

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

    private fun exitApp() {
        setResult(0)
        finish()
    }

    private fun matToBitmap(mat: Mat): Bitmap? {
        var bitmap: Bitmap? = null
        val tmpFile = File(cacheDir, TMP_FILE_NAME)
        val tmpAbsolutePath = tmpFile.absolutePath

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
        val tmpFile = File(cacheDir, TMP_FILE_NAME)
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


    private fun loadImage(path: String): Pair<Mat, Mat> {
        val img = imread(path)
        if (img.empty()) return Pair(img, img)

        Log.i("STITCHER", "Load OK: $path")

        var widthSmall: Int
        var heightSmall: Int

        if (img.rows() < img.cols()) {
            widthSmall = IMG_SIZE_SMALL
            heightSmall = IMG_SIZE_SMALL * img.rows() / img.cols()
        } else {
            widthSmall = IMG_SIZE_SMALL * img.cols() / img.rows()
            heightSmall = IMG_SIZE_SMALL
        }

        val imgSmall = Mat()
        resize(img, imgSmall, Size(widthSmall, heightSmall))
        return Pair(img.clone(), imgSmall)
    }

    private fun loadImages(l: ()->Unit) {
        BusyDialog.show(supportFragmentManager)

        mImages.clear()
        mImagesSmall.clear()

        GlobalScope.launch(Dispatchers.IO) {
            for (i in 1..5) {
                val both = loadImage("/storage/emulated/0/Panorama/$i.jpg")
                if (!both.first.empty() && !both.second.empty()) {
                    mImages.push_back(both.first)
                    mImagesSmall.push_back(both.second)
                }
            }

            runOnUiThread {
                BusyDialog.dismiss()
                l.invoke()
            }
        }
    }

    private fun makePanorama( images: MatVector, l: (panorama: Bitmap?)->Unit ) {
        if (images.size() <= 1) {
            Toast.makeText(applicationContext, "You must have minimum 2 images !", Toast.LENGTH_LONG).show()
            l.invoke(null)
            return
        }

        BusyDialog.show(supportFragmentManager)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val projection = mBinding.spinnerProjection.selectedItemPosition

        GlobalScope.launch(Dispatchers.IO) {
            Log.i("STITCHER", "Start")
            val panoramaMat = Mat()
            val stitcher = Stitcher.create(Stitcher.PANORAMA)

            when(projection) {
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

    private fun makePanoramaSmall() {
        makePanorama(mImagesSmall) { panorama ->
            setBitamp(panorama)
        }
    }

    private fun setBitamp(bitamp: Bitmap?) {
        if (null != bitamp) {
            mBinding.imageView.setImageBitmap(bitamp)
        } else {
            mBinding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        mBinding.imageView.resetZoom()
    }

    private fun onPermissionsAllowed() {
        setContentView(mBinding.root)

        setUseOpenCL(false)

        mBinding.spinnerProjection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (mImages.size() >= 2) {
                    makePanoramaSmall()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        loadImages() {
            makePanoramaSmall()
        }
    }
}