package org.scholay.rimes.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class NineKeyPreeditTest {
    @Test public void firstCandidateReadingReplacesNumericPreedit() {
        assertEquals("ni'hao",NineKeyPinyin.displayPreedit("64426","64 426","ni hao"));
    }
    @Test public void selectedSyllablesAndRemainingDigitsUseTheSameCode() {
        assertEquals("ni'hao",NineKeyPinyin.displayPreedit("ni'426","ni 426","ni hao"));
        assertEquals("ni'hao",NineKeyPinyin.displayPreedit("NI'426","NI 426","ni'hao"));
    }
    @Test public void incrementalJniTypingShowsOnlyLettersWhoseDigitsWereTyped() {
        String[] raw={"6","64","644","6442","64426"};
        String[] preedit={"6","64","64 4","64 42","64 426"};
        String[] reading={"n","ni","ni hao","ni ha","ni hao"};
        String[] display={"n","ni","ni'h","ni'ha","ni'hao"};
        for(int i=0;i<raw.length;i++) assertEquals(raw[i],display[i],NineKeyPinyin.displayPreedit(raw[i],preedit[i],reading[i]));
    }
    @Test public void longerReadingIsCutAtTheTypedPrefixIncludingAfterBackspace() {
        assertEquals("ni'ha",NineKeyPinyin.displayPreedit("6442","64 42","ni hao"));
        assertEquals("ni",NineKeyPinyin.displayPreedit("64","64","ni hao"));
    }
    @Test public void incompatibleCandidateCannotReplacePreedit() {
        assertEquals("64 52",NineKeyPinyin.displayPreedit("6452","64 52","ni hao"));
        assertEquals("6",NineKeyPinyin.displayPreedit("6","6","hao"));
        assertEquals("mi'h",NineKeyPinyin.displayPreedit("644","64 4","mi hao"));
    }
    @Test public void readingMustCoverEveryTypedLetterOrDigit() {
        assertEquals("64 426",NineKeyPinyin.displayPreedit("64426","64 426","ni"));
        assertEquals("64 4262",NineKeyPinyin.displayPreedit("644262","64 4262","ni hao"));
    }
    @Test public void selectedLatinSpellingCannotChangeToADigitEquivalentAlternative() {
        assertEquals("ni'4",NineKeyPinyin.displayPreedit("ni'4","ni'4","mi hao"));
        assertEquals("n4",NineKeyPinyin.displayPreedit("n4","n4","mi hao"));
        assertEquals("ni'h",NineKeyPinyin.displayPreedit("NI'4","NI'4","ni hao"));
    }
    @Test public void explicitSyllableBoundariesMustAgreeWithReading() {
        assertEquals("6'4426",NineKeyPinyin.displayPreedit("6'4426","6'4426","ni hao"));
        assertEquals("ni'426",NineKeyPinyin.displayPreedit("ni'426","ni'426","nih ao"));
        assertEquals("ni'",NineKeyPinyin.displayPreedit("64'","64'","ni hao"));
        assertEquals("ni'",NineKeyPinyin.displayPreedit("ni'","ni'","ni"));
        assertEquals("ni'h",NineKeyPinyin.displayPreedit("ni'4","ni 4","ni'hao"));
    }
    @Test public void malformedSeparatorsDoNotFabricateSyllables() {
        for(String reading:new String[]{" ni hao","ni hao ","ni  hao","ni''hao"})
            assertEquals("64 4",NineKeyPinyin.displayPreedit("644","64 4",reading));
        for(String raw:new String[]{"'644","64''4","64  '4"})
            assertEquals(raw,NineKeyPinyin.displayPreedit(raw,raw,"ni hao"));
    }
    @Test public void annotationsAndToneMarksRemainEnginePreedit() {
        for(String reading:new String[]{"ni hao [common]","ni3 hao3","nǐ hǎo","NI HAO","你好","ni🙂hao","ni\thao"})
            assertEquals("64 426",NineKeyPinyin.displayPreedit("64426","64 426",reading));
    }
    @Test public void unsupportedRawInputCannotBeSilentlyDropped() {
        for(String raw:new String[]{"64🙂426","64+426","164426","064426"})
            assertEquals("raw",NineKeyPinyin.displayPreedit(raw,"raw","ni hao"));
    }
    @Test public void absentCompositionOrReadingKeepsOriginalDisplay() {
        assertEquals("",NineKeyPinyin.displayPreedit("64426","","ni hao"));
        assertEquals("64 426",NineKeyPinyin.displayPreedit("64426","64 426",null));
        assertEquals("64 426",NineKeyPinyin.displayPreedit("64426","64 426",""));
        assertEquals("64 426",NineKeyPinyin.displayPreedit(null,"64 426","ni hao"));
        assertEquals("",NineKeyPinyin.displayPreedit("64426",null,"ni hao"));
    }
    @Test public void separatorsWithoutLettersDoNotCreateAReading() {
        assertEquals("'",NineKeyPinyin.displayPreedit("'","'"," ' "));
        assertEquals("64",NineKeyPinyin.displayPreedit("64","64"," ' "));
    }
    @Test public void displayDoesNotChangeRawBackspaceOrReturnText() {
        String raw="ni'426";
        assertEquals("ni'hao",NineKeyPinyin.displayPreedit(raw,"ni 426","ni hao"));
        assertEquals("ni'426",raw);
        assertEquals("ni'42",NineKeyPinyin.backspace(raw));
    }
}
