package dev.forcemind.xs.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded structural admission for one complete, self-contained H.264/AAC MP4.
 * Rejects fragments, encryption, external references and sample ranges outside
 * mdat. Codec configuration/sample bytes still require platform decode checks.
 * This class never fetches, decrypts or rewrites any media.
 */
public final class Mp4Structure {
    private static final int MAX_BOXES = 20000;
    private static final long MAX_SAMPLES = 250000;
    private final RandomAccessFile input;
    private final long length;
    private int boxes;
    private long samples;
    private final List<Box> data = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();

    private static final class Box {
        final String type; final long payload, end;
        Box(String type, long payload, long end) { this.type=type; this.payload=payload; this.end=end; }
        long bytes() { return end-payload; }
    }
    private static final class Track {
        String handler, codec;
        Box sizes, chunks, mapping, timing;
        long count, constantSize, chunkCount;
        boolean co64, selfContained;
    }
    private Mp4Structure(RandomAccessFile input, long length) { this.input=input; this.length=length; }

    public static void validate(File file) throws IOException {
        if (file==null || !file.isFile() || file.length()<32 ||
                file.length()>MediaDownloadPolicy.MAX_VIDEO_BYTES) throw invalid();
        try (RandomAccessFile input=new RandomAccessFile(file,"r")) {
            new Mp4Structure(input,input.length()).movie();
        }
    }

    private void movie() throws IOException {
        Box movie=null; boolean fileType=false;
        for (Box box:children(0,length)) {
            switch (box.type) {
                case "ftyp": if(fileType || movie!=null || !data.isEmpty())throw invalid(); brands(box);fileType=true;break;
                case "moov": if(!fileType || movie!=null)throw invalid();movie=box;break;
                case "mdat": if(!fileType || box.bytes()==0 || data.size()>=32)throw invalid();data.add(box);break;
                case "free": case "skip": case "wide": break;
                default: throw invalid();
            }
        }
        if(!fileType || movie==null || data.isEmpty())throw invalid();
        boolean header=false;
        for(Box box:children(movie.payload,movie.end)) {
            switch(box.type) {
                case "mvhd": if(header)throw invalid();fixedHeader(box,100,112);header=true;break;
                case "trak": if(tracks.size()>=16)throw invalid();Track track=new Track();tracks.add(track);track(box,track);break;
                case "udta": case "meta": case "free": case "iods": break;
                default: throw invalid();
            }
        }
        if(!header || tracks.isEmpty())throw invalid();
        boolean video=false;
        for(Track track:tracks) { sampleRanges(track);video|="vide".equals(track.handler); }
        if(!video)throw invalid();
    }

    private void brands(Box box) throws IOException {
        if(box.bytes()<8 || box.bytes()>264 || (box.bytes()-8)%4!=0)throw invalid();
        boolean supported=false;
        for(long pos=box.payload;pos<box.end;pos+=4) {
            if(pos==box.payload+4)continue; // minor version
            String brand=fourcc(pos);
            if(oneOf(brand,"dash","msdh","msix","cmfc","cmfs","piff"))throw invalid();
            supported|=oneOf(brand,"isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","avc1","M4V ");
        }
        if(!supported)throw invalid();
    }

    private void track(Box parent, Track track) throws IOException {
        boolean header=false, media=false;
        for(Box box:children(parent.payload,parent.end)) {
            switch(box.type) {
                case "tkhd": if(header)throw invalid();fixedHeader(box,84,96);header=true;break;
                case "mdia": if(media)throw invalid();media=true;media(box,track);break;
                case "edts": for(Box edit:children(box.payload,box.end))if(!"elst".equals(edit.type))throw invalid();break;
                case "udta": case "meta": case "free": break;
                default: throw invalid();
            }
        }
        if(!header || !media)throw invalid();
    }

