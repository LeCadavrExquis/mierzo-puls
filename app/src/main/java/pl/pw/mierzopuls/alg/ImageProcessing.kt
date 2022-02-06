package pl.pw.mierzopuls.alg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter

class ImageProcessing {
    var state = AlgState.START
    var calibration: Calibration? = null

    private var calibrationStart: Long? = null
    private var redAcc: Double = 0.0
    private var probeCounter = 0

    companion object {
        val CALIBRATION_TIME = 3000L
        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e("ImgProc", "Unable to load OpenCV! BE")
                throw InstantiationException("OpenCV not loaded correctly!")
            } else { Log.d("ImgProc","OpenCV library loaded correctly") }
        }
    }

    fun analyse(image: Image): Mat {
        val mat = image.yuvToRgba()
        when (state) {
            AlgState.START -> {
                state = AlgState.CALIBRATION

                calibrationStart = System.currentTimeMillis()
                Log.d("ImgProc", "Calibration start: $calibrationStart")
            }
            AlgState.CALIBRATION -> {
                val meanPixelValue = Core.mean(mat)
                Log.d("ImgProc", """
                        red: ${meanPixelValue.`val`[0]}, 
                        green: ${meanPixelValue.`val`[1]},
                        blue: ${meanPixelValue.`val`[2]}
                    """.trimIndent())

                redAcc += meanPixelValue.`val`[0]
                probeCounter++

                if (System.currentTimeMillis() - calibrationStart!! > CALIBRATION_TIME) {
                    calibration = Calibration(
                        (redAcc/probeCounter), 0.0, 0.0
                    )
                    state = AlgState.ANALYZE
                    Log.d("ImgProc", "Calibration = $calibration")
                }
            }
            AlgState.ANALYZE -> {
                val threshold = calibration!!.getThreshold(10)
                Core.inRange(mat, threshold.first, threshold.second, mat)
            }
        }
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = mat.let { it1 -> Bitmap.createBitmap(it1.cols(), mat.rows(), Bitmap.Config.ARGB_8888) }
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    private fun Image.yuvToRgba(): Mat {
        val rgbaMat = Mat()

        if (format == ImageFormat.YUV_420_888
            && planes.size == 3) {

            val chromaPixelStride = planes[1].pixelStride

            if (chromaPixelStride == 2) { // Chroma channels are interleaved
                assert(planes[0].pixelStride == 1)
                assert(planes[2].pixelStride == 2)
                val yPlane = planes[0].buffer
                val uvPlane1 = planes[1].buffer
                val uvPlane2 = planes[2].buffer
                val yMat = Mat(height, width, CvType.CV_8UC1, yPlane)
                val uvMat1 = Mat(height / 2, width / 2, CvType.CV_8UC2, uvPlane1)
                val uvMat2 = Mat(height / 2, width / 2, CvType.CV_8UC2, uvPlane2)
                val addrDiff = uvMat2.dataAddr() - uvMat1.dataAddr()
                if (addrDiff > 0) {
                    assert(addrDiff == 1L)
                    Imgproc.cvtColorTwoPlane(yMat, uvMat1, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV12)
                } else {
                    assert(addrDiff == -1L)
                    Imgproc.cvtColorTwoPlane(yMat, uvMat2, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)
                }
            } else { // Chroma channels are not interleaved
                val yuvBytes = ByteArray(width * (height + height / 2))
                val yPlane = planes[0].buffer
                val uPlane = planes[1].buffer
                val vPlane = planes[2].buffer

                yPlane.get(yuvBytes, 0, width * height)

                val chromaRowStride = planes[1].rowStride
                val chromaRowPadding = chromaRowStride - width / 2

                var offset = width * height
                if (chromaRowPadding == 0) {
                    // When the row stride of the chroma channels equals their width, we can copy
                    // the entire channels in one go
                    uPlane.get(yuvBytes, offset, width * height / 4)
                    offset += width * height / 4
                    vPlane.get(yuvBytes, offset, width * height / 4)
                } else {
                    // When not equal, we need to copy the channels row by row
                    for (i in 0 until height / 2) {
                        uPlane.get(yuvBytes, offset, width / 2)
                        offset += width / 2
                        if (i < height / 2 - 1) {
                            uPlane.position(uPlane.position() + chromaRowPadding)
                        }
                    }
                    for (i in 0 until height / 2) {
                        vPlane.get(yuvBytes, offset, width / 2)
                        offset += width / 2
                        if (i < height / 2 - 1) {
                            vPlane.position(vPlane.position() + chromaRowPadding)
                        }
                    }
                }

                val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
                yuvMat.put(0, 0, yuvBytes)
                Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_I420, 4)
            }
        }

        return rgbaMat
    }
}