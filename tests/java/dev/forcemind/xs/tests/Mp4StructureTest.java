package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.MediaDownloadPolicy;
import dev.forcemind.xs.core.Mp4Structure;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Structural fixtures are deliberately not codec-decodable; Android tests check playback separately. */
public final class Mp4StructureTest {
    private static int assertions;
    private static final String POST="https://x.com/user/status/123";
    private static void check(boolean value){if(!value)throw new AssertionError("MP4 assertion "+(assertions+1));assertions++;}
    private static byte[] join(byte[]... parts)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] part:parts)out.write(part);return out.toByteArray();
    }
    private static byte[] ints(long... values)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(out);
        for(long value:values)data.writeInt((int)value);return out.toByteArray();
    }
    private static byte[] box(String type,byte[]... parts)throws IOException {
        byte[] payload=join(parts);return join(ints(payload.length+8),type.getBytes(StandardCharsets.ISO_8859_1),payload);
    }
    private static byte[] extended(String type,byte[] payload)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(out);
        data.writeInt(1);data.writeBytes(type);data.writeLong(payload.length+16);data.write(payload);return out.toByteArray();
    }
    private static byte[] ftyp(String brand)throws IOException {
        return box("ftyp",brand.getBytes(StandardCharsets.ISO_8859_1),ints(512),"isomavc1".getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] movie(long offset,boolean co64,boolean constant,byte[] extra)throws IOException {
        byte[] sampleHeader=new byte[78];sampleHeader[7]=1;
        byte[] avcc=box("avcC",new byte[]{1,66,0,30,(byte)255,(byte)225,0});
        byte[] entry=box("avc1",sampleHeader,avcc,extra);
        byte[] offsets;
        if(co64) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(bytes);
            data.writeInt(0);data.writeInt(1);data.writeLong(offset);offsets=box("co64",bytes.toByteArray());
        } else offsets=box("stco",ints(0,1,offset));
        byte[] sizes=constant?box("stsz",ints(0,3,2)):box("stsz",ints(0,0,2,3,4));
        byte[] stbl=box("stbl",box("stsd",ints(0,1),entry),box("stts",ints(0,1,2,1000)),
            sizes,box("stsc",ints(0,1,1,2,1)),offsets);
        byte[] references=box("dinf",box("dref",ints(0,1),box("url ",ints(1))));
        byte[] mdhd=new byte[24];put(mdhd,12,1000);put(mdhd,16,2000);
        byte[] hdlr=new byte[24];System.arraycopy("vide".getBytes(StandardCharsets.US_ASCII),0,hdlr,8,4);
        return box("moov",box("mvhd",new byte[100]),box("trak",box("tkhd",new byte[84]),
            box("mdia",box("mdhd",mdhd),box("hdlr",hdlr),box("minf",box("vmhd",new byte[12]),references,stbl))));
    }
    private static byte[] valid(boolean fastStart,boolean co64,boolean constant)throws IOException {
        byte[] type=ftyp("isom"),payload=constant?new byte[6]:new byte[7];
        byte[] moov=movie(0,co64,constant,new byte[0]);
        long offset=type.length+8+(fastStart?moov.length:0);
        moov=movie(offset,co64,constant,new byte[0]);
        return fastStart?join(type,moov,box("mdat",payload)):join(type,box("mdat",payload),moov);
    }
    private static int typeAt(byte[] bytes,String type) {
        byte[] needle=type.getBytes(StandardCharsets.ISO_8859_1);
        for(int i=4;i<=bytes.length-4;i++)if(Arrays.equals(Arrays.copyOfRange(bytes,i,i+4),needle))return i;
        throw new AssertionError("No box "+type);
    }
    private static void put(byte[] bytes,int at,long value){for(int i=3;i>=0;i--){bytes[at+i]=(byte)value;value>>>=8;}}
    private static void accepts(byte[] bytes)throws Exception {
        File file=File.createTempFile("xs-mp4-structure-",".mp4");
        try{Files.write(file.toPath(),bytes);Mp4Structure.validate(file);check(true);}finally{check(file.delete());}
    }
    private static void rejects(byte[] bytes)throws Exception {
        File file=File.createTempFile("xs-mp4-rejected-",".mp4");
        try{Files.write(file.toPath(),bytes);try{Mp4Structure.validate(file);throw new AssertionError("Malformed MP4 accepted");}
            catch(IOException expected){check(!expected.getMessage().contains(file.getName()));}}
        finally{check(file.delete());}
    }
    private static void rejectsUrl(String post,String media) {
        try{MediaDownloadPolicy.validateVideo(post,media);throw new AssertionError("Video URL accepted");}
        catch(IllegalArgumentException expected){check(!expected.getMessage().contains("http"));}
    }
    public static void main(String[] args)throws Exception {
        for(boolean fast:new boolean[]{false,true})for(boolean wide:new boolean[]{false,true})for(boolean constant:new boolean[]{false,true})accepts(valid(fast,wide,constant));
        byte[] full=valid(true,false,false);
        rejects(Arrays.copyOf(full,full.length-1));rejects(join(full,new byte[4]));
        rejects("<html>not a complete video file</html>".getBytes(StandardCharsets.US_ASCII));
        rejects(join(ftyp("isom"),box("mdat",new byte[10])));
        rejects(join(ftyp("isom"),movie(0,false,false,new byte[0])));
        rejects(join(ftyp("dash"),Arrays.copyOfRange(full,ftyp("isom").length,full.length)));
        rejects(join(full,box("moov",new byte[0])));rejects(join(full,ftyp("isom")));
        for(String blocked:new String[]{"moof","mvex","styp","sidx","ssix","mfra","tfra","pssh","sinf","schi","senc","schm","encv","enca","saiz","saio","uuid","junk"})rejects(join(full,box(blocked,new byte[4])));
        for(String blocked:new String[]{"sinf","pssh","senc","schm","saiz","junk"}) {
            byte[] moov=movie(0,false,false,box(blocked,new byte[4]));
            moov=movie(ftyp("isom").length+moov.length+8,false,false,box(blocked,new byte[4]));
            rejects(join(ftyp("isom"),moov,box("mdat",new byte[7])));
        }
        byte[] malformed=full.clone();put(malformed,typeAt(malformed,"mdat")-4,0);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"mdat")-4,0xffffffffL);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"avcC")-4,7);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stco")+12,1);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stco")+12,full.length);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stsz")+12,250001);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stsz")+16,0);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stsz")+16,100000);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stsc")+12,0);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stsc")+16,3);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stsc")+20,2);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"stts")+12,1);rejects(malformed);
        malformed=full.clone();put(malformed,typeAt(malformed,"url ")+4,0);rejects(malformed);
        malformed=full.clone();System.arraycopy("enca".getBytes(StandardCharsets.US_ASCII),0,malformed,typeAt(malformed,"stsd")+16,4);rejects(malformed);
        // mdat's declared size is shortened consistently; the sample table still reaches beyond it.
        malformed=Arrays.copyOf(full,full.length-1);put(malformed,typeAt(malformed,"mdat")-4,14);rejects(malformed);
        // Extended mdat header/co64 offsets are parsed without 32-bit truncation.
        byte[] type=ftyp("isom"),moov=movie(type.length+16,true,false,new byte[0]);
        accepts(join(type,extended("mdat",new byte[7]),moov));
        byte[] largeHeader=join(type,extended("mdat",new byte[7]),moov);put(largeHeader,type.length+8,0x80000000L);rejects(largeHeader);
        ByteArrayOutputStream tooMany=new ByteArrayOutputStream();tooMany.write(full);
        for(int i=0;i<20000;i++)tooMany.write(box("free",new byte[0]));rejects(tooMany.toByteArray());
        File sparse=File.createTempFile("xs-mp4-size-",".mp4");
        try{try(RandomAccessFile file=new RandomAccessFile(sparse,"rw")){file.setLength(MediaDownloadPolicy.MAX_VIDEO_BYTES+1);}
            try{Mp4Structure.validate(sparse);throw new AssertionError("Oversized MP4 accepted");}catch(IOException expected){check(true);}}
        finally{check(sparse.delete());}
        try{Mp4Structure.validate(null);throw new AssertionError();}catch(IOException expected){check(true);}

        for(String url:new String[]{"https://video.twimg.com/ext_tw_video/123/pu/vid/1280x720/AbC_123.mp4",
                "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/1280x720/AbC_123.mp4?tag=12",
                "https://video.twimg.com/amplify_video/456/vid/320x180/hello.mp4",
                "https://video.twimg.com/amplify_video/456/vid/avc1/1920x1080/hello.mp4?tag=0",
                "https://video.twimg.com/tweet_video/animated_test.mp4"})check(url.equals(MediaDownloadPolicy.validateVideo(POST,url)));
        for(String url:new String[]{null,"","blob:https://x.com/id","file:///tmp/video.mp4","content://video/id",
                "http://video.twimg.com/tweet_video/a.mp4","https://127.0.0.1/tweet_video/a.mp4","https://localhost/tweet_video/a.mp4",
                "https://video.twimg.com.evil.test/tweet_video/a.mp4","https://user@video.twimg.com/tweet_video/a.mp4",
                "https://video.twimg.com:443/tweet_video/a.mp4","https://video.twimg.com/tweet_video/a.mp4#part",
                "https://video.twimg.com/tweet_video/a.m3u8","https://video.twimg.com/tweet_video/a.mpd","https://video.twimg.com/tweet_video/a.m4s",
                "https://video.twimg.com/ext_tw_video/1/pu/vid/0x720/a.mp4","https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/../a.mp4",
                "https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/%61.mp4","https://video.twimg.com/tweet_video/a.mp4?",
                "https://video.twimg.com/tweet_video/a.mp4?tag=","https://video.twimg.com/tweet_video/a.mp4?tag=1&tag=2",
                "https://video.twimg.com/tweet_video/a.mp4?token=private","https://video.twimg.com/tweet_video/a.mp4?tag=1&token=private",
                "https://pbs.twimg.com/tweet_video/a.mp4","https://video.twimg.com/other/a.mp4"})rejectsUrl(POST,url);
        for(String post:new String[]{null,"https://x.com/home","http://x.com/u/status/1","https://x.com:443/u/status/1",
                "https://x.com/u/status/1#fragment","https://evil.test/u/status/1","https://user@x.com/u/status/1"})rejectsUrl(post,"https://video.twimg.com/tweet_video/a.mp4");
        check(MediaDownloadPolicy.completeLength(-1,MediaDownloadPolicy.MAX_VIDEO_BYTES,MediaDownloadPolicy.MAX_VIDEO_BYTES));
        check(MediaDownloadPolicy.completeLength(21,21,MediaDownloadPolicy.MAX_VIDEO_BYTES));
        check(!MediaDownloadPolicy.completeLength(21,20,MediaDownloadPolicy.MAX_VIDEO_BYTES));
        check(!MediaDownloadPolicy.completeLength(-2,20,MediaDownloadPolicy.MAX_VIDEO_BYTES));
        check(!MediaDownloadPolicy.completeLength(-1,MediaDownloadPolicy.MAX_VIDEO_BYTES+1,MediaDownloadPolicy.MAX_VIDEO_BYTES));
        check(!MediaDownloadPolicy.completeLength(-1,1,0));check(!MediaDownloadPolicy.completeLength(-1,1,Long.MAX_VALUE));
        check(!MediaDownloadPolicy.completeLength(-1,MediaDownloadPolicy.MAX_BYTES+1));
        // Optional caller-provided real fixture lets developers compare structure and codec validation.
        for(String path:args){Mp4Structure.validate(new File(path));check(true);}
        System.out.println("PASS "+assertions+" MP4 structure/video policy assertions");
    }
}