    private void media(Box parent, Track track) throws IOException {
        boolean header=false, mediaInfo=false;
        Box info=null;
        for(Box box:children(parent.payload,parent.end)) {
            switch(box.type) {
                case "mdhd":
                    if(header || box.bytes()<24)throw invalid();header=true;
                    long version=u32(box.payload)>>>24;
                    if(version>1 || box.bytes()<(version==1?36:24) || u32(box.payload+(version==1?20:12))==0)throw invalid();break;
                case "hdlr":
                    if(track.handler!=null || box.bytes()<24)throw invalid();
                    track.handler=fourcc(box.payload+8);if(!oneOf(track.handler,"vide","soun"))throw invalid();break;
                case "minf": if(mediaInfo)throw invalid();mediaInfo=true;info=box;break;
                case "free": break;
                default: throw invalid();
            }
        }
        if(!header || track.handler==null || info==null)throw invalid();
        boolean table=false;
        for(Box box:children(info.payload,info.end)) {
            switch(box.type) {
                case "dinf": references(box,track);break;
                case "stbl": if(table)throw invalid();table=true;table(box,track);break;
                case "vmhd": case "smhd": case "nmhd": case "free": break;
                default: throw invalid();
            }
        }
        if(!table || !track.selfContained)throw invalid();
    }

    private void references(Box parent, Track track) throws IOException {
        if(track.selfContained)throw invalid();
        List<Box> entries=children(parent.payload,parent.end);
        if(entries.size()!=1 || !"dref".equals(entries.get(0).type))throw invalid();
        Box dref=entries.get(0);
        if(dref.bytes()<8 || u32(dref.payload)!=0 || u32(dref.payload+4)!=1)throw invalid();
        entries=children(dref.payload+8,dref.end);
        if(entries.size()!=1 || !"url ".equals(entries.get(0).type) ||
                entries.get(0).bytes()!=4 || u32(entries.get(0).payload)!=1)throw invalid();
        track.selfContained=true;
    }

    private void table(Box parent, Track track) throws IOException {
        boolean description=false;
        for(Box box:children(parent.payload,parent.end)) {
            switch(box.type) {
                case "stsd": if(description)throw invalid();description=true;description(box,track);break;
                case "stsz": if(track.sizes!=null)throw invalid();track.sizes=box;break;
                case "stco": case "co64": if(track.chunks!=null)throw invalid();track.chunks=box;track.co64="co64".equals(box.type);break;
                case "stsc": if(track.mapping!=null)throw invalid();track.mapping=box;break;
                case "stts": if(track.timing!=null)throw invalid();track.timing=box;break;
                case "sgpd": case "sbgp":
                    if(box.bytes()<8 || "seig".equals(fourcc(box.payload+4)))throw invalid();break;
                case "ctts": case "stss": case "sdtp": case "free": break;
                default: throw invalid();
            }
        }
        if(!description || track.sizes==null || track.chunks==null || track.mapping==null || track.timing==null)throw invalid();
    }

    private void description(Box stsd, Track track) throws IOException {
        if(stsd.bytes()<8 || u32(stsd.payload)!=0 || u32(stsd.payload+4)!=1)throw invalid();
        List<Box> entries=children(stsd.payload+8,stsd.end);
        if(entries.size()!=1)throw invalid();
        Box entry=entries.get(0);track.codec=entry.type;
        boolean video="vide".equals(track.handler);
        int header=video?78:28;
        if(entry.bytes()<header || (video?!oneOf(entry.type,"avc1","avc3"):!"mp4a".equals(entry.type)) ||
                u16(entry.payload+6)!=1 || (!video && u16(entry.payload+8)!=0))throw invalid();
        boolean config=false;
        for(Box box:children(entry.payload+header,entry.end)) {
            if((video && "avcC".equals(box.type)) || (!video && "esds".equals(box.type))) {
                if(config || box.bytes()<(video?7:8))throw invalid();config=true;
            } else if(!oneOf(box.type,"btrt","pasp","colr","clap","fiel","free"))throw invalid();
        }
        if(!config)throw invalid();
    }

