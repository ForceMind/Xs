package dev.forcemind.xs.android;

import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaMetadataRetriever;
import dev.forcemind.xs.core.Mp4Structure;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Narrow standard-MP4 validation. No remote URLs, player state, cookies or DRM keys. */
final class VideoFileValidator {
    static final long MAX_DURATION_MS=600_000;
    private static final int MAX_SAMPLE_BYTES=8*1024*1024;
    private static final int MAX_SAMPLES=100_000;
    static final class Info {
        final long durationMs; final int width,height,videoTracks,audioTracks,rotationDegrees;
        Info(long durationMs,int width,int height,int videoTracks,int audioTracks,int rotationDegrees) {
            this.durationMs=durationMs;this.width=width;this.height=height;
            this.videoTracks=videoTracks;this.audioTracks=audioTracks;this.rotationDegrees=rotationDegrees;
        }
    }
    private VideoFileValidator() {}
    static Info validate(File file)throws IOException {
        Mp4Structure.validate(file);
        MediaExtractor extractor=new MediaExtractor();
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try {
            extractor.setDataSource(file.getPath());
            java.util.Map<java.util.UUID,byte[]> pssh=extractor.getPsshInfo();
            if(pssh!=null&&!pssh.isEmpty())throw invalid();
            int tracks=extractor.getTrackCount(),videos=0,audios=0,width=0,height=0;
            long durationUs=0;int sampleBudget=MAX_SAMPLES;
            long deadline=android.os.SystemClock.elapsedRealtime()+30_000;
            if(tracks<1||tracks>2)throw invalid();
            ByteBuffer buffer=ByteBuffer.allocate(MAX_SAMPLE_BYTES);
            for(int i=0;i<tracks;i++) {
                MediaFormat format=extractor.getTrackFormat(i);
                String mime=format.getString(MediaFormat.KEY_MIME);
                if("video/avc".equals(mime)) {
                    videos++;width=format.getInteger(MediaFormat.KEY_WIDTH);height=format.getInteger(MediaFormat.KEY_HEIGHT);
                    if(width<=0||height<=0||width>3840||height>2160||(long)width*height>8_294_400)throw invalid();
                } else if("audio/mp4a-latm".equals(mime)) {
                    audios++;int channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if(channels<1||channels>2||rate<8000||rate>96000)throw invalid();
                } else throw invalid();
                if(!format.containsKey(MediaFormat.KEY_DURATION))throw invalid();
                long trackDuration=format.getLong(MediaFormat.KEY_DURATION);
                if(trackDuration<=0||trackDuration>MAX_DURATION_MS*1000)throw invalid();
                durationUs=Math.max(durationUs,trackDuration);
                extractor.selectTrack(i);extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                int samples=0;long maxTime=-1;
                while(true) {
                    if(android.os.SystemClock.elapsedRealtime()>deadline)throw invalid();
                    long size=extractor.getSampleSize();
                    if(size<0)break;
                    if(size==0||size>MAX_SAMPLE_BYTES||--sampleBudget<0||
                        (extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw invalid();
                    buffer.clear();int read=extractor.readSampleData(buffer,0);
                    if(read!=size)throw invalid();
                    long time=extractor.getSampleTime();if(time<0||time>trackDuration+250_000)throw invalid();
                    maxTime=Math.max(maxTime,time);samples++;
                    if(!extractor.advance())break;
                }
                extractor.unselectTrack(i);
                // A movie declaring a much longer track than its samples is incomplete.
                if(samples==0||maxTime<0||trackDuration-maxTime>1_000_000)throw invalid();
            }
            if(videos!=1||audios>1)throw invalid();
            retriever.setDataSource(file.getPath());
            long metadataDuration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int rotation=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if(metadataDuration<=0||metadataDuration>MAX_DURATION_MS||Math.abs(metadataDuration-durationUs/1000)>1000||
                !(rotation==0||rotation==90||rotation==180||rotation==270))throw invalid();
            decodeFrame(retriever,0);
            decodeFrame(retriever,Math.max(0,metadataDuration-100)*1000);
            if(audios>0){decodeAudio(file,0);decodeAudio(file,Math.max(0,durationUs-250_000));}
            return new Info(Math.max(metadataDuration,durationUs/1000),width,height,videos,audios,rotation);
        } catch(IOException failure) {throw failure;}
        catch(RuntimeException|OutOfMemoryError unsupported){throw invalid();}
        finally {extractor.release();retriever.release();}
    }
    /** Decode representative first/tail AAC samples, rather than infer audio support from a MIME label. */
    private static void decodeAudio(File file,long seekUs)throws IOException {
        MediaExtractor input=new MediaExtractor();MediaCodec codec=null;
        try {
            input.setDataSource(file.getPath());MediaFormat audio=null;
            for(int i=0;i<input.getTrackCount();i++) {
                MediaFormat candidate=input.getTrackFormat(i);
                if("audio/mp4a-latm".equals(candidate.getString(MediaFormat.KEY_MIME))){audio=candidate;input.selectTrack(i);break;}
            }
            if(audio==null)throw invalid();
            input.seekTo(seekUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec=MediaCodec.createDecoderByType("audio/mp4a-latm");codec.configure(audio,null,null,0);codec.start();
            long deadline=android.os.SystemClock.elapsedRealtime()+5000;boolean ended=false;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                if(!ended) {
                    int slot=codec.dequeueInputBuffer(10000);
                    if(slot>=0) {
                        ByteBuffer buffer=codec.getInputBuffer(slot);if(buffer==null)throw invalid();buffer.clear();
                        long size=input.getSampleSize();
                        if(size<0){codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);ended=true;}
                        else {
                            if(size>buffer.capacity())throw invalid();int count=input.readSampleData(buffer,0);if(count!=size)throw invalid();
                            codec.queueInputBuffer(slot,0,count,input.getSampleTime(),0);input.advance();
                        }
                    }
                }
                int slot=codec.dequeueOutputBuffer(info,10000);
                if(slot>=0) {
                    boolean decoded=info.size>0;boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(slot,false);if(decoded)return;if(eos)break;
                }
            }
            throw invalid();
        } finally {input.release();if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}codec.release();}}
    }
    private static void decodeFrame(MediaMetadataRetriever retriever,long us)throws IOException {
        Bitmap frame=retriever.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST);
        if(frame==null)throw invalid();
        try {if(frame.getWidth()<=0||frame.getHeight()<=0||(long)frame.getWidth()*frame.getHeight()>8_294_400)throw invalid();}
        finally {frame.recycle();}
    }
    private static IOException invalid(){return new IOException("Unsupported or incomplete standard video");}
}
