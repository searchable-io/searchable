package io.searchable.core.infrastructure.lucene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Detector backed by Lingua, loaded reflectively so the dependency stays
 * optional.
 *
 * <p>Lingua ships every language model in one artifact of around 77 MB, which
 * is more than a search library should force on everyone. Projects that want
 * the higher accuracy add {@code com.github.pemistahl:lingua} themselves; the
 * rest keep {@link CjkRatioLanguageDetector}.
 *
 * <p>The detector is built for Japanese and English only, which keeps the
 * loaded models small and matches the analyzers that exist today.
 */
public final class LinguaLanguageDetector implements LanguageDetector {

    private static final Logger log = LoggerFactory.getLogger(LinguaLanguageDetector.class);
    private static final String BUILDER_CLASS =
        "com.github.pemistahl.lingua.api.LanguageDetectorBuilder";
    private static final String LANGUAGE_CLASS = "com.github.pemistahl.lingua.api.Language";

    private final Object detector;
    private final LanguageDetector fallback = new CjkRatioLanguageDetector();

    /**
     * Whether Lingua is on the classpath.
     *
     * @return {@code true} when the Lingua classes can be loaded
     */
    public static boolean isAvailable() {
        try {
            Class.forName(BUILDER_CLASS, false, LinguaLanguageDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public LinguaLanguageDetector() {
        this.detector = build();
    }

    private static Object build() {
        try {
            final ClassLoader loader = LinguaLanguageDetector.class.getClassLoader();
            final Class<?> languageClass = Class.forName(LANGUAGE_CLASS, true, loader);
            final Object japanese = Enum.valueOf(languageClass.asSubclass(Enum.class), "JAPANESE");
            final Object english = Enum.valueOf(languageClass.asSubclass(Enum.class), "ENGLISH");
            final Object languages = java.lang.reflect.Array.newInstance(languageClass, 2);
            java.lang.reflect.Array.set(languages, 0, japanese);
            java.lang.reflect.Array.set(languages, 1, english);

            final Class<?> builderClass = Class.forName(BUILDER_CLASS, true, loader);
            final Method fromLanguages = builderClass.getMethod("fromLanguages", languages.getClass());
            final Object builder = fromLanguages.invoke(null, languages);
            return builderClass.getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Lingua is on the classpath but could not be initialized; "
                + "falling back to the character-ratio heuristic", e);
            return null;
        }
    }

    @Override
    public Optional<AnalyzerType> detect(final String sample) {
        if (detector == null || sample == null || sample.isBlank()) {
            return fallback.detect(sample);
        }
        try {
            final Object language = detector.getClass()
                .getMethod("detectLanguageOf", String.class)
                .invoke(detector, sample);
            final String name = language == null ? "NONE" : language.toString();
            return switch (name) {
                case "JAPANESE" -> Optional.of(AnalyzerType.KUROMOJI);
                case "ENGLISH" -> Optional.of(AnalyzerType.ENGLISH);
                default -> fallback.detect(sample);
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Lingua detection failed; falling back to the character-ratio heuristic", e);
            return fallback.detect(sample);
        }
    }
}
