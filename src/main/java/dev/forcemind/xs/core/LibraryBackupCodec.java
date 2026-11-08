package dev.forcemind.xs.core;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded metadata-only JSONL container. A checksum detects damage, not authorship. */
public final class LibraryBackupCodec {
    public static final int MAX_BYTES=16*1024*1024, MAX_RECORDS=20000, MAX_LINE_BYTES=65536;
    public static final String FORMAT="XsLibraryMetadata";
    private LibraryBackupCodec() {}
    public static final class Header {
        public final int schemaVersion; public final long createdAtMs; public final String label;
        public Header(int schemaVersion,long createdAtMs,String label) {
            if((schemaVersion!=3&&schemaVersion!=4&&schemaVersion!=5&&schemaVersion!=6)||createdAtMs<=0||label==null||label.length()>120)
                throw invalid();
            unicode(label);this.schemaVersion=schemaVersion;this.createdAtMs=createdAtMs;this.label=label;
        }
    }
    public static final class Record {
        public final String table; public final Map<String,Object> values;
        public Record(String table,Map<String,Object> values) {
            if(table==null||!table.matches("[a-z_]{1,40}")||values==null||values.isEmpty()||values.size()>32)
                throw invalid();
            LinkedHashMap<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<String,Object> field:values.entrySet()) {
                if(field.getKey()==null||!field.getKey().matches("[a-z_][a-z0-9_]{0,39}"))throw invalid();
                Object value=field.getValue();
                if(value!=null&&!(value instanceof Long)&&!(value instanceof String))throw invalid();
                if(value instanceof String){if(((String)value).length()>16000)throw invalid();unicode((String)value);}
                copy.put(field.getKey(),value);
            }
            this.table=table;this.values=Collections.unmodifiableMap(copy);
        }
    }
    public static final class Data {
        private final Header header; private final List<Record> records;
        private Data(Header header,List<Record> records) {
            this.header=header;this.records=Collections.unmodifiableList(new ArrayList<>(records));
        }
        public Header header(){return header;}
        public List<Record> records(){return records;}
    }
    /** The caller retains ownership of its stream. Partial output is never a valid backup. */
    public static void write(OutputStream out,Header header,Iterable<Record> records)throws IOException {
        if(out==null||header==null||records==null)throw invalid();
        MessageDigest hash=sha256();long[] written={0};
        LinkedHashMap<String,Object> start=new LinkedHashMap<>();
        start.put("format",FORMAT);start.put("version",1L);start.put("schema",(long)header.schemaVersion);
        start.put("created_at_ms",header.createdAtMs);start.put("label",header.label);
        line(out,start,hash,written);long count=0;
        for(Record row:records) {
            if(row==null||++count>MAX_RECORDS)throw invalid();
            LinkedHashMap<String,Object> item=new LinkedHashMap<>();item.put("table",row.table);item.put("values",row.values);
            line(out,item,hash,written);
        }
        LinkedHashMap<String,Object> end=new LinkedHashMap<>();end.put("end",true);end.put("records",count);end.put("sha256",hex(hash.digest()));
        line(out,end,null,written);out.flush();
    }
    public static Data read(InputStream in)throws IOException {
        if(in==null)throw invalid();
        if(!(in instanceof BufferedInputStream))in=new BufferedInputStream(in,8192);
        MessageDigest hash=sha256();long[] total={0};
        byte[] raw=readLine(in,total);if(raw==null)throw invalid();
        Map<String,Object> start=object(parse(raw));exact(start,"format","version","schema","created_at_ms","label");
        if(!FORMAT.equals(start.get("format"))||!Long.valueOf(1).equals(start.get("version")))throw invalid();
        Header header=new Header((int)number(start.get("schema")),number(start.get("created_at_ms")),string(start.get("label")));
        // Reject narrowing overflow (e.g. 2^32+3 is not schema 3).
        if(number(start.get("schema"))!=header.schemaVersion)throw invalid();
        hash.update(raw);hash.update((byte)'\n');List<Record> records=new ArrayList<>();
        while((raw=readLine(in,total))!=null) {
            Map<String,Object> item=object(parse(raw));
            if(item.containsKey("end")) {
                exact(item,"end","records","sha256");
                if(!Boolean.TRUE.equals(item.get("end"))||number(item.get("records"))!=records.size()||
                    !hex(hash.digest()).equals(string(item.get("sha256"))))throw invalid();
                if(readLine(in,total)!=null)throw invalid();
                return new Data(header,records);
            }
            exact(item,"table","values");
            if(records.size()>=MAX_RECORDS)throw invalid();
            records.add(new Record(string(item.get("table")),object(item.get("values"))));
            hash.update(raw);hash.update((byte)'\n');
        }
        throw invalid();
    }
    private static void line(OutputStream out,Map<String,Object> map,MessageDigest hash,long[] written)throws IOException {
        byte[] bytes=json(map).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_LINE_BYTES||written[0]+bytes.length+1>MAX_BYTES)throw invalid();
        out.write(bytes);out.write('\n');written[0]+=bytes.length+1;
        if(hash!=null){hash.update(bytes);hash.update((byte)'\n');}
    }
    private static byte[] readLine(InputStream in,long[] total)throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1) {
            if(++total[0]>MAX_BYTES)throw invalid();
            if(b=='\n'){if(line.size()==0)throw invalid();return line.toByteArray();}
            if(line.size()>=MAX_LINE_BYTES)throw invalid();line.write(b);
        }
        if(line.size()!=0)throw invalid(); // A record/trailer must finish with LF.
        return null;
    }
    private static Object parse(byte[] bytes)throws IOException {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return StrictJson.parse(text);
        }catch(CharacterCodingException failure){throw new IOException("Invalid backup UTF-8");}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map))throw invalid();return(Map<String,Object>)value;
    }
    private static long number(Object value){if(!(value instanceof Long))throw invalid();return(Long)value;}
    private static String string(Object value){if(!(value instanceof String))throw invalid();unicode((String)value);return(String)value;}
    private static void exact(Map<String,Object> map,String...keys){if(map.size()!=keys.length)throw invalid();for(String key:keys)if(!map.containsKey(key))throw invalid();}
    private static String json(Object value) {
        if(value==null)return "null";if(value instanceof Long||value instanceof Boolean)return value.toString();
        if(value instanceof String){
            String text=(String)value;unicode(text);StringBuilder out=new StringBuilder("\"");
            for(int i=0;i<text.length();i++){char c=text.charAt(i);switch(c){
                case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;
                case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;
                default:if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
            }}return out.append('"').toString();
        }
        if(value instanceof Map){StringBuilder out=new StringBuilder("{");boolean first=true;
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){if(!first)out.append(',');first=false;out.append(json(entry.getKey())).append(':').append(json(entry.getValue()));}
            return out.append('}').toString();}
        throw invalid();
    }
    private static void unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String hex(byte[] digest){StringBuilder out=new StringBuilder();for(byte value:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid or oversized Xs metadata backup");}
}
