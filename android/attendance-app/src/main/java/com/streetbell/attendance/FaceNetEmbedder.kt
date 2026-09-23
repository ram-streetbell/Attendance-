package com.streetbell.attendance

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/** On-device FaceNet 128-D embedding. Input: 160x160 RGB, standardized per image. */
class FaceNetEmbedder(context: Context) : AutoCloseable {
    companion object { const val DIM = 128 }
    private val interpreter: Interpreter

    init {
        val afd = context.assets.openFd("facenet.tflite")
        val input = FileInputStream(afd.fileDescriptor)
        val mapped = input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        interpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })
        input.close()
        afd.close()
    }

    @Synchronized fun embed(face: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(face, 160, 160, true)
        val pixels = IntArray(160 * 160)
        resized.getPixels(pixels, 0, 160, 0, 0, 160, 160)
        val raw = FloatArray(160 * 160 * 3)
        var k = 0; var mean = 0.0
        for (p in pixels) {
            val r = ((p shr 16) and 255).toFloat(); val g = ((p shr 8) and 255).toFloat(); val b = (p and 255).toFloat()
            raw[k++] = r; raw[k++] = g; raw[k++] = b
            mean += (r + g + b) / 3.0
        }
        mean /= pixels.size
        var variance = 0.0
        for (i in raw.indices step 3) {
            val v = (raw[i] + raw[i + 1] + raw[i + 2]) / 3.0
            val d = v - mean; variance += d * d
        }
        val std = sqrt(variance / pixels.size).coerceAtLeast(1.0 / sqrt(pixels.size.toDouble()))
        val input = ByteBuffer.allocateDirect(raw.size * 4).order(ByteOrder.nativeOrder())
        for (v in raw) input.putFloat(((v - mean) / std).toFloat())
        input.rewind()
        val output = Array(1) { FloatArray(DIM) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val d = sqrt(n).coerceAtLeast(1e-12)
        return FloatArray(v.size) { i -> (v[i] / d).toFloat() }
    }

    override fun close() { interpreter.close() }
}
