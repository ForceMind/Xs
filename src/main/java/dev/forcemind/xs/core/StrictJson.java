package dev.forcemind.xs.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded strict JSON reader, rejecting duplicate keys/trailing tokens/coercions. */
public final class StrictJson {
    private final String text; private int at;
    private StrictJson(String text) { this.text=text; }
    public static Object parse(String text) {
        if (text==null || text.length()>65536 ||
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw bad();
        StrictJson p=new StrictJson(text); Object result=p.value(0); p.space();
        if (p.at!=text.length()) throw bad(); return result;
    }
    private Object value(int depth) {
        if (depth>6) throw bad(); space(); if(at>=text.length()) throw bad();
        char c=text.charAt(at);
        if(c=='"')return string();
        if(c=='{') {
            at++; Map<String,Object> map=new LinkedHashMap<>(); space();
            if(take('}'))return map;
            do { space(); if(at>=text.length() || text.charAt(at)!='"')throw bad();
                String key=string(); space(); require(':'); if(map.containsKey(key))throw bad();
                map.put(key,value(depth+1)); if(map.size()>32)throw bad(); space();
                if(take('}'))return map; require(',');
            } while(true);
        }
        if(c=='[') {
            at++; List<Object> list=new ArrayList<>(); space(); if(take(']'))return list;
            do { list.add(value(depth+1)); if(list.size()>24)throw bad(); space();
                if(take(']'))return list; require(',');
            } while(true);
        }
        if(text.startsWith("true",at)){at+=4;return Boolean.TRUE;}
        if(text.startsWith("false",at)){at+=5;return Boolean.FALSE;}
        if(text.startsWith("null",at)){at+=4;return null;}
        int start=at; if(take('-') && at>=text.length())throw bad();
        if(take('0')) { if(at<text.length() && Character.isDigit(text.charAt(at)))throw bad(); }
        else { int begin=at; while(at<text.length() && text.charAt(at)>='0' && text.charAt(at)<='9')at++;
            if(at==begin)throw bad(); }
        boolean decimal=false;
        if(take('.')) { decimal=true; int begin=at; while(at<text.length() && Character.isDigit(text.charAt(at)))at++;
            if(at==begin)throw bad(); }
        if(at<text.length() && (text.charAt(at)=='e'||text.charAt(at)=='E')) {
            decimal=true;at++;if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;
            int begin=at;while(at<text.length()&&Character.isDigit(text.charAt(at)))at++;if(at==begin)throw bad();
        }
        try { if(!decimal)return Long.valueOf(text.substring(start,at));
            Double d=Double.valueOf(text.substring(start,at)); if(!Double.isFinite(d))throw bad();return d;
        } catch(NumberFormatException invalid){throw bad();}
    }
    private String string() {
        require('"'); StringBuilder s=new StringBuilder();
        while(at<text.length()) {
            char c=text.charAt(at++); if(c=='"')return s.toString(); if(c<32)throw bad();
            if(c=='\\') {
                if(at>=text.length())throw bad(); char e=text.charAt(at++);
                switch(e){case '"':case '\\':case '/':s.append(e);break;case 'b':s.append('\b');break;
                    case 'f':s.append('\f');break;case 'n':s.append('\n');break;case 'r':s.append('\r');break;
                    case 't':s.append('\t');break;case 'u':
                        if(at+4>text.length())throw bad(); try{s.append((char)Integer.parseInt(text.substring(at,at+4),16));}
                        catch(NumberFormatException x){throw bad();}at+=4;break;default:throw bad();}
            } else s.append(c);
        } throw bad();
    }
    private void space(){while(at<text.length()&&" \n\r\t".indexOf(text.charAt(at))>=0)at++;}
    private boolean take(char c){if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
    private void require(char c){if(!take(c))throw bad();}
    private static IllegalArgumentException bad(){return new IllegalArgumentException("Invalid observation JSON");}
}
