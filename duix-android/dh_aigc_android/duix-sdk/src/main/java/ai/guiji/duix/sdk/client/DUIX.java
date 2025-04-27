package ai.guiji.duix.sdk.client;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.guiji.duix.sdk.client.loader.ModelInfo;
import ai.guiji.duix.sdk.client.render.RenderSink;
import ai.guiji.duix.sdk.client.thread.RenderThread;

public class DUIX {

    private final Context mContext;
    private final Callback mCallback;
    private final String baseDir;
    private final String modelDir;
    private final RenderSink renderSink;
    private ExecutorService commonExecutor = Executors.newSingleThreadExecutor();
    private RenderThread mRenderThread;

    private boolean isReady;            // 准备完成的标记

    public DUIX(Context context, String baseDir, String modelDir, RenderSink sink, Callback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.baseDir = baseDir;
        this.modelDir = modelDir;
        this.renderSink = sink;
    }

    public boolean isReady() {
        return isReady;
    }

    /**
     * 模型读取，需要异步操作
     */
    public void init() {
        if (mRenderThread != null) {
            mRenderThread.stopPreview();
            mRenderThread = null;
        }
        mRenderThread = new RenderThread(mContext, baseDir, modelDir, renderSink, new RenderThread.RenderCallback() {
            @Override
            public void onInitResult(int code, String message, ModelInfo modelInfo) {
                if (code == 0){
                    isReady = true;
                    if (mCallback != null){
                        mCallback.onEvent(Constant.CALLBACK_EVENT_INIT_READY, "init ok", modelInfo);
                    }
                } else {
                    if (mCallback != null){
                        mCallback.onEvent(Constant.CALLBACK_EVENT_INIT_ERROR, message, null);
                    }
                }
            }

            @Override
            public void onPlayStart() {
                if (mCallback != null){
                    mCallback.onEvent(Constant.CALLBACK_EVENT_AUDIO_PLAY_START, "play start", null);
                }
            }

            @Override
            public void onPlayEnd() {
                if (mCallback != null){
                    mCallback.onEvent(Constant.CALLBACK_EVENT_AUDIO_PLAY_END, "play end", null);
                }
            }

            @Override
            public void onPlayProgress(long current, long total) {
                float progress = current * 1.0F / total;
                if (mCallback != null){
                    mCallback.onEvent(Constant.CALLBACK_EVENT_AUDIO_PLAY_PROGRESS, "audio play progress", progress);
                }
            }

            @Override
            public void onPlayError(int code, String msg) {
                if (mCallback != null){
                    mCallback.onEvent(Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR, "audio play error code: " + code + " msg: " + msg, null);
                }
            }
        });
        mRenderThread.setName("DUIXRender-Thread");
        mRenderThread.start();
    }

    public boolean setRandomMotion(boolean random){
        if (mRenderThread != null) {
            return mRenderThread.setRandomMotion(random);
        } else {
            return false;
        }
    }

    /**
     * 播放动作区间
     */
    public boolean startMotion() {
        if (mRenderThread != null) {
            return mRenderThread.startMotion();
        } else {
            return false;
        }
    }

    /**
     * 停止播放动作区间
     */
    public boolean stopMotion(boolean immediately){
        if (mRenderThread != null) {
            return mRenderThread.stopMotion(immediately);
        } else {
            return false;
        }
    }

    /**
     * 播放音频文件
     *
     * @param wavPath 16k采样率单通道16位深的wav本地文件
     */
    public void playAudio(String wavPath) {
        if (isReady && mRenderThread != null) {
            mRenderThread.prepareAudio(wavPath);
        }
    }

    /**
     * 停止音频播放
     */
    public boolean stopAudio() {
        if (isReady && mRenderThread != null) {
            mRenderThread.stopAudio();
            return true;
        } else {
            return false;
        }
    }

    public void release() {
        isReady = false;
        if (commonExecutor != null) {
            commonExecutor.shutdown();
            commonExecutor = null;
        }
        if (mRenderThread != null) {
            mRenderThread.stopPreview();
        }
    }
}
