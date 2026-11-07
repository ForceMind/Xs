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
        byte[] backup=bytes.toByteArray();LibraryBackupCodec.Data read=LibraryBackupCodec.read(new ByteArrayInputStream(backup));
        yes(read.header().schemaVersion==4&&read.header().createdAtMs==100);eq("私有笔记",read.header().label);yes(read.records().size()==30);
        eq("中文 😀 \"quote\"\nnext",read.records().get(0).values.get("body"));
        try{read.records().clear();throw new AssertionError("Mutable records");}catch(UnsupportedOperationException expected){checks++;}
        try{read.records().get(0).values.put("body","tamper");throw new AssertionError("Mutable fields");}catch(UnsupportedOperationException expected){checks++;}
        String raw=new String(backup,StandardCharsets.UTF_8);
        reject(raw.replace("中文","损坏").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"version\":1","\"version\":2").getBytes(StandardCharsets.UTF_8));
        reject(raw.replace("\"schema\":4","\"schema\":5").getBytes(StandardCharsets.UTF_8));
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
    private static final class Discard extends OutputStream {public void write(int value){}public void write(byte[] b,int o,int n){}}
    private static void reject(byte[] bytes)throws Exception{try{LibraryBackupCodec.read(new ByteArrayInputStream(bytes));throw new AssertionError("Invalid backup accepted");}catch(IllegalArgumentException|IOException expected){yes(!expected.getMessage().contains("中文"));}}
    private static void yes(boolean value){if(!value)throw new AssertionError("Backup assertion failed");checks++;}
    private static void eq(Object expected,Object actual){yes(Objects.equals(expected,actual));}
}
