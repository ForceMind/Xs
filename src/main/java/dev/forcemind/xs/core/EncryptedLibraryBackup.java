package dev.forcemind.xs.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-portable, bounded media container. No credentials, paths or compression are encoded.
 * Ciphertext is authenticated in fixed-size records so providers never buffer a whole video.
 * Decrypted bytes stay in a caller-selected PRIVATE stage until every record, final tag and EOF
 * have been checked. This class does not publish a library or trust asset descriptor fields.
 */
public final class EncryptedLibraryBackup {
    public static final int VERSION=1,HEADER_BYTES=56,CHUNK_BYTES=64*1024,TAG_BYTES=16;
    public static final int PBKDF2_ITERATIONS=600000,MAX_ASSETS=500,MAX_DESCRIPTOR_BYTES=8192;
    public static final int MAX_METADATA_BYTES=16*1024*1024;
    public static final long MAX_ASSET_BYTES=100L*1024*1024,MAX_PLAINTEXT_BYTES=512L*1024*1024;
    public static final long MAX_ENCRYPTED_BYTES=HEADER_BYTES+MAX_PLAINTEXT_BYTES+
        (MAX_PLAINTEXT_BYTES/CHUNK_BYTES+1)*TAG_BYTES;
    private static final byte[] MAGIC="XSENCBAK".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PAYLOAD_MAGIC="XSPAY001".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PAYLOAD_END="XSEND001".getBytes(StandardCharsets.US_ASCII);
    private EncryptedLibraryBackup() {}

    /** Streams remain owned by the caller. An incomplete export has no authenticated final tag. */
    public static void encrypt(InputStream plaintext,OutputStream encrypted,char[] password,long plaintextLength)throws IOException {
        if(plaintext==null||encrypted==null)throw invalid();checkLength(plaintextLength);checkPassword(password);
        byte[] salt=new byte[16],baseNonce=new byte[12],header=null,plain=new byte[CHUNK_BYTES];
        SecretKeySpec key=null;new SecureRandom().nextBytes(salt);new SecureRandom().nextBytes(baseNonce);
        try {
            header=ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(VERSION).putInt(PBKDF2_ITERATIONS)
                .put(salt).put(baseNonce).putLong(plaintextLength).putInt(CHUNK_BYTES).array();
            key=derive(password,salt);encrypted.write(header);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            long remaining=plaintextLength;int ordinal=0;
            while(remaining>0) {
                interrupted();int count=(int)Math.min(CHUNK_BYTES,remaining);readFully(plaintext,plain,count);
                // Withhold the last data record until the declared source length is exact.
                if(remaining==count&&plaintext.read()!=-1)throw invalid();
                byte[] encoded=crypt(cipher,Cipher.ENCRYPT_MODE,key,baseNonce,header,ordinal,count,false,plaintextLength,plain,count);
                try{encrypted.write(encoded);}finally{Arrays.fill(encoded,(byte)0);}
                Arrays.fill(plain,0,count,(byte)0);remaining-=count;ordinal++;
            }
            if(plaintextLength==0&&plaintext.read()!=-1)throw invalid();
            byte[] end=crypt(cipher,Cipher.ENCRYPT_MODE,key,baseNonce,header,ordinal,0,true,plaintextLength,new byte[0],0);
            try{encrypted.write(end);encrypted.flush();}finally{Arrays.fill(end,(byte)0);}
        }catch(GeneralSecurityException failure){throw authenticationFailure();}
        finally{wipe(key);Arrays.fill(salt,(byte)0);Arrays.fill(baseNonce,(byte)0);Arrays.fill(plain,(byte)0);if(header!=null)Arrays.fill(header,(byte)0);}
    }

