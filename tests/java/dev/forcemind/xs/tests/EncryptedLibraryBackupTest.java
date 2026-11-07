package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.EncryptedLibraryBackup;
import dev.forcemind.xs.core.EncryptedLibraryBackup.AssetSource;
import dev.forcemind.xs.core.EncryptedLibraryBackup.Payload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Byte-level adversarial checks plus a 64 MiB streaming roundtrip in a 32 MiB JVM. */
public final class EncryptedLibraryBackupTest {
    private static int checks,sequence;
    private static final char[] PASSWORD="备份口令 🔐 local-only".toCharArray();
    private static File dir;
    public static void main(String[] args)throws Exception {
        dir=Files.createTempDirectory("xs-encrypted-backup-test-").toFile();
        try {
            if(args.length==1&&args[0].equals("--memory")){memoryRoundTrip();System.out.println("PASS bounded-memory encrypted stream");return;}
            encryption();payload();limits();
            Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath(),"-Xmx32m","-cp",System.getProperty("java.class.path"),EncryptedLibraryBackupTest.class.getName(),"--memory").inheritIO().start();
            yes(child.waitFor(60,TimeUnit.SECONDS));yes(child.exitValue()==0);
            System.out.println("PASS "+checks+" encrypted backup/container assertions");
        }finally{remove(dir);}
    }
    private static void encryption()throws Exception {
        byte[] plain=pattern(EncryptedLibraryBackup.CHUNK_BYTES*2+123);char[] saved=PASSWORD.clone();
        byte[] encoded=encrypt(plain,PASSWORD);yes(Arrays.equals(PASSWORD,saved));
        yes(encoded.length==EncryptedLibraryBackup.HEADER_BYTES+plain.length+4*EncryptedLibraryBackup.TAG_BYTES);
        File restored=fresh("roundtrip");yes(EncryptedLibraryBackup.decryptToVerifiedFile(new ByteArrayInputStream(encoded),restored,PASSWORD)==plain.length);
        yes(Arrays.equals(plain,Files.readAllBytes(restored.toPath())));
        byte[] again=encrypt(plain,PASSWORD);yes(!Arrays.equals(encoded,again));
        yes(!Arrays.equals(Arrays.copyOfRange(encoded,16,44),Arrays.copyOfRange(again,16,44)));
        rejectEncrypted(encoded,"incorrect password".toCharArray());
        for(int offset:new int[]{0,8,12,16,32,44,52,56,encoded.length-1}){byte[] corrupt=encoded.clone();corrupt[offset]^=1;rejectEncrypted(corrupt,PASSWORD);}
        byte[] truncated=Arrays.copyOf(encoded,encoded.length-16);rejectEncrypted(truncated,PASSWORD);
        rejectEncrypted(Arrays.copyOf(encoded,encoded.length-1),PASSWORD);
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);rejectEncrypted(trailing,PASSWORD);
        int block=EncryptedLibraryBackup.CHUNK_BYTES+EncryptedLibraryBackup.TAG_BYTES;
        byte[] swapped=encoded.clone();System.arraycopy(encoded,56+block,swapped,56,block);System.arraycopy(encoded,56,swapped,56+block,block);rejectEncrypted(swapped,PASSWORD);
        byte[] duplicate=encoded.clone();System.arraycopy(encoded,56,duplicate,56+block,block);rejectEncrypted(duplicate,PASSWORD);
        byte[] huge=encoded.clone();ByteBuffer.wrap(huge).putLong(44,EncryptedLibraryBackup.MAX_PLAINTEXT_BYTES+1);rejectEncrypted(huge,PASSWORD);
        byte[] negative=encoded.clone();ByteBuffer.wrap(negative).putLong(44,-1);rejectEncrypted(negative,PASSWORD);
        byte[] invalidChunks=encoded.clone();ByteBuffer.wrap(invalidChunks).putInt(52,EncryptedLibraryBackup.CHUNK_BYTES+1);rejectEncrypted(invalidChunks,PASSWORD);
        File existing=fresh("existing");Files.write(existing.toPath(),"existing data".getBytes(StandardCharsets.UTF_8));
        try{EncryptedLibraryBackup.decryptToVerifiedFile(new ByteArrayInputStream(encoded),existing,PASSWORD);bad("overwrote stage");}catch(IOException expected){yes(new String(Files.readAllBytes(existing.toPath()),StandardCharsets.UTF_8).equals("existing data"));}
        File directory=fresh("directory");yes(directory.mkdir());try{EncryptedLibraryBackup.decryptToVerifiedFile(new ByteArrayInputStream(encoded),directory,PASSWORD);bad("directory replaced");}catch(IOException expected){yes(directory.isDirectory());}
        byte[] empty=encrypt(new byte[0],PASSWORD);File emptyStage=fresh("empty");yes(empty.length==72);yes(EncryptedLibraryBackup.decryptToVerifiedFile(new ByteArrayInputStream(empty),emptyStage,PASSWORD)==0);rejectEncrypted(empty,"wrong empty password".toCharArray());
        rejectEncrypted(Arrays.copyOf(empty,56),PASSWORD);
        final boolean[] inClosed={false},outClosed={false};
        InputStream input=new ByteArrayInputStream(plain){@Override public void close(){inClosed[0]=true;}};
        OutputStream output=new ByteArrayOutputStream(){@Override public void close(){outClosed[0]=true;}};
        EncryptedLibraryBackup.encrypt(input,output,PASSWORD,plain.length);yes(!inClosed[0]&&!outClosed[0]);
        InputStream encryptedInput=new ByteArrayInputStream(encoded){@Override public void close(){inClosed[0]=true;}};
        EncryptedLibraryBackup.decryptToVerifiedFile(encryptedInput,fresh("stream-owned"),PASSWORD);yes(!inClosed[0]);
        byte[] declaredShort=pattern(400);ByteArrayOutputStream incomplete=new ByteArrayOutputStream();
        try{EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(declaredShort),incomplete,PASSWORD,399);bad("source longer than declared");}catch(IOException expected){checks++;}
        rejectEncrypted(incomplete.toByteArray(),PASSWORD);
        incomplete.reset();try{EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(declaredShort),incomplete,PASSWORD,401);bad("source shorter than declared");}catch(IOException expected){checks++;}
        rejectEncrypted(incomplete.toByteArray(),PASSWORD);
        yes(Arrays.equals(PASSWORD,saved));
    }
    private static void payload()throws Exception {
        byte[] metadata="{\"metadata\":\"中文 🚀\"}\n".getBytes(StandardCharsets.UTF_8),a=pattern(98304),b=pattern(4097);
        String descriptor="{\"note\":\"../../outside stays opaque text, 视频\"}";
        List<AssetSource> assets=Arrays.asList(source(descriptor,a),source("{\"kind\":\"IMAGE\"}",b));
        ByteArrayOutputStream out=new ByteArrayOutputStream();EncryptedLibraryBackup.writePayload(out,metadata,assets);byte[] bytes=out.toByteArray();
        yes(bytes.length==EncryptedLibraryBackup.payloadLength(metadata,assets));
        File stage=stageDir();Payload payload=EncryptedLibraryBackup.readPayload(new ByteArrayInputStream(bytes),stage);
        yes(payload.assets().size()==2);yes(Arrays.equals(metadata,payload.metadata()));
        byte[] callerMetadata=payload.metadata();callerMetadata[0]=0;yes(Arrays.equals(metadata,payload.metadata()));
        yes(payload.assets().get(0).descriptorJson.equals(descriptor));yes(payload.assets().get(0).length==a.length);
        yes(Arrays.equals(a,Files.readAllBytes(payload.assets().get(0).file.toPath())));yes(Arrays.equals(b,Files.readAllBytes(payload.assets().get(1).file.toPath())));
        for(EncryptedLibraryBackup.AssetEntry entry:payload.assets())yes(entry.file.getCanonicalFile().getParentFile().equals(stage.getCanonicalFile()));
        try{payload.assets().clear();bad("mutable asset list");}catch(UnsupportedOperationException expected){checks++;}
        payload.close();yes(stage.list().length==0);payload.close();yes(stage.list().length==0);
        int ordinal=16+metadata.length,description=ordinal+8,assetLength=description+descriptor.getBytes(StandardCharsets.UTF_8).length,assetHash=assetLength+8,assetBytes=assetHash+32;
        for(int offset:new int[]{0,ordinal,description,assetHash,assetBytes,bytes.length-1}){byte[] corrupt=bytes.clone();corrupt[offset]^=(offset==description?(byte)0xff:1);rejectPayload(corrupt);}
        byte[] badOrdinal=bytes.clone();ByteBuffer.wrap(badOrdinal).putInt(ordinal,1);rejectPayload(badOrdinal);
        byte[] badCount=bytes.clone();ByteBuffer.wrap(badCount).putInt(12+metadata.length,501);rejectPayload(badCount);
        byte[] tooBig=bytes.clone();ByteBuffer.wrap(tooBig).putLong(assetLength,EncryptedLibraryBackup.MAX_ASSET_BYTES+1);rejectPayload(tooBig);
        byte[] hugeMetadata=bytes.clone();ByteBuffer.wrap(hugeMetadata).putInt(8,EncryptedLibraryBackup.MAX_METADATA_BYTES+1);rejectPayload(hugeMetadata);
        byte[] hugeDescriptor=bytes.clone();ByteBuffer.wrap(hugeDescriptor).putInt(ordinal+4,EncryptedLibraryBackup.MAX_DESCRIPTOR_BYTES+1);rejectPayload(hugeDescriptor);
        rejectPayload(Arrays.copyOf(bytes,bytes.length-1));rejectPayload(Arrays.copyOf(bytes,assetBytes+10));rejectPayload(Arrays.copyOf(bytes,bytes.length+1));rejectPayload("PK\003\004../outside".getBytes(StandardCharsets.UTF_8));
        File nonempty=stageDir(),sentinel=new File(nonempty,"keep");Files.write(sentinel.toPath(),a);
        try{EncryptedLibraryBackup.readPayload(new ByteArrayInputStream(bytes),nonempty);bad("nonempty stage accepted");}catch(IOException expected){yes(sentinel.exists()&&Arrays.equals(a,Files.readAllBytes(sentinel.toPath())));yes(nonempty.list().length==1);}
        final boolean[] closed={false};AssetSource wrongHash=new AssetSource("{}",a.length,hash(b),()->new ByteArrayInputStream(a){@Override public void close(){closed[0]=true;}});
        out.reset();try{EncryptedLibraryBackup.writePayload(out,metadata,Collections.singletonList(wrongHash));bad("wrong source hash accepted");}catch(IOException expected){yes(closed[0]);}rejectPayload(out.toByteArray());
        AssetSource shortSource=new AssetSource("{}",a.length+1,hash(a),()->new ByteArrayInputStream(a));out.reset();try{EncryptedLibraryBackup.writePayload(out,metadata,Collections.singletonList(shortSource));bad("truncated source accepted");}catch(IOException expected){checks++;}rejectPayload(out.toByteArray());
        AssetSource longSource=new AssetSource("{}",a.length-1,hash(a),()->new ByteArrayInputStream(a));out.reset();try{EncryptedLibraryBackup.writePayload(out,metadata,Collections.singletonList(longSource));bad("extra source accepted");}catch(IOException expected){checks++;}rejectPayload(out.toByteArray());
        out.reset();EncryptedLibraryBackup.writePayload(out,metadata,Collections.emptyList());try(Payload none=EncryptedLibraryBackup.readPayload(new ByteArrayInputStream(out.toByteArray()),stageDir())){yes(none.assets().isEmpty());yes(Arrays.equals(metadata,none.metadata()));}
    }
    private static void limits()throws Exception {
        for(char[] password:new char[][]{null,new char[7],new char[1025],new char[]{'a','b','c','d','e','f','g','\ud800'}})try{EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),password,0);bad("password policy ignored");}catch(IOException expected){checks++;}
        try{EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),PASSWORD,EncryptedLibraryBackup.MAX_PLAINTEXT_BYTES+1);bad("plaintext budget ignored");}catch(IOException expected){checks++;}
        byte[] metadata={1};AssetSource huge=new AssetSource("{}",EncryptedLibraryBackup.MAX_ASSET_BYTES,"0000000000000000000000000000000000000000000000000000000000000000",()->{throw new AssertionError("Source opened despite invalid budget");});
        try{EncryptedLibraryBackup.payloadLength(metadata,Collections.nCopies(6,huge));bad("total payload budget ignored");}catch(IOException expected){checks++;}
        try{EncryptedLibraryBackup.payloadLength(metadata,Collections.nCopies(501,huge));bad("asset count ignored");}catch(IOException expected){checks++;}
        try{new AssetSource("{}",EncryptedLibraryBackup.MAX_ASSET_BYTES+1,huge.sha256Hex,()->null);bad("asset size ignored");}catch(IOException expected){checks++;}
        try{new AssetSource(repeat("中",3000),1,huge.sha256Hex,()->null);bad("descriptor UTF8 budget ignored");}catch(IOException expected){checks++;}
        try{new AssetSource("\ud800",1,huge.sha256Hex,()->null);bad("invalid descriptor unicode accepted");}catch(IOException expected){checks++;}
        try{new AssetSource("{}",1,"../path",()->null);bad("invalid hash accepted");}catch(IOException expected){checks++;}
        Thread.currentThread().interrupt();try{EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(new byte[]{1}),new ByteArrayOutputStream(),PASSWORD,1);bad("interruption ignored");}catch(IOException expected){yes(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
    }
    private static void memoryRoundTrip()throws Exception {
        long size=64L*1024*1024;File ciphertext=fresh("large-encrypted"),plain=fresh("large-verified");
        try(OutputStream out=new FileOutputStream(ciphertext)){EncryptedLibraryBackup.encrypt(new PatternInput(size),out,PASSWORD,size);}
        try(InputStream in=new FileInputStream(ciphertext)){yes(EncryptedLibraryBackup.decryptToVerifiedFile(in,plain,PASSWORD)==size);}
        byte[] buffer=new byte[65536];long offset=0;try(InputStream in=new FileInputStream(plain)){int read;while((read=in.read(buffer))!=-1){for(int i=0;i<read;i++)if(buffer[i]!=(byte)((offset+i)*31+7))bad("large stream corruption");offset+=read;}}yes(offset==size);
    }
    private static final class PatternInput extends InputStream {
        private final long length;private long position;PatternInput(long length){this.length=length;}
        @Override public int read(){return position>=length?-1:(int)(((position++)*31+7)&255);}
        @Override public int read(byte[] bytes,int offset,int count){if(position>=length)return -1;count=(int)Math.min(count,length-position);for(int i=0;i<count;i++)bytes[offset+i]=(byte)((position+i)*31+7);position+=count;return count;}
    }
    private static AssetSource source(String descriptor,byte[] bytes)throws Exception{return new AssetSource(descriptor,bytes.length,hash(bytes),()->new ByteArrayInputStream(bytes));}
    private static byte[] encrypt(byte[] bytes,char[] password)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(bytes),out,password,bytes.length);return out.toByteArray();}
    private static void rejectEncrypted(byte[] bytes,char[] password)throws Exception{File stage=fresh("reject");try{EncryptedLibraryBackup.decryptToVerifiedFile(new ByteArrayInputStream(bytes),stage,password);bad("invalid ciphertext accepted");}catch(IOException expected){yes(!stage.exists());yes(!expected.getMessage().contains(new String(PASSWORD)));}}
    private static void rejectPayload(byte[] bytes)throws Exception{File stage=stageDir();try{EncryptedLibraryBackup.readPayload(new ByteArrayInputStream(bytes),stage);bad("invalid payload accepted");}catch(IOException expected){yes(stage.list().length==0);}}
    private static byte[] pattern(int length){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)(i*31+7);return data;}
    private static String hash(byte[] bytes)throws Exception{StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
    private static File fresh(String prefix){return new File(dir,prefix+"-"+(++sequence));}
    private static File stageDir()throws IOException{File stage=fresh("assets");if(!stage.mkdir())throw new IOException("Cannot create test stage");return stage;}
    private static void remove(File file)throws IOException{if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}if(file.exists()&&!file.delete())throw new IOException("Cannot remove test fixture");}
    private static void yes(boolean value){if(!value)bad("Encrypted backup assertion "+(checks+1));checks++;}
    private static void bad(String message){throw new AssertionError(message);}
}
