package ai.guiji.duix.sdk.client.thread;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.btows.ncnntest.SCRFDNcnn;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ai.guiji.duix.sdk.client.bean.ImageFrame;
import ai.guiji.duix.sdk.client.bean.ModelInfo;
import ai.guiji.duix.sdk.client.render.RenderSink;
import ai.guiji.duix.sdk.client.util.Logger;

/**
 * DUIX绘制线程，负责绘制线程及音频播放控制
 */
public class RenderThread extends Thread {

    private static final int MSG_RENDER_STEP = 1;           // 请求下一帧渲染
    private static final int MSG_STOP_RENDER = 2;           // 停止渲染
    private static final int MSG_QUIT = 3;                  // 退出线程
    private static final int MSG_PREPARE_AUDIO = 4;         // 准备播放音频
    private static final int MSG_STOP_AUDIO = 5;            // 停止播放音频
    private static final int MSG_PLAY_AUDIO = 6;            // 音频的下载及ncnn计算完毕，准备播放
    private static final int MSG_REQUIRE_MOTION = 7;        // 请求播放动作区间
    private static final int MSG_RANDOM_MOTION = 8;         // 请求播放动作区间
    private static final int MSG_STOP_MOTION = 11;          // 请求结束播放动作区间

    private static final int MSG_PAUSE_AUDIO = 9;            // 暂停播放音频
    private static final int MSG_RESUME_AUDIO = 10;          // 恢复播放音频

    private volatile boolean isRendering = false;                     // 为false时终止线程
    RenderHandler mHandler;                                 // 使用该处理器来调度线程的事件

    private final Object mReadyFence = new Object();        // 给isReady加一个对象锁

    private final Object mBnfFence = new Object();        // 给isReady加一个对象锁

    private final Context mContext;
    private SCRFDNcnn scrfdncnn;

    private final RenderCallback callback;

    private RenderSink mRenderSink;
    private ExecutorService commonExecutor;

    private ConcurrentLinkedQueue<ModelInfo.Frame> mPreviewQueue;       // 播放帧
    private SimpleExoPlayer mExoPlayer;                     // 音频播放器
    private int mTotalBnf = 0;                              // 播放音频的帧数
    private boolean requireMotion = false;                  // 请求播放动作
    private boolean mMotionRandom = false;                   // 是否随机播放动作
    private boolean mMotionContinue = false;                 // 动作区间持续播放
    private int mCurrentMotionIndex = -1;                    // 当前动作区间

    private ModelInfo mModelInfo;                           // 模型的全部信息都放在这里面
    private ByteBuffer rawBuffer;
    private ByteBuffer maskBuffer;
    private final String baseConfigDir;
    private final String modelDir;

    public RenderThread(Context context, String baseConfigDir, String modelDir, RenderSink renderSink, RenderCallback callback) {
        this.mContext = context;
        this.baseConfigDir = baseConfigDir;
        this.modelDir = modelDir;
        this.mRenderSink = renderSink;
        this.callback = callback;
    }

    public void setRenderSink(RenderSink renderSink){
        this.mRenderSink = renderSink;
    }

