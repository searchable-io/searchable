package io.searchable.admin.config;

import io.searchable.core.application.config.SearchableConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Maps {@code searchable.*} properties from {@code application.properties}.
 *
 * <p>Resolves all path-typed fields against {@link #dataDirectory} after
 * Spring binding completes (see
 * {@code docs/devel/adr/0002-data-directory-relative-path-resolution.md}).
 */
@ConfigurationProperties(prefix = "searchable")
public class SearchableProperties {

    private static final Path DEFAULT_INDEX_DIR = Path.of("./data/indexes");
    private static final Path DEFAULT_DICTIONARY_DIR = Path.of("./data/dictionaries");

    private Path dataDirectory = Path.of("./data");
    private Persistence persistence = new Persistence();
    private Index index = new Index();
    private Plugins plugins = new Plugins();
    private Global global = new Global();
    private Embedding embedding = new Embedding();
    private Dictionary dictionary = new Dictionary();
    private Chunking chunking = new Chunking();
    private Ai ai = new Ai();

    public Path getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(final Path v) { this.dataDirectory = v; }
    public Persistence getPersistence() { return persistence; }
    public void setPersistence(final Persistence v) { this.persistence = v; }
    public Index getIndex() { return index; }
    public void setIndex(final Index v) { this.index = v; }
    public Plugins getPlugins() { return plugins; }
    public void setPlugins(final Plugins v) { this.plugins = v; }
    public Global getGlobal() { return global; }
    public void setGlobal(final Global v) { this.global = v; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(final Embedding v) { this.embedding = v; }
    public Dictionary getDictionary() { return dictionary; }
    public void setDictionary(final Dictionary v) { this.dictionary = v; }
    public Chunking getChunking() { return chunking; }
    public void setChunking(final Chunking v) { this.chunking = v; }
    public Ai getAi() { return ai; }
    public void setAi(final Ai v) { this.ai = v; }

    /**
     * Resolve all path-typed fields to absolute paths. {@code dataDirectory}
     * is resolved against the JVM working directory; sub-paths (index,
     * dictionary, plugins, embedding model, H2 URL) are resolved against the
     * absolute {@code dataDirectory}. Default values such as
     * {@code ./data/indexes} are replaced with the data-directory-relative
     * equivalent.
     */
    @PostConstruct
    public void normalizePaths() {
        final Path cwd = Path.of("").toAbsolutePath();
        this.dataDirectory = resolveAgainst(this.dataDirectory, cwd);

        final Path idxDir = index.getDirectory();
        if (DEFAULT_INDEX_DIR.equals(idxDir)) {
            index.setDirectory(dataDirectory.resolve("indexes"));
        } else if (idxDir != null) {
            index.setDirectory(resolveAgainst(idxDir, dataDirectory));
        }

        final Path dictDir = dictionary.getDirectory();
        if (DEFAULT_DICTIONARY_DIR.equals(dictDir)) {
            dictionary.setDirectory(dataDirectory.resolve("dictionaries"));
        } else if (dictDir != null) {
            dictionary.setDirectory(resolveAgainst(dictDir, dataDirectory));
        }

        if (plugins.getDirectory() != null) {
            plugins.setDirectory(resolveAgainst(plugins.getDirectory(), dataDirectory));
        }
        if (embedding.getModelPath() != null) {
            embedding.setModelPath(resolveAgainst(embedding.getModelPath(), dataDirectory));
        }
        persistence.setUrl(SearchableConfig.normalizeH2Url(persistence.getUrl(), dataDirectory));
    }

    private static Path resolveAgainst(final Path p, final Path base) {
        return p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
    }

    /** Persistence DB connection settings. */
    public static class Persistence {
        private String type = "H2";
        private String url = "jdbc:h2:./data/metadata;MODE=PostgreSQL";
        private String username = "sa";
        private String password = "";

        public String getType() { return type; }
        public void setType(final String v) { this.type = v; }
        public String getUrl() { return url; }
        public void setUrl(final String v) { this.url = v; }
        public String getUsername() { return username; }
        public void setUsername(final String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(final String v) { this.password = v; }
    }

    /** Lucene index settings. */
    public static class Index {
        private Path directory = Path.of("./data/indexes");
        private io.searchable.core.infrastructure.lucene.StorageBackend backend
            = io.searchable.core.infrastructure.lucene.StorageBackend.FILESYSTEM;

        public Path getDirectory() { return directory; }
        public void setDirectory(final Path v) { this.directory = v; }
        public io.searchable.core.infrastructure.lucene.StorageBackend getBackend() { return backend; }
        public void setBackend(final io.searchable.core.infrastructure.lucene.StorageBackend v) { this.backend = v; }
        public Retention getRetention() { return retention; }
        public void setRetention(final Retention v) { this.retention = v; }

        private Retention retention = new Retention();
    }

    /**
     * How many old index generations survive a rebuild.
     *
     * <p>A generation is kept when it is among the newest {@code generations}
     * or was created within {@code max-age}; the active generation is always
     * kept. The defaults keep only the active generation, which is what
     * releases before this setting did.
     */
    public static class Retention {
        private int generations = 1;
        private Duration maxAge = Duration.ZERO;

        public int getGenerations() { return generations; }
        public void setGenerations(final int v) { this.generations = v; }
        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(final Duration v) { this.maxAge = v; }
    }

    /** Plugin loader settings. */
    public static class Plugins {
        private Path directory;

        public Path getDirectory() { return directory; }
        public void setDirectory(final Path v) { this.directory = v; }
    }

    /** Vector embedding settings. */
    public static class Embedding {
        /** Embedding provider: {@code hash} (default, deterministic) or {@code onnx}. */
        private String provider = "hash";
        /** Output vector dimension. Must be a multiple of 8 for the hash provider. */
        private int dimension = 384;
        /** Path to an ONNX model file (required when {@code provider=onnx}). */
        private Path modelPath;
        /** Identifier for diagnostics (e.g. {@code multilingual-e5-small}). */
        private String modelId = "hash:384";
        /** Maximum sequence length for the ONNX tokenizer. */
        private int maxSequenceLength = 512;

        public String getProvider() { return provider; }
        public void setProvider(final String v) { this.provider = v; }
        public int getDimension() { return dimension; }
        public void setDimension(final int v) { this.dimension = v; }
        public Path getModelPath() { return modelPath; }
        public void setModelPath(final Path v) { this.modelPath = v; }
        public String getModelId() { return modelId; }
        public void setModelId(final String v) { this.modelId = v; }
        public int getMaxSequenceLength() { return maxSequenceLength; }
        public void setMaxSequenceLength(final int v) { this.maxSequenceLength = v; }
    }

    /** Chunking strategy for embedding/indexing. */
    public static class Chunking {
        /** Strategy name: {@code whole} (default), {@code fixed}, {@code sentence}, {@code paragraph}, {@code section}. */
        private String strategy = "whole";
        /** Chunk size (chars). Used by {@code fixed} strategy. */
        private int chunkSize = 512;
        /** Overlap between consecutive chunks. Used by {@code fixed} strategy. */
        private int overlap = 64;
        /** Target chunk size (chars) used by {@code sentence} strategy. */
        private int sentenceTargetSize = 400;

        public String getStrategy() { return strategy; }
        public void setStrategy(final String v) { this.strategy = v; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(final int v) { this.chunkSize = v; }
        public int getOverlap() { return overlap; }
        public void setOverlap(final int v) { this.overlap = v; }
        public int getSentenceTargetSize() { return sentenceTargetSize; }
        public void setSentenceTargetSize(final int v) { this.sentenceTargetSize = v; }
    }

    /** User dictionary (custom tokenization) settings. */
    public static class Dictionary {
        /** Storage backend: {@code file} (default) or {@code db}. */
        private String storage = "file";
        /** Root directory for file-backed storage. */
        private Path directory = Path.of("./data/dictionaries");

        public String getStorage() { return storage; }
        public void setStorage(final String v) { this.storage = v; }
        public Path getDirectory() { return directory; }
        public void setDirectory(final Path v) { this.directory = v; }
    }

    /**
     * AI summarisation settings (provider selection, model, runtime limits).
     *
     * <p>API keys are <strong>not</strong> bound here; each provider reads them
     * from its own environment variable (see provider Javadoc). This keeps
     * secrets out of {@code application.properties} unless the operator opts in.
     */
    public static class Ai {
        /** Master switch. When false the SummaryService returns a disabled sentinel. */
        private boolean enabled;
        /** Provider id: {@code openai}, {@code anthropic}, {@code ollama}, or empty. */
        private String provider = "";
        /** Model identifier sent to the provider. Empty → provider default. */
        private String model = "";
        /** Wall-clock timeout for a single summarisation call. */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(15);
        /** Upper bound on tokens requested from the provider. */
        private int maxTokens = 512;
        /** Sampling temperature in [0.0, 2.0]. */
        private double temperature = 0.2;
        /** Cap on number of search hits forwarded as context. */
        private int maxContextItems = 5;
        /** Cap on total character length of context body. */
        private int maxContextChars = 8000;
        /** When true, TIMEOUT / UPSTREAM failures degrade silently. */
        private boolean fallbackOnError = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(final boolean v) { this.enabled = v; }
        public String getProvider() { return provider; }
        public void setProvider(final String v) { this.provider = v == null ? "" : v; }
        public String getModel() { return model; }
        public void setModel(final String v) { this.model = v == null ? "" : v; }
        public java.time.Duration getTimeout() { return timeout; }
        public void setTimeout(final java.time.Duration v) { this.timeout = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(final int v) { this.maxTokens = v; }
        public double getTemperature() { return temperature; }
        public void setTemperature(final double v) { this.temperature = v; }
        public int getMaxContextItems() { return maxContextItems; }
        public void setMaxContextItems(final int v) { this.maxContextItems = v; }
        public int getMaxContextChars() { return maxContextChars; }
        public void setMaxContextChars(final int v) { this.maxContextChars = v; }
        public boolean isFallbackOnError() { return fallbackOnError; }
        public void setFallbackOnError(final boolean v) { this.fallbackOnError = v; }
    }

    /** Default search behavior for namespaces. */
    public static class Global {
        private String defaultArchitecture = "FULL_TEXT";
        private String defaultSearchStrategy = "SEQUENTIAL";
        private String defaultSearchOrder = "FULL_TEXT_FIRST";

        public String getDefaultArchitecture() { return defaultArchitecture; }
        public void setDefaultArchitecture(final String v) { this.defaultArchitecture = v; }
        public String getDefaultSearchStrategy() { return defaultSearchStrategy; }
        public void setDefaultSearchStrategy(final String v) { this.defaultSearchStrategy = v; }
        public String getDefaultSearchOrder() { return defaultSearchOrder; }
        public void setDefaultSearchOrder(final String v) { this.defaultSearchOrder = v; }
    }
}
