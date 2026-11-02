package io.searchable.core.infrastructure.lucene;

import java.util.Optional;

/**
 * Zero-dependency detector that compares how much of the sample is CJK.
 *
 * <p>Japanese prose cannot avoid kana, so even a text full of English product
 * names carries a steady share of CJK characters, while English prose carries
 * essentially none. A low threshold is therefore enough to separate the two,
 * and it is the only tuning knob here.
 *
 * <p>Letters are counted, everything else (digits, punctuation, whitespace) is
 * ignored, so formatting does not move the ratio.
 */
public final class CjkRatioLanguageDetector implements LanguageDetector {

    /** Share of CJK letters above which the text is treated as Japanese. */
    public static final double CJK_THRESHOLD = 0.05;

    @Override
    public Optional<AnalyzerType> detect(final String sample) {
        if (sample == null || sample.isBlank()) {
            return Optional.empty();
        }
        long letters = 0;
        long cjk = 0;
        for (int i = 0; i < sample.length(); ) {
            final int codePoint = sample.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            letters++;
            if (isCjk(codePoint)) {
                cjk++;
            }
        }
        if (letters == 0) {
            return Optional.empty();
        }
        final double ratio = (double) cjk / letters;
        return Optional.of(ratio >= CJK_THRESHOLD ? AnalyzerType.KUROMOJI : AnalyzerType.ENGLISH);
    }

    private static boolean isCjk(final int codePoint) {
        final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA;
    }
}