    @Override
    public void run() {
        super.run();
        Looper.prepare();
        mHandler = new RenderHandler(this);
        mPreviewQueue = new ConcurrentLinkedQueue<>();

        commonExecutor = Executors.newSingleThreadExecutor();
        BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(mContext).build();
        ExoTrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory();
        TrackSelector trackSelector = new DefaultTrackSelector(mContext, videoTrackSelectionFactory);
        mExoPlayer = new SimpleExoPlayer.Builder(mContext)
                .setTrackSelector(trackSelector)
                .setBandwidthMeter(bandwidthMeter)
                .build();
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(C.USAGE_VOICE_COMMUNICATION).build();

        mExoPlayer.setAudioAttributes(attributes, false);
        mExoPlayer.setPlayWhenReady(true);
        mExoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                Player.Listener.super.onPlaybackStateChanged(state);
//                Log.e("123", "onPlaybackStateChanged:" + state);
                if (state == Player.STATE_READY) {
                    if (callback != null) {
                        callback.onPlayStart();
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (callback != null) {
                        callback.onPlayEnd();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull ExoPlaybackException error) {
                Player.Listener.super.onPlayerError(error);
                Log.e("123", "onPlayerError:" + error);
                if (callback != null) {
                    callback.onPlayError(-1000, "音频播放异常: " + error);
                }

            }
        });

        scrfdncnn = new SCRFDNcnn();
        int sessionKey = (int) (System.currentTimeMillis() / 1000);
        scrfdncnn.createdigit(sessionKey, (SCRFDNcnn.Callback) (what, arg1, arg2, msg1, msg2, object) -> {
        });
        ModelInfo info = ModelInfo.loadResource(scrfdncnn, baseConfigDir, modelDir);
        if (info != null) {
            try {
                scrfdncnn.config(info.getNcnnConfig());
                scrfdncnn.start();
                mModelInfo = info;
                Logger.d("分辨率: " + mModelInfo.getWidth() + "x" + mModelInfo.getHeight());
                rawBuffer = ByteBuffer.allocate(mModelInfo.getWidth() * mModelInfo.getHeight() * 3);
                maskBuffer = ByteBuffer.allocate(mModelInfo.getWidth() * mModelInfo.getHeight() * 3);
                if (!mModelInfo.isHasMask()) {
                    // 用纯白填充mask
                    Arrays.fill(maskBuffer.array(), (byte) 255);
                }
                Logger.d("模型初始化完成");
                if (callback != null) {
                    callback.onInitResult(0, "init success", mModelInfo);
                }
            } catch (Exception e){
                if (callback != null) {
                    callback.onInitResult(-1, "模型加载异常: " + e, null);
                }
            }
        } else {
            if (callback != null) {
                callback.onInitResult(-2, "模型配置读取异常", null);
            }
        }

        synchronized (mReadyFence) {
            mReadyFence.notify();
        }
        isRendering = true;
        handleAudioStep();
        Looper.loop();
        synchronized (mBnfFence) {
            // 线程最后释放NCNN
            scrfdncnn.stop();
            scrfdncnn.reset();
            scrfdncnn.releasedigit(sessionKey);
        }
        Logger.d("NCNN释放");
        synchronized (mReadyFence) {
            mHandler = null;
        }
    }

