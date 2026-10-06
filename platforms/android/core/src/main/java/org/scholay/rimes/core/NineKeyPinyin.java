package org.scholay.rimes.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Spelling constraints for the same digit-derived Rime prism used on iOS. */
public final class NineKeyPinyin {
    private static final String[] GROUPS={"abc","def","ghi","jkl","mno","pqrs","tuv","wxyz"};
    private final List<String> syllables;
    public NineKeyPinyin(Collection<String> source) {
        TreeSet<String> valid=new TreeSet<>();
        for(String value:source) if(value!=null && value.matches("[a-z]{1,8}")) valid.add(value);
        syllables=new ArrayList<>(valid);
    }
    public static String digits(String spelling) {
        StringBuilder out=new StringBuilder();
        for(char c:spelling.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean found=false;
            for(int i=0;i<GROUPS.length;i++) if(GROUPS[i].indexOf(c)>=0) { out.append(i+2); found=true; break; }
            if(!found) out.append(c);
        }
        return out.toString();
    }
    /**
     * Displays only the typed prefix of a compatible first candidate's reading.
     * This is a presentation value: raw input, candidate selection and Return stay unchanged.
     * A missing, annotated or incompatible reading leaves the engine's preedit intact.
     */
    public static String displayPreedit(String raw,String preedit,String firstCandidateReading) {
        if(preedit==null) return "";
        if(preedit.isEmpty() || raw==null || firstCandidateReading==null || firstCandidateReading.isEmpty()) return preedit;
        StringBuilder letters=new StringBuilder(firstCandidateReading.length());
        boolean[] boundaries=new boolean[firstCandidateReading.length()+1];
        boolean separated=false;
        for(int i=0;i<firstCandidateReading.length();i++) {
            char c=firstCandidateReading.charAt(i);
            if(c>='a' && c<='z') { letters.append(c); separated=false; }
            else if(c==' ' || c=='\'') {
                if(letters.length()==0 || separated || i==firstCandidateReading.length()-1) return preedit;
                boundaries[letters.length()]=true; separated=true;
            } else return preedit;
        }
        if(letters.length()==0) return preedit;
        String readingCode=digits(letters.toString()),rawCode=numericCode(raw);
        if(rawCode.isEmpty() || !readingCode.startsWith(rawCode)) return preedit;
        int position=0; separated=false;
        for(int i=0;i<raw.length();i++) {
            char c=raw.charAt(i);
            if(c==' ' || c=='\'') {
                if(position==0 || separated || !boundaries[position] && position!=letters.length()) return preedit;
                separated=true;
            } else {
                if(position>=letters.length()) return preedit;
                if(digit(c)) {
                    if(c!=readingCode.charAt(position)) return preedit;
                } else if(c>='a' && c<='z' || c>='A' && c<='Z') {
                    if(Character.toLowerCase(c)!=letters.charAt(position)) return preedit;
                } else return preedit;
                position++; separated=false;
            }
        }
        StringBuilder display=new StringBuilder(position+4);
        for(int i=0;i<position;i++) {
            if(boundaries[i]) display.append('\'');
            display.append(letters.charAt(i));
        }
        if(separated) display.append('\'');
        return display.toString();
    }
    private static String numericCode(String spelling) {
        String encoded=digits(spelling); StringBuilder result=new StringBuilder(encoded.length());
        for(int i=0;i<encoded.length();i++) if(digit(encoded.charAt(i))) result.append(encoded.charAt(i));
        return result.toString();
    }
    private static int start(String raw) { for(int i=0;i<raw.length();i++) if(digit(raw.charAt(i))) return i; return -1; }
    private static boolean digit(char c) { return c>='2' && c<='9'; }
    public List<String> choices(String raw) {
        List<String> result=new ArrayList<>(); int start=start(raw); if(start<0) return result;
        String pending=raw.substring(start);
        for(String syllable:syllables) if(pending.startsWith(digits(syllable))) result.add(syllable);
        result.sort((a,b) -> a.length()==b.length()?a.compareTo(b):Integer.compare(b.length(),a.length()));
        return result;
    }
    public String select(String syllable,String raw) {
        int start=start(raw); if(start<0 || !syllables.contains(syllable)) return null;
        String digits=digits(syllable); if(!raw.substring(start).startsWith(digits)) return null;
        String rest=raw.substring(start+digits.length());
        return raw.substring(0,start)+syllable+(rest.startsWith("'")?"":"'")+rest;
    }
    public static String backspace(String raw) {
        if(raw.isEmpty()) return raw;
        String rest=raw.substring(0,raw.length()-1);
        if(!raw.endsWith("'")) return rest;
        int boundary=rest.lastIndexOf('\'')+1; String last=rest.substring(boundary);
        return last.matches("[a-z]+")?rest.substring(0,boundary)+digits(last):rest;
    }
}
