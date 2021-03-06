package org.droidplanner.services.android.utils.video;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * Created by Fredia Huya-Kouadio on 2/19/15.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MediaCodecManager {

    private static final String MIME_TYPE = "video/avc";
    public static final int DEFAULT_VIDEO_WIDTH = 1920;
    public static final int DEFAULT_VIDEO_HEIGHT = 1080;

    private final Runnable stopSafely = new Runnable() {
        @Override
        public void run() {
            processInputData.set(false);
            sendCompletionFlag.set(false);
            naluChunkAssembler.reset();

            if (dequeueRunner != null && dequeueRunner.isAlive()) {
                Timber.d("Interrupting dequeue runner thread.");
                dequeueRunner.interrupt();
            }
            dequeueRunner = null;

            final MediaCodec mediaCodec = mediaCodecRef.get();
            if (mediaCodec != null) {
                try {
                    mediaCodec.stop();
                }catch(IllegalStateException e){
                    Timber.e(e, "Error while stopping media codec.");
                }
                mediaCodec.release();
                mediaCodecRef.set(null);
            }

            isDecoding.set(false);
            handler.post(decodingEndedNotification);
        }
    };

    private final Runnable decodingStartedNotification = new Runnable() {
        @Override
        public void run() {
            final DecoderListener listener = decoderListenerRef.get();
            if (listener != null)
                listener.onDecodingStarted();
        }
    };

    private final Runnable decodingErrorNotification = new Runnable() {
        @Override
        public void run() {
            final DecoderListener listener = decoderListenerRef.get();
            if (listener != null)
                listener.onDecodingError();
        }
    };

    private final Runnable decodingEndedNotification = new Runnable() {
        @Override
        public void run() {
            final DecoderListener listener = decoderListenerRef.get();
            if (listener != null)
                listener.onDecodingEnded();
        }
    };

    private final AtomicBoolean decodedFirstFrame = new AtomicBoolean(false);

    private final AtomicBoolean isDecoding = new AtomicBoolean(false);
    private final AtomicBoolean processInputData = new AtomicBoolean(false);
    private final AtomicBoolean sendCompletionFlag = new AtomicBoolean(false);
    private final AtomicReference<MediaCodec> mediaCodecRef = new AtomicReference<>();
    private final AtomicReference<DecoderListener> decoderListenerRef = new AtomicReference<>();
    private final NALUChunkAssembler naluChunkAssembler;

    private final Handler handler;

    private DequeueCodec dequeueRunner;

    public MediaCodecManager(Handler handler) {
        this.handler = handler;
        this.naluChunkAssembler = new NALUChunkAssembler();
    }

    public void startDecoding(Surface surface, DecoderListener listener) throws IOException {
        if (surface == null)
            throw new IllegalStateException("Surface argument must be non-null.");

        if (isDecoding.compareAndSet(false, true)) {
            Timber.i("Starting decoding...");
            this.naluChunkAssembler.reset();

            this.decoderListenerRef.set(listener);

            final MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT);

            final MediaCodec mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            mediaCodec.configure(mediaFormat, surface, null, 0);
            mediaCodec.start();

            mediaCodecRef.set(mediaCodec);
            processInputData.set(true);

            dequeueRunner = new DequeueCodec();
            dequeueRunner.start();
        }
    }

    public void stopDecoding(DecoderListener listener) {
        Timber.i("Stopping input data processing...");

        this.decoderListenerRef.set(listener);
        if(!isDecoding.get()) {
            if (listener != null) {
                    notifyDecodingEnded();
            }
        }
        else {
            if(decodedFirstFrame.get()) {
                if (processInputData.compareAndSet(true, false)) {
                    sendCompletionFlag.set(!processNALUChunk(naluChunkAssembler.getEndOfStream()));
                }
            }
            else{
                handler.post(stopSafely);
            }
        }
    }

    public void onInputDataReceived(byte[] data, int dataSize) {
        if (isDecoding.get()) {
            if (processInputData.get()) {
                //Process the received buffer
                NALUChunk naluChunk = naluChunkAssembler.assembleNALUChunk(data, dataSize);
                if (naluChunk != null)
                    processNALUChunk(naluChunk);
            } else {
                if (sendCompletionFlag.get()) {
                    Timber.d("Sending end of stream data.");
                    sendCompletionFlag.set(!processNALUChunk(naluChunkAssembler.getEndOfStream()));
                }
            }
        }
    }

    private boolean processNALUChunk(NALUChunk naluChunk) {
        if (naluChunk == null)
            return false;

        final MediaCodec mediaCodec = mediaCodecRef.get();
        if (mediaCodec == null)
            return false;

        try {
            final int index = mediaCodec.dequeueInputBuffer(-1);
            if (index >= 0) {
                ByteBuffer inputBuffer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    inputBuffer = mediaCodec.getInputBuffer(index);
                } else {
                    inputBuffer = mediaCodec.getInputBuffers()[index];
                }

                if (inputBuffer == null)
                    return false;

                inputBuffer.clear();
                int totalLength = 0;

                int payloadCount = naluChunk.payloads.length;
                for (int i = 0; i < payloadCount; i++) {
                    ByteBuffer payload = naluChunk.payloads[i];

                    if (payload.capacity() == 0)
                        continue;

                    inputBuffer.order(payload.order());
                    final int dataLength = payload.position();
                    inputBuffer.put(payload.array(), 0, dataLength);

                    totalLength += dataLength;
                }

                mediaCodec.queueInputBuffer(index, 0, totalLength, naluChunk.presentationTime, naluChunk.flags);
            }
        } catch (IllegalStateException e) {
            Timber.e(e.getMessage(), e);
            return false;
        }

        return true;
    }

    private void notifyDecodingStarted() {
        handler.post(decodingStartedNotification);
    }

    private void notifyDecodingError() {
        handler.post(decodingErrorNotification);
    }

    private void notifyDecodingEnded() {
        handler.post(stopSafely);
    }

    private class DequeueCodec extends Thread {
        @Override
        public void run() {
            final MediaCodec mediaCodec = mediaCodecRef.get();
            if (mediaCodec == null)
                throw new IllegalStateException("Start decoding hasn't been called yet.");

            Timber.i("Starting dequeue codec runner.");

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            decodedFirstFrame.set(false);
            boolean doRender;
            boolean continueDequeue = true;
            try {
                while (continueDequeue) {
                    final int index = mediaCodec.dequeueOutputBuffer(info, -1);
                    if (index >= 0) {
                        doRender = info.size != 0;
                        mediaCodec.releaseOutputBuffer(index, doRender);

                        if (decodedFirstFrame.compareAndSet(false, true)) {
                            notifyDecodingStarted();
                            Timber.i("Received first decoded frame of size " + info.size);
                        }

                        continueDequeue = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                        if (!continueDequeue) {
                            Timber.i("Received end of stream flag.");
                        }
                    }
                }
            } catch (IllegalStateException e) {
                if(!isInterrupted()) {
                    Timber.e("Decoding error!", e);
                    notifyDecodingError();
                }
            } finally {
                if (!isInterrupted())
                    notifyDecodingEnded();
                Timber.i("Stopping dequeue codec runner.");
            }
        }
    }
}