    private void sampleRanges(Track track) throws IOException {
        Box sizes=track.sizes, chunks=track.chunks, mapping=track.mapping, timing=track.timing;
        if(sizes.bytes()<12 || u32(sizes.payload)!=0)throw invalid();
        track.constantSize=u32(sizes.payload+4);track.count=u32(sizes.payload+8);
        if(track.count==0 || track.count>MAX_SAMPLES || (samples+=track.count)>MAX_SAMPLES ||
                sizes.bytes()!=12+(track.constantSize==0?track.count*4:0))throw invalid();
        track.chunkCount=counted(chunks,track.co64?8:4);
        long maps=counted(mapping,12), timed=counted(timing,8);
        if(track.chunkCount==0 || maps==0 || timed==0 || maps>track.chunkCount)throw invalid();
        long timedSamples=0;
        for(long index=0;index<timed;index++) {
            long count=u32(timing.payload+8+index*8), delta=u32(timing.payload+12+index*8);
            if(count==0 || delta==0 || (timedSamples+=count)>track.count)throw invalid();
        }
        if(timedSamples!=track.count)throw invalid();
        long sample=0, previousFirst=0;
        for(long map=0;map<maps;map++) {
            long pos=mapping.payload+8+map*12;
            long first=u32(pos), perChunk=u32(pos+4), description=u32(pos+8);
            long next=map+1<maps?u32(pos+12):track.chunkCount+1;
            if((map==0 && first!=1) || first<=previousFirst || first>track.chunkCount ||
                    next<=first || next>track.chunkCount+1 || perChunk==0 || perChunk>track.count || description!=1)throw invalid();
            previousFirst=first;
            for(long chunk=first;chunk<next;chunk++) {
                long offset=track.co64?u64(chunks.payload+8+(chunk-1)*8):u32(chunks.payload+8+(chunk-1)*4);
                long bytes=0;
                if(perChunk>track.count-sample)throw invalid();
                for(long i=0;i<perChunk;i++) {
                    long size=track.constantSize!=0?track.constantSize:u32(sizes.payload+12+sample*4);
                    if(size==0 || size>length || bytes>length-size)throw invalid();
                    bytes+=size;sample++;
                }
                if(offset>length-bytes || !inData(offset,offset+bytes))throw invalid();
            }
        }
        if(sample!=track.count)throw invalid();
    }

    private long counted(Box box, int stride) throws IOException {
        if(box.bytes()<8 || u32(box.payload)!=0)throw invalid();
        long count=u32(box.payload+4);
        if(count>MAX_SAMPLES || box.bytes()!=8+count*stride)throw invalid();
        return count;
    }
    private void fixedHeader(Box box,int versionZero,int versionOne)throws IOException {
        if(box.bytes()<4)throw invalid();
        long version=u32(box.payload)>>>24;
        if(version>1 || box.bytes()<(version==1?versionOne:versionZero))throw invalid();
    }
    private boolean inData(long from,long to) {
        for(Box box:data)if(from>=box.payload && to<=box.end)return true;
        return false;
    }
    private List<Box> children(long start,long end) throws IOException {
        List<Box> result=new ArrayList<>();
        for(long pos=start;pos<end;) {
            if(end-pos<8 || ++boxes>MAX_BOXES)throw invalid();
            long size=u32(pos);String type=fourcc(pos+4);long header=8;
            if(oneOf(type,"moof","mvex","styp","sidx","ssix","mfra","tfra","pssh","sinf","schi","senc","schm","encv","enca","saiz","saio"))throw invalid();
            if(size==1) { if(end-pos<16)throw invalid();size=u64(pos+8);header=16; }
            // A zero-sized mdat has no declared end, so this first version cannot prove completion.
            if(size<header || size>end-pos)throw invalid();
            result.add(new Box(type,pos+header,pos+size));pos+=size;
        }
        return result;
    }
    private long u32(long pos) throws IOException { input.seek(pos);return input.readInt()&0xffffffffL; }
    private long u64(long pos) throws IOException { input.seek(pos);long value=input.readLong();if(value<0)throw invalid();return value; }
    private int u16(long pos) throws IOException { input.seek(pos);return input.readUnsignedShort(); }
    private String fourcc(long pos) throws IOException { input.seek(pos);byte[] value=new byte[4];input.readFully(value);return new String(value,StandardCharsets.ISO_8859_1); }
    private static boolean oneOf(String value,String... choices) { for(String choice:choices)if(choice.equals(value))return true;return false; }
    private static IOException invalid() { return new IOException("Unsupported or incomplete clear MP4"); }
}
