package com.dan.mergephotos

import android.graphics.*
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.*
import androidx.fragment.app.DialogFragment
import com.dan.mergephotos.databinding.MaskEditFragmentBinding
import org.opencv.android.Utils
import org.opencv.core.Core.*
import org.opencv.core.CvType
import org.opencv.core.Mat


class MaskEditFragment(private val activity: MainActivity, image: Mat, private val mask: Mat, private val onOKListener: ()->Unit ) : DialogFragment() {

    companion object {
        private const val RADIUS = 50f //dp

        fun show(activity: MainActivity, image: Mat, mask: Mat, onOKListener: ()->Unit ) {
            activity.pushView( "Edit Mask", MaskEditFragment( activity, image, mask, onOKListener ) )
        }

        private fun matToBitmap( image: Mat, bitmap: Bitmap ) {
            val image8: Mat
            if (CvType.CV_16UC3 == image.type()) {
                image8 = Mat()
                image.convertTo(image8, CvType.CV_8UC3, MainFragment.ALPHA_16_TO_8)
            } else if (CvType.CV_8UC1 == image.type()) {
                image8 = Mat()
                merge(listOf(image, image, image), image8)
            } else {
                image8 = image
            }

            Utils.matToBitmap(image8, bitmap)
        }
    }

    private lateinit var binding: MaskEditFragmentBinding
    private var fromX = -1f
    private var fromY = -1f
    private val paint = Paint()

    private val touchImageViewListenerNone = object : TouchImageViewListener {
        override fun onViewRectChanged(rect: RectF) {
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return true
        }
    }

    private val touchImageViewListenerDrawMask = object : TouchImageViewListener {
        override fun onViewRectChanged(rect: RectF) {
            binding.imageView.viewRect = rect
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!binding.buttonDraw.isPressed && !binding.buttonErase.isPressed) return false

            if (MotionEvent.ACTION_DOWN != event.action && MotionEvent.ACTION_MOVE != event.action) {
                fromX = -1f
                fromY = -1f
                return true
            }

            val draw = binding.buttonDraw.isPressed
            val color = if (draw) 255 else 0
            val viewRect = binding.imageView.viewRect
            val scale = image.cols() / viewRect.width()
            val toX = (event.x - viewRect.left) * scale
            val toY = (event.y - viewRect.top) * scale
            val radius = convertDpToPixel(RADIUS) * scale

            if (fromX < 0 || fromY < 0) {
                fromX = toX
                fromY = toY
            }

            drawMask { canvas ->
                paint.color = Color.rgb(color, color, color)
                paint.strokeWidth = radius
                canvas.drawLine(fromX, fromY, toX, toY, paint)
            }

            fromX = toX
            fromY = toY

            return true
        }
    }

    private val imageBitmap: Bitmap = Bitmap.createBitmap(
        image.cols(),
        image.rows(),
        Bitmap.Config.ARGB_8888
    )

    private val maskBitmap: Bitmap = Bitmap.createBitmap(
        image.cols(),
        image.rows(),
        Bitmap.Config.ARGB_8888
    )

    init {
        matToBitmap(image, imageBitmap)
        if (!mask.empty()) {
            matToBitmap(mask, maskBitmap)
        } else {
            matToBitmap(Mat.zeros(image.rows(), image.cols(), CvType.CV_8UC1), maskBitmap)
        }

        paint.isAntiAlias = false
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun convertDpToPixel(dp: Float): Float {
        val context = this.context ?: return dp
        return dp * (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
    }

    private fun drawMask(callback:(canvas: Canvas)->Unit) {
        val canvas = Canvas(maskBitmap)
        callback(canvas)
        binding.maskView.invalidate()
    }

    private fun setMask(value: Int) {
        drawMask { canvas ->
            canvas.drawARGB(255, value, value, value)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.ok_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.ok -> {
                val maskMat = Mat()
                Utils.bitmapToMat(maskBitmap, maskMat)
                val channels = mutableListOf<Mat>()
                split(maskMat, channels)
                val maskChannel = channels[0]

                if (mask.empty()) mask.create(maskChannel.rows(), maskChannel.cols(), CvType.CV_8UC1)
                maskChannel.copyTo(mask)

                onOKListener.invoke()

                activity.popView()

                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = MaskEditFragmentBinding.inflate( inflater )

        binding.imageView.setListener(touchImageViewListenerNone)
        binding.maskView.setListener(touchImageViewListenerDrawMask)

        binding.buttonClear.setOnClickListener{ setMask(0) }
        binding.buttonFill.setOnClickListener{ setMask(255) }

        binding.imageView.setBitmap(imageBitmap)
        binding.maskView.setBitmap(maskBitmap)

        setHasOptionsMenu(true)

        return binding.root
    }
}