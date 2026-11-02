package io.searchable.core.infrastructure.lucene;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {

    private final CjkRatioLanguageDetector detector = new CjkRatioLanguageDetector();

    @Test
    void japaneseProseChoosesKuromoji() {
        assertThat(detector.detect("四半期の売上に関する報告書です。"))
            .contains(AnalyzerType.KUROMOJI);
    }

    @Test
    void englishProseChoosesEnglish() {
        assertThat(detector.detect("This document describes the quarterly sales report."))
            .contains(AnalyzerType.ENGLISH);
    }

    @Test
    void japaneseTextFullOfEnglishTermsStillChoosesKuromoji() {
        assertThat(detector.detect(
            "create_agent と delete_agent の API を呼び出す handler の実装について"))
            .contains(AnalyzerType.KUROMOJI);
    }

    @Test
    void emptyOrSymbolOnlySampleDecidesNothing() {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("   ")).isEmpty();
        assertThat(detector.detect("1234 56.78 --- ***")).isEmpty();
    }

    @Test
    void digitsAndPunctuationDoNotMoveTheRatio() {
        final Optional<AnalyzerType> withNoise =
            detector.detect("Sales: 1,234,567 (2026-11-02) --- report!!!");

        assertThat(withNoise).contains(AnalyzerType.ENGLISH);
    }

    @Test
    void thresholdIsTheOnlyTuningKnob() {
        // 1 CJK letter among 20 letters is exactly the 5% threshold, so it counts as Japanese.
        final String atThreshold = "abcdefghijklmnopqrs漢";

        assertThat(atThreshold.chars().filter(Character::isLetter).count()).isEqualTo(20);
        assertThat(detector.detect(atThreshold)).contains(AnalyzerType.KUROMOJI);
        assertThat(detector.detect("abcdefghijklmnopqrst")).contains(AnalyzerType.ENGLISH);
        assertThat(CjkRatioLanguageDetector.CJK_THRESHOLD).isEqualTo(0.05);
    }

    @Test
    void defaultDetectorFallsBackWhenLinguaIsAbsent() {
        assertThat(LinguaLanguageDetector.isAvailable()).isFalse();
        assertThat(LanguageDetector.defaultDetector())
            .isInstanceOf(CjkRatioLanguageDetector.class);
    }
}