    /** Only creates a NEW private stage. Existing files are never overwritten or deleted.
     * Returns authenticated plaintext length. Failure removes the newly created partial stage.
     */
    public static long decryptToVerifiedFile(InputStream encrypted,File privateStage,char[] password)throws IOException {
        if(encrypted==null||privateStage==null)throw invalid();checkPassword(password);
        if(!privateStage.createNewFile())throw new IOException("A new private staging file is required");
        boolean complete=false;byte[] header=new byte[HEADER_BYTES],salt=null,baseNonce=null,input=new byte[CHUNK_BYTES+TAG_BYTES];
        SecretKeySpec key=null;
        try {
            readFully(encrypted,header,HEADER_BYTES);ByteBuffer fields=ByteBuffer.wrap(header);
            byte[] magic=new byte[MAGIC.length];fields.get(magic);
            if(!Arrays.equals(magic,MAGIC)||fields.getInt()!=VERSION||fields.getInt()!=PBKDF2_ITERATIONS)throw invalid();
            salt=new byte[16];baseNonce=new byte[12];fields.get(salt);fields.get(baseNonce);
            long length=fields.getLong();checkLength(length);if(fields.getInt()!=CHUNK_BYTES)throw invalid();
            key=derive(password,salt);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            try(FileOutputStream out=new FileOutputStream(privateStage)) {
                long remaining=length;int ordinal=0;
                while(remaining>0) {
                    interrupted();int count=(int)Math.min(CHUNK_BYTES,remaining);readFully(encrypted,input,count+TAG_BYTES);
                    byte[] plain=crypt(cipher,Cipher.DECRYPT_MODE,key,baseNonce,header,ordinal,count,false,length,input,count+TAG_BYTES);
                    try{if(plain.length!=count)throw invalid();out.write(plain);}finally{Arrays.fill(plain,(byte)0);}
                    Arrays.fill(input,0,count+TAG_BYTES,(byte)0);remaining-=count;ordinal++;
                }
                readFully(encrypted,input,TAG_BYTES);
                byte[] end=crypt(cipher,Cipher.DECRYPT_MODE,key,baseNonce,header,ordinal,0,true,length,input,TAG_BYTES);
                try{if(end.length!=0||encrypted.read()!=-1)throw invalid();}finally{Arrays.fill(end,(byte)0);}
                out.getFD().sync();
            }
            complete=true;return length;
        }catch(GeneralSecurityException failure){throw authenticationFailure();}
        finally {
            wipe(key);Arrays.fill(header,(byte)0);Arrays.fill(input,(byte)0);
            if(salt!=null)Arrays.fill(salt,(byte)0);if(baseNonce!=null)Arrays.fill(baseNonce,(byte)0);
            if(!complete)removeOwnedStage(privateStage);
        }
    }
    private static SecretKeySpec derive(char[] password,byte[] salt)throws GeneralSecurityException {
        PBEKeySpec spec=new PBEKeySpec(password,salt,PBKDF2_ITERATIONS,256);SecretKey derived=null;byte[] encoded=null;
        try {derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec);encoded=derived.getEncoded();return new SecretKeySpec(encoded,"AES");}
        finally{spec.clearPassword();if(encoded!=null)Arrays.fill(encoded,(byte)0);if(derived!=null)try{derived.destroy();}catch(Exception ignored){/* Provider-owned key objects may not support destruction. */}}
    }
    private static byte[] crypt(Cipher cipher,int mode,SecretKeySpec key,byte[] baseNonce,byte[] header,
            int ordinal,int count,boolean terminal,long total,byte[] input,int length)throws GeneralSecurityException {
        byte[] nonce=baseNonce.clone();for(int i=0;i<4;i++)nonce[8+i]^=(byte)(ordinal>>>(24-i*8));
        byte[] aad=terminal?ByteBuffer.allocate(13).put((byte)'F').putInt(ordinal).putLong(total).array():
            ByteBuffer.allocate(9).put((byte)'D').putInt(ordinal).putInt(count).array();
        try {cipher.init(mode,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(header);cipher.updateAAD(aad);return cipher.doFinal(input,0,length);}
        finally{Arrays.fill(nonce,(byte)0);Arrays.fill(aad,(byte)0);}
    }
    private static void wipe(SecretKeySpec key){if(key!=null)try{key.destroy();}catch(Exception ignored){/* No guarantee for provider-internal key copies. */}}
    private static void checkLength(long length)throws IOException{if(length<0||length>MAX_PLAINTEXT_BYTES)throw invalid();}
    private static void checkPassword(char[] password)throws IOException {
        if(password==null||password.length<8||password.length>1024)throw new IOException("Backup password must contain 8 to 1024 characters");
        for(int i=0;i<password.length;i++){char c=password[i];if(Character.isHighSurrogate(c)){if(++i>=password.length||!Character.isLowSurrogate(password[i]))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}
    }

    public interface SourceFactory { InputStream open()throws IOException; }
    /** Descriptor is opaque to this transport; native code must validate its exact fields/types. */
    public static final class AssetSource {
        public final String descriptorJson,sha256Hex;public final long length;
        private final byte[] descriptor;private final SourceFactory source;
        public AssetSource(String descriptorJson,long length,String sha256Hex,SourceFactory source)throws IOException {
            if(source==null||length<=0||length>MAX_ASSET_BYTES||sha256Hex==null||!sha256Hex.matches("[0-9a-f]{64}"))throw invalid();
            this.descriptor=descriptor(descriptorJson);this.descriptorJson=descriptorJson;this.length=length;this.sha256Hex=sha256Hex;this.source=source;
        }
    }
    public static final class AssetEntry {
        public final String descriptorJson,sha256Hex;public final long length;public final File file;
        private AssetEntry(String descriptorJson,long length,String hash,File file){this.descriptorJson=descriptorJson;this.length=length;this.sha256Hex=hash;this.file=file;}
    }
    /** Owns only files it created in the supplied private staging directory. */
    public static final class Payload implements Closeable {
        private final byte[] metadata;private final List<AssetEntry> assets;
        private Payload(byte[] metadata,List<AssetEntry> assets){this.metadata=metadata;this.assets=Collections.unmodifiableList(new ArrayList<>(assets));}
        public byte[] metadata(){return metadata.clone();}
        public List<AssetEntry> assets(){return assets;}
        @Override public void close()throws IOException{IOException failure=null;for(AssetEntry asset:assets)try{removeOwnedStage(asset.file);}catch(IOException problem){failure=problem;}Arrays.fill(metadata,(byte)0);if(failure!=null)throw failure;}
    }
    public static long payloadLength(byte[] metadata,List<AssetSource> assets)throws IOException {
        if(metadata==null||metadata.length==0||metadata.length>MAX_METADATA_BYTES||assets==null||assets.size()>MAX_ASSETS)throw invalid();
        long length=8L+4+metadata.length+4+8;
        for(AssetSource asset:assets){if(asset==null)throw invalid();length+=4+4+asset.descriptor.length+8+32+asset.length;if(length>MAX_PLAINTEXT_BYTES)throw invalid();}
        return length;
    }
    /** Fixed ordinals and bounded lengths, no ZIP/paths. Each source is opened/closed once. */
    public static void writePayload(OutputStream out,byte[] metadata,List<AssetSource> assets)throws IOException {
        if(out==null||assets==null)throw invalid();List<AssetSource> snapshot=new ArrayList<>(assets);payloadLength(metadata,snapshot);
        DataOutputStream data=new DataOutputStream(out);data.write(PAYLOAD_MAGIC);data.writeInt(metadata.length);data.write(metadata);data.writeInt(snapshot.size());
        int ordinal=0;byte[] buffer=new byte[CHUNK_BYTES];
        try {
            for(AssetSource asset:snapshot) {
                interrupted();data.writeInt(ordinal++);data.writeInt(asset.descriptor.length);data.write(asset.descriptor);data.writeLong(asset.length);data.write(unhex(asset.sha256Hex));
                MessageDigest hash=sha256();
                try(InputStream source=asset.source.open()) {
                    if(source==null)throw invalid();copyExactly(source,data,asset.length,hash,buffer);if(source.read()!=-1||!asset.sha256Hex.equals(hex(hash.digest())))throw invalid();
                }
            }
            data.write(PAYLOAD_END);data.flush();
        }finally{Arrays.fill(buffer,(byte)0);}
    }
    /** Parse only after decryptToVerifiedFile succeeds. Never accepts archive-controlled filenames. */
    public static Payload readPayload(InputStream verifiedPlain,File privateEmptyStageDir)throws IOException {
        if(verifiedPlain==null||privateEmptyStageDir==null||!privateEmptyStageDir.isDirectory())throw invalid();
        String[] existing=privateEmptyStageDir.list();if(existing==null||existing.length!=0)throw new IOException("An empty private asset staging directory is required");
        CountingInputStream bounded=new CountingInputStream(verifiedPlain);DataInputStream data=new DataInputStream(bounded);
        List<AssetEntry> assets=new ArrayList<>();List<File> owned=new ArrayList<>();byte[] metadata=null,buffer=new byte[CHUNK_BYTES];boolean complete=false;
        try {
            byte[] magic=new byte[8];data.readFully(magic);if(!Arrays.equals(magic,PAYLOAD_MAGIC))throw invalid();
            int metadataSize=data.readInt();if(metadataSize<=0||metadataSize>MAX_METADATA_BYTES)throw invalid();
            metadata=new byte[metadataSize];data.readFully(metadata);int count=data.readInt();if(count<0||count>MAX_ASSETS)throw invalid();
            for(int ordinal=0;ordinal<count;ordinal++) {
                interrupted();if(data.readInt()!=ordinal)throw invalid();int descriptorSize=data.readInt();if(descriptorSize<=0||descriptorSize>MAX_DESCRIPTOR_BYTES)throw invalid();
                byte[] description=new byte[descriptorSize];data.readFully(description);String descriptor=decode(description);descriptor(descriptor);
                long length=data.readLong();if(length<=0||length>MAX_ASSET_BYTES||length>MAX_PLAINTEXT_BYTES-bounded.total)throw invalid();
                byte[] expected=new byte[32];data.readFully(expected);MessageDigest hash=sha256();
                File file=File.createTempFile("xs-asset-",".stage",privateEmptyStageDir);owned.add(file);
                try(FileOutputStream out=new FileOutputStream(file)){copyExactly(data,out,length,hash,buffer);out.getFD().sync();}
                if(!MessageDigest.isEqual(expected,hash.digest()))throw invalid();assets.add(new AssetEntry(descriptor,length,hex(expected),file));
            }
            data.readFully(magic);if(!Arrays.equals(magic,PAYLOAD_END)||data.read()!=-1)throw invalid();
            complete=true;return new Payload(metadata,assets);
        }finally {
            Arrays.fill(buffer,(byte)0);
            if(!complete){if(metadata!=null)Arrays.fill(metadata,(byte)0);IOException failure=null;for(File file:owned)try{removeOwnedStage(file);}catch(IOException problem){failure=problem;}if(failure!=null)throw failure;}
        }
    }
    private static byte[] descriptor(String value)throws IOException {
        if(value==null||value.isEmpty())throw invalid();byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_DESCRIPTOR_BYTES||!value.equals(decode(bytes)))throw invalid();return bytes;
    }
    private static String decode(byte[] bytes)throws IOException {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException malformed){throw invalid();}
    }
    private static final class CountingInputStream extends InputStream {
        private final InputStream source;private long total;
        CountingInputStream(InputStream source){this.source=source;}
        @Override public int read()throws IOException{int value=source.read();if(value!=-1&&++total>MAX_PLAINTEXT_BYTES)throw invalid();return value;}
        @Override public int read(byte[] bytes,int offset,int count)throws IOException{int read=source.read(bytes,offset,count);if(read>0&&(total+=read)>MAX_PLAINTEXT_BYTES)throw invalid();return read;}
    }
    private static void copyExactly(InputStream in,OutputStream out,long length,MessageDigest hash,byte[] buffer)throws IOException {
        long remaining=length;while(remaining>0){interrupted();int count=(int)Math.min(buffer.length,remaining);readFully(in,buffer,count);hash.update(buffer,0,count);out.write(buffer,0,count);Arrays.fill(buffer,0,count,(byte)0);remaining-=count;}
    }
    private static void readFully(InputStream in,byte[] bytes,int count)throws IOException {
        int offset=0;while(offset<count){int read=in.read(bytes,offset,count-offset);if(read<0)throw new EOFException("Incomplete encrypted library backup");if(read==0){int value=in.read();if(value<0)throw new EOFException("Incomplete encrypted library backup");bytes[offset++]=(byte)value;}else offset+=read;}
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    private static byte[] unhex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    private static void interrupted()throws IOException{if(Thread.currentThread().isInterrupted())throw new IOException("Backup operation interrupted");}
    private static void removeOwnedStage(File file)throws IOException {
        if(!file.exists())return;if(file.delete())return;
        // If deletion fails, remove plaintext bytes before reporting that cleanup was incomplete.
        try(FileOutputStream truncate=new FileOutputStream(file)){truncate.getFD().sync();}
        if(!file.delete())throw new IOException("Could not remove private backup staging file");
    }
    private static IOException authenticationFailure(){return new IOException("Backup authentication failed");}
    private static IOException invalid(){return new IOException("Invalid, incomplete or oversized encrypted library backup");}
}
