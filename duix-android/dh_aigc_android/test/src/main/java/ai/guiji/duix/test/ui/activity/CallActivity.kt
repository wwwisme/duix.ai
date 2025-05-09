package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.bean.ImageFrame
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.render.DebugSink
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors


class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
    }

    private var baseDir = ""
    private var modelDir = ""


    private lateinit var binding: ActivityCallBinding
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo?=null     // 加载的模型信息

    private val debugFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        baseDir = intent.getStringExtra("baseDir") ?: ""
        modelDir = intent.getStringExtra("modelDir") ?: ""

        Log.e("123", "baseDir: $baseDir")
        Log.e("123", "modelDir: $modelDir")

        binding.btnPlayEN.setOnClickListener {
            playWav("output.wav")
        }

        Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // 透明
//        binding.glTextureView.preserveEGLContextOnPause = true      // 后台运行不要释放上下文
        binding.glTextureView.isOpaque = false           // 透明

        mDUIXRender =
            DUIXRenderer(
                mContext,
                binding.glTextureView
            )
        binding.glTextureView.setRenderer(mDUIXRender)
        binding.glTextureView.renderMode =
            GLSurfaceView.RENDERMODE_WHEN_DIRTY      // 一定要在设置完Render之后再调用

        val sink = if (debugFrame){
            DebugSink { imageFrame ->
                mDUIXRender?.onVideoFrame(imageFrame)
                // to bitmap
                toBitmapDebug(imageFrame)
            }
        } else {
            mDUIXRender
        }
        duix = DUIX(mContext, baseDir, modelDir, sink) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    mModelInfo = info as ModelInfo
                    Log.e(TAG, "CALLBACK_EVENT_INIT_READY: $info")
                    initOk()
                }

                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    runOnUiThread {
                        Toast.makeText(mContext, "Initialization exception: $msg", Toast.LENGTH_SHORT).show()
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    runOnUiThread {

                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    Log.e(TAG, "CALLBACK_EVENT_PLAY_END: $msg")
                    runOnUiThread {
                        if ((mModelInfo?.motionRegions?.size ?: 0) > 0){
                            duix?.stopMotion(true)
                        }
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    Log.e(TAG, "CALLBACK_EVENT_PLAY_ERROR: $msg")
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_PROGRESS -> {
//                    Log.e(TAG, "audio play progress: $info")

                }
            }
        }
        // 异步回调结果
        duix?.init()
    }

    private var tempBitmap: Bitmap?=null

    /**
     * 将imageFrame转换成RGB显示，效率较低会导致渲染卡顿。
     */
    private fun toBitmapDebug(imageFrame: ImageFrame){
        tempBitmap?:let{
            tempBitmap = Bitmap.createBitmap(imageFrame.width, imageFrame.height, Bitmap.Config.ARGB_8888)
        }
        for (y in 0 until imageFrame.height) {
            for (x in 0 until imageFrame.width){
                val b = imageFrame.rawBuffer[(y * imageFrame.width + x) * 3].toInt() and 0xFF
                val g = imageFrame.rawBuffer[(y * imageFrame.width + x) * 3 + 1].toInt() and 0xFF
                val r = imageFrame.rawBuffer[(y * imageFrame.width + x) * 3 + 2].toInt() and 0xFF
                val pixelColor = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
                if (tempBitmap?.isRecycled == true){
                    return
                }
                tempBitmap?.setPixel(x, y, pixelColor)
            }
        }
        runOnUiThread {
            tempBitmap?.let {
                if (!it.isRecycled){
                    binding.ivDebugImage.setImageBitmap(tempBitmap)
                }
            }
        }
    }

    private fun initOk() {
        Log.e(TAG, "init ok")
        runOnUiThread {
            // 设置是随机播放动作区间还是顺序播放
            if ((mModelInfo?.motionRegions?.size ?: 0) > 0){
                duix?.setRandomMotion(true)
            }
            binding.btnPlayEN.visibility = View.VISIBLE
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        duix?.release()
        mDUIXRender?.release()
        tempBitmap?.recycle()
        tempBitmap = null
    }

    /**
     * 播放16k采样率单通道16位深的wav本地文件
     */
    private fun playWav(wavName: String) {
        val wavDir = File(mContext.getExternalFilesDir("duix"), "wav")
        if (!wavDir.exists()) {
            wavDir.mkdirs()
        }
        val wavFile = File(wavDir, wavName)
        if (!wavFile.exists()) {
            // 拷贝到sdcard
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                val input = mContext.assets.open("wav/${wavName}")
                val out: OutputStream = FileOutputStream("${wavFile.absolutePath}.tmp")
                val buffer = ByteArray(1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
                input.close()
                out.close()
                File("${wavFile.absolutePath}.tmp").renameTo(wavFile)
                playAudioWithMotion(wavFile.absolutePath)
            }
        } else {
            playAudioWithMotion(wavFile.absolutePath)
        }
    }

    private fun playAudioWithMotion(path: String){
        runOnUiThread {
            duix?.playAudio(path)
            // 如果模型支持动作区间会播放动作区间
            if ((mModelInfo?.motionRegions?.size ?: 0) > 0){
                duix?.startMotion()
            }
        }
    }

}