    public void stopPreview() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_STOP_RENDER);
        }
    }

    public void prepareAudio(String wavPath) {
        if (mHandler != null) {
            Message msg = new Message();
            msg.what = MSG_PREPARE_AUDIO;
            msg.obj = wavPath;
            mHandler.sendMessage(msg);
        }
    }

    public void pauseAudio() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_PAUSE_AUDIO);
        }
    }

    public void resumeAudio(){
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_RESUME_AUDIO);
        }
    }

    public void stopAudio() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MSG_STOP_AUDIO);
        }
    }

    public boolean setRandomMotion(boolean random){
        if (mHandler != null) {
            if (mModelInfo != null && !mModelInfo.getMotionRegions().isEmpty()){
                Message msg = new Message();
                msg.what = MSG_RANDOM_MOTION;
                msg.obj = random;
                mHandler.sendMessage(msg);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean startMotion() {
        if (mHandler != null) {
            if (mModelInfo != null && !mModelInfo.getMotionRegions().isEmpty()){
                mHandler.sendEmptyMessage(MSG_REQUIRE_MOTION);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean stopMotion(boolean immediately){
        if (mHandler != null) {
            if (mModelInfo != null && !mModelInfo.getMotionRegions().isEmpty()){
                Message msg = new Message();
                msg.what = MSG_STOP_MOTION;
                msg.obj = immediately;
                mHandler.sendMessage(msg);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean isPlaying(){
        return mExoPlayer != null && mExoPlayer.isPlaying();
    }

    private void handleAudioStep() {
        if (isRendering) {
            long startTime = System.currentTimeMillis();
            renderStep();
            long endTime = System.currentTimeMillis();
            long delay = 40 - (endTime - startTime);
            if (delay < 0) {
                Logger.w("渲染耗时过高: " + (endTime - startTime) + "(>40ms)");
                delay = 0;
            }
            if (mHandler != null) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RENDER_STEP), delay);
            }
        } else {
            if (commonExecutor != null) {
                commonExecutor.shutdownNow();
                try {
                    boolean termination = commonExecutor.awaitTermination(300, TimeUnit.MILLISECONDS);
                    Logger.d("commonExecutor termination: " + termination);
                } catch (InterruptedException e) {
                    Logger.e("中断commonExecutor异常: " + e);
                }
            }
            if (mPreviewQueue != null) {
                mPreviewQueue.clear();
            }
            if (mExoPlayer != null) {
                if (mExoPlayer.isPlaying()) {
                    mExoPlayer.stop();
                }
                mExoPlayer.release();
            }
            if (mHandler != null) {
                mHandler.sendEmptyMessage(MSG_QUIT);
            }
        }
    }

    private void findMotionRegions(){
        if (!mModelInfo.getMotionRegions().isEmpty()) {
            if (mMotionRandom){
                mCurrentMotionIndex = new Random().nextInt(mModelInfo.getMotionRegions().size());
            } else {
                mCurrentMotionIndex++;
                if (mCurrentMotionIndex >= mModelInfo.getMotionRegions().size()){
                    mCurrentMotionIndex = 0;
                }
            }
            mPreviewQueue.clear();
            ModelInfo.Region motionRegion = mModelInfo.getMotionRegions().get(mCurrentMotionIndex);
            Logger.d("发现想要动作区间index: " + mCurrentMotionIndex + " region: " + motionRegion);
            mPreviewQueue.addAll(motionRegion.frames);
        }
    }

    private void renderStep() {
        ModelInfo.Frame frame;
        if (requireMotion) {
            // 收到动作的通知
            requireMotion = false;
            mMotionContinue = true;
            mCurrentMotionIndex = -1;
            findMotionRegions();
        }
        if (mPreviewQueue.isEmpty()) {
            if (mMotionContinue){
                findMotionRegions();
            } else {
                // 先假设把静默的都加进来
                List<ModelInfo.Region> silenceRegions = mModelInfo.getSilenceRegions();
                mPreviewQueue.addAll(silenceRegions.get(0).frames);
                List<ModelInfo.Frame> copiedList = new ArrayList<>(silenceRegions.get(0).frames);
                // 反向的也加进来
                Collections.reverse(copiedList);
                mPreviewQueue.addAll(copiedList);
            }
        }
        frame = mPreviewQueue.poll();
        if (frame != null) {
            int audioBnf = -1;
            if (mExoPlayer != null && mExoPlayer.isPlaying()) {
                if (callback != null){
                    callback.onPlayProgress(mExoPlayer.getCurrentPosition(), mExoPlayer.getDuration());
                }
                float progress = mExoPlayer.getCurrentPosition() * 1.0F / mExoPlayer.getDuration();
                float curr = mTotalBnf * progress;
                audioBnf = (int) curr;
            }
            if (audioBnf > -1 && audioBnf <= mTotalBnf) {
                if (mModelInfo.isHasMask()) {
                    int rst = scrfdncnn.mskrstbuf(frame.rawPath, frame.maskPath, frame.rect, audioBnf, frame.sgPath, rawBuffer.array(), maskBuffer.array(), mModelInfo.getWidth() * mModelInfo.getHeight() * 3);
                    //                Log.e("123", "mskrstbuf rst: " + rst);
                } else {
                    int rst = scrfdncnn.onerstbuf(frame.rawPath, frame.rect, audioBnf, rawBuffer.array(), mModelInfo.getWidth() * mModelInfo.getHeight() * 3);
                }
            } else {
                if (mModelInfo.isHasMask()) {
                    int rst = scrfdncnn.drawmskbuf(frame.sgPath, frame.maskPath, rawBuffer.array(), maskBuffer.array(), mModelInfo.getWidth() * mModelInfo.getHeight() * 3);
//                Log.e("123", "drawmskbuf rst: " + rst);
                } else {
                    int rst = scrfdncnn.drawonebuf(frame.rawPath, rawBuffer.array(), mModelInfo.getWidth() * mModelInfo.getHeight() * 3);
                }
            }
            if (mRenderSink != null) {
                mRenderSink.onVideoFrame(new ImageFrame(rawBuffer, maskBuffer, mModelInfo.getWidth(), mModelInfo.getHeight()));
            }
        }
    }

    private void handleStopRender() {
        if (isRendering) {
            isRendering = false;
        } else {
            mHandler.sendEmptyMessage(MSG_QUIT);
        }
    }

    private void handlePrepareAudio(String path){
        if (!isRendering){
            return;
        }
        if (commonExecutor != null) {
            commonExecutor.execute(() -> {
                String playPath = path;
                if (!TextUtils.isEmpty(path)){
                    File wavFile = new File(path);
                    try (InputStream inputStream = new FileInputStream(wavFile)) {
                        byte[] headBuffer = new byte[44]; // 创建一个大小为44B的缓冲区
                        int ret = inputStream.read(headBuffer);
                        if (ret != -1) {
                            int chunkSize = headBuffer[4] + (headBuffer[5] << 8) + (headBuffer[6] << 16) + (headBuffer[7] << 24);
                            long fileLength = wavFile.length();
                            if (fileLength != chunkSize + 8) {
                                // wav头重写
                                int setChunkSize = (int) (fileLength - 8);
                                String setChunk2Id = "" + (char)headBuffer[36] + (char)headBuffer[37] + (char)headBuffer[38] + (char)headBuffer[39];
//                                Logger.d("setChunk2Id: " + setChunk2Id);
                                int setChunk2Size = (int) (fileLength - 44);
//                            Logger.d("setChunkSize: " + setChunkSize);
                                Logger.w("Wav头中的chunkSize和实际文件大小不一致chunkSize: " + chunkSize + " fileLength: " + fileLength + ", 尝试重写");
                                headBuffer[7] = (byte) (setChunkSize >> 24);
                                headBuffer[6] = (byte) ((setChunkSize << 8) >> 24);
                                headBuffer[5] = (byte) ((setChunkSize << 16) >> 24);
                                headBuffer[4] = (byte) ((setChunkSize << 24) >> 24);

                                if ("data".equals(setChunk2Id)){
                                    headBuffer[43] = (byte) (setChunk2Size >> 24);
                                    headBuffer[42] = (byte) ((setChunk2Size << 8) >> 24);
                                    headBuffer[41] = (byte) ((setChunk2Size << 16) >> 24);
                                    headBuffer[40] = (byte) ((setChunk2Size << 24) >> 24);
                                }

                                String modifyName = "modify_" + wavFile.getName();
                                File modifyWavFile = new File(wavFile.getParentFile(), modifyName);
                                OutputStream outStream = new BufferedOutputStream(new FileOutputStream(modifyWavFile));
                                outStream.write(headBuffer, 0, headBuffer.length);
                                byte[] buffer = new byte[1024 * 4];
                                while (inputStream.read(buffer) != -1) {
                                    outStream.write(buffer);
                                }
                                outStream.flush();
                                outStream.close();
                                Logger.d("使用转换后的Wav文件: " + modifyWavFile.getAbsolutePath());
                                playPath = modifyWavFile.getAbsolutePath();
                            }
                        }
                    } catch (Exception e) {
                        Logger.e("音频文件头信息读取失败: " + e);
                    }
                }
                if (isRendering && !TextUtils.isEmpty(playPath)) {
                    synchronized (mBnfFence) {
                        long t1 = System.currentTimeMillis();
                        int all_bnf = scrfdncnn.onewav(playPath, "");
                        long t2 = System.currentTimeMillis();
                        Logger.d("all_bnf: " + all_bnf + " use: " + (t2-t1) + "(ms) text: " + playPath);
                        Message msg = new Message();
                        msg.what = MSG_PLAY_AUDIO;
                        msg.arg1 = all_bnf;
                        msg.obj = playPath;
                        if (mHandler != null) {
                            mHandler.sendMessage(msg);
                        }
                    }
                }
            });
        }
    }

    private void handleStopAudio() {
        if (mExoPlayer != null) {
            if (mExoPlayer.isPlaying()) {
                mExoPlayer.stop();
            }
        }
    }

    private void handlePlayAudio(int all_bnf, String path) {
        if (all_bnf > 0){
            mTotalBnf = all_bnf - 1;
        } else {
            mTotalBnf = all_bnf;
        }
        if (mExoPlayer != null) {
            if (mExoPlayer.isPlaying()) {
                mExoPlayer.stop();
            }
            MediaSource videoSource = new ProgressiveMediaSource.Factory(new DefaultDataSourceFactory(mContext))
                    .createMediaSource(new MediaItem.Builder().setUri(path).build());
            mExoPlayer.setMediaSource(videoSource, true);
            mExoPlayer.setPlayWhenReady(true);
            mExoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
            mExoPlayer.setVolume(1f);
            mExoPlayer.prepare();
        }
    }

    private void handlePauseAudio(){
        if (mExoPlayer != null) {
            mExoPlayer.pause();
        }
    }

    private void handleResumeAudio(){
        if (mExoPlayer != null) {
            mExoPlayer.setPlayWhenReady(true);
        }
    }

    private void handleRandomMotion(boolean random){
        mMotionRandom = random;
    }

    private void handleRequireMotion() {
        requireMotion = true;
    }

    private void handleStopMotion(boolean immediately){
        mMotionContinue = false;
        if (immediately){
            mPreviewQueue.clear();
        }
    }

    static class RenderHandler extends Handler {

        private final WeakReference<RenderThread> encoderWeakReference;

        public RenderHandler(RenderThread render) {
            encoderWeakReference = new WeakReference<>(render);
        }

        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
            RenderThread render = encoderWeakReference.get();
            if (render == null) {
                return;
            }
            switch (what) {
                case MSG_RENDER_STEP:
                    render.handleAudioStep();
                    break;
                case MSG_STOP_RENDER:
                    render.handleStopRender();
                    break;
                case MSG_PREPARE_AUDIO:
                    render.handlePrepareAudio((String) msg.obj);
                    break;
                case MSG_PAUSE_AUDIO:
                    render.handlePauseAudio();
                    break;
                case MSG_RESUME_AUDIO:
                    render.handleResumeAudio();
                    break;
                case MSG_STOP_AUDIO:
                    render.handleStopAudio();
                    break;
                case MSG_PLAY_AUDIO:
                    render.handlePlayAudio(msg.arg1, (String) msg.obj);
                    break;
                case MSG_REQUIRE_MOTION:
                    render.handleRequireMotion();
                    break;
                case MSG_STOP_MOTION:
                    render.handleStopMotion((boolean) msg.obj);
                    break;
                case MSG_RANDOM_MOTION:
                    render.handleRandomMotion((boolean) msg.obj);
                    break;
                case MSG_QUIT:
                    Logger.i("duix thread quit!");
                    Looper myLooper = Looper.myLooper();
                    if (myLooper != null) {
                        myLooper.quit();
                    }
                    break;
            }
        }

    }

    public interface RenderCallback {
        void onInitResult(int code, String message, ModelInfo modelInfo);

        void onPlayStart();

        void onPlayEnd();

        void onPlayProgress(long current, long total);

        void onPlayError(int code, String msg);
    }
}
