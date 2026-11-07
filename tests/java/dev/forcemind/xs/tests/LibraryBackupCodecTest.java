package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.LibraryBackupCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real byte-level portability checks, not a SQLite or Android substitute. */
public final class LibraryBackupCodecTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        LinkedHashMap<String,Object> values=new LinkedHashMap<>();values.put("post_id","123");values.put("body","中文 😀 \"quote\"\nnext");values.put("published_at_ms",null);
        LibraryBackupCodec.Record row=new LibraryBackupCodec.Record("posts",values);values.put("body","changed after construction");
        LibraryBackupCodec.Header header=new LibraryBackupCodec.Header(4,100,"私有笔记");
        List<LibraryBackupCodec.Record> records=new ArrayList<>();for(int i=0;i<30;i++)records.add(row);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();LibraryBackupCodec.write(bytes,header,records);
        assetDescriptorRoundTrip();
        byte[] backup=bytes.toByteArray();LibraryBackupCodec.Data read=LibraryBackupCodec.read(new ByteArrayInputStream(backup));
        yes(read.header().schemaVersion==4&&read.header().createdAtMs==100);eq("私有笔记",read.header().label);yes(read.records().size()==30);
        eq("中文 😀 \"quote\"\nnext",read.records().get(0).values.get("body"));
        try{read.records().clear();throw new AssertionError("Mutable records");}catch(UnsupportedOperationException expected){checks++;}
        try{read.records().get(0).values.put("body","tamper");throw new AssertionError("Mutable fields");}catch(UnsupportedOperationException expected){checks++;}
        String raw=new String(backup,StandardCharsets.UTF_8);
        reject(raw.replace("中文","损坏").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"version\":1","\"version\":2").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"schema\":4","\"schema\":6").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"schema\":4","\"schema\":4294967300").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"version\":1","\"version\":1.0").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"version\":1","\"version\":1,\"version\":1").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"table\":\"posts\"","\"table\":\"posts\",\"table\":\"posts\"").getBytes(StandardCharsets.UTF_8));
        reject((raw+"{}\n").getBytes(StandardCharsets.UTF_8));reject((raw+"\n").getBytes(StandardCharsets.UTF_8));
        reject(Arrays.copyOf(backup,backup.length-1));reject("PK\003\004../evil".getBytes(StandardCharsets.UTF_8));
        reject(raw.substring(0,raw.lastIndexOf("{\"end\"")).getBytes(StandardCharsets.UTF_8));
        byte[] invalidUtf=backup.clone();invalidUtf[0]=(byte)0xff;reject(invalidUtf);
        try{new LibraryBackupCodec.Record("../posts",row.values);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        LinkedHashMap<String,Object> coercion=new LinkedHashMap<>();coercion.put("id",1.0);
        try{new LibraryBackupCodec.Record("posts",coercion);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        coercion.put("id",true);try{new LibraryBackupCodec.Record("posts",coercion);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        coercion.put("id","\ud800");try{new LibraryBackupCodec.Record("posts",coercion);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        List<LibraryBackupCodec.Record> tooMany=Collections.nCopies(LibraryBackupCodec.MAX_RECORDS+1,row);
        try{LibraryBackupCodec.write(new Discard(),header,tooMany);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        LinkedHashMap<String,Object> large=new LinkedHashMap<>();char[] chars=new char[16000];Arrays.fill(chars,'x');large.put("body",new String(chars));
        try{LibraryBackupCodec.write(new Discard(),header,Collections.nCopies(1100,new LibraryBackupCodec.Record("posts",large)));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        byte[] hugeLine=new byte[LibraryBackupCodec.MAX_LINE_BYTES+2];Arrays.fill(hugeLine,(byte)'x');hugeLine[hugeLine.length-1]='\n';reject(hugeLine);
        final boolean[] closed={false};ByteArrayInputStream input=new ByteArrayInputStream(backup){@Override public void close(){closed[0]=true;}};
        LibraryBackupCodec.read(input);yes(!closed[0]);ByteArrayOutputStream output=new ByteArrayOutputStream(){@Override public void close(){closed[0]=true;}};
        LibraryBackupCodec.write(output,header,records);yes(!closed[0]);
        LibraryBackupCodec.write(new Discard(),new LibraryBackupCodec.Header(3,1,"v3"),Collections.emptyList());checks++;
        System.out.println("PASS "+checks+" bounded backup codec assertions");
    }
    /** Exercise the exact completed-resource descriptor shape used by the native full-backup path. */
    private static void assetDescriptorRoundTrip()throws Exception {
        LinkedHashMap<String,Object> descriptor=new LinkedHashMap<>();
        descriptor.put("source_id","12345678-1234-1234-1234-123456789abc");
        descriptor.put("post_url","https://x.com/i/web/status/123");descriptor.put("media_kind","IMAGE");
        descriptor.put("mime","image/png");descriptor.put("bytes",3L);
        descriptor.put("sha256",String.join("",Collections.nCopies(64,"0")));
        descriptor.put("category","图片 📷");descriptor.put("created_ms",1000L);
        for(String field:Arrays.asList("duration_ms","width","height","video_tracks","audio_tracks","rotation_degrees"))descriptor.put(field,null);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        LibraryBackupCodec.write(out,new LibraryBackupCodec.Header(3,1,"asset"),Collections.singletonList(new LibraryBackupCodec.Record("assets",descriptor)));
        LibraryBackupCodec.Data decoded=LibraryBackupCodec.read(new ByteArrayInputStream(out.toByteArray()));
        yes(decoded.records().size()==1);eq("assets",decoded.records().get(0).table);eq(descriptor,decoded.records().get(0).values);
        for(String key:new String[]{"1sha256","sha-256","sha/256","sha.256","sha256;DROP","SHA256","",String.join("",Collections.nCopies(41,"a"))}) {
            LinkedHashMap<String,Object> invalid=new LinkedHashMap<>();invalid.put(key,"value");
            try{new LibraryBackupCodec.Record("assets",invalid);throw new AssertionError("Unsafe field name accepted");}catch(IllegalArgumentException expected){checks++;}
        }
    }
    private static final class Discard extends OutputStream {public void write(int value){}public void write(byte[] b,int o,int n){}}
    private static void reject(byte[] bytes)throws Exception{try{LibraryBackupCodec.read(new ByteArrayInputStream(bytes));throw new AssertionError("Invalid backup accepted");}catch(IllegalArgumentException|IOException expected){yes(!expected.getMessage().contains("中文"));}}
    private static void yes(boolean value){if(!value)throw new AssertionError("Backup assertion failed");checks++;}
    private static void eq(Object expected,Object actual){yes(Objects.equals(expected,actual));}
}
