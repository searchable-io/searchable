package io.searchable.example.api.config;

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
    private Api api = new Api();
    private Cors cors = new Cors();

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
    public Api getApi() { return api; }
    public void setApi(final Api v) { this.api = v; }
    public Cors getCors() { return cors; }
    public void setCors(final Cors v) { this.cors = v; }

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
        private Retention retention = new Retention();

        public Path getDirectory() { return directory; }
        public void setDirectory(final Path v) { this.directory = v; }
        public Retention getRetention() { return retention; }
        public void setRetention(final Retention v) { this.retention = v; }
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
     * API security: when {@code key} is set (also overridable via the
     * {@code SEARCHABLE_API_KEY} environment variable), every request
     * must carry a matching {@code X-API-Key} header (TASK-126).
     */
    public static class Api {
        private String key;

        public String getKey() {
            // Environment variable overrides config file.
            final String envKey = System.getenv("SEARCHABLE_API_KEY");
            return envKey != null && !envKey.isBlank() ? envKey : key;
        }
        public void setKey(final String v) { this.key = v; }
        public boolean isEnabled() {
            final String k = getKey();
            return k != null && !k.isBlank();
        }
    }

    /** CORS configuration for the REST API (TASK-127). */
    public static class Cors {
        private java.util.List<String> allowedOrigins = java.util.List.of();
        private java.util.List<String> allowedMethods =
            java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private java.util.List<String> allowedHeaders = java.util.List.of("*");
        private boolean allowCredentials = false;

        public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(final java.util.List<String> v) { this.allowedOrigins = v; }
        public java.util.List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(final java.util.List<String> v) { this.allowedMethods = v; }
        public java.util.List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(final java.util.List<String> v) { this.allowedHeaders = v; }
        public boolean isAllowCredentials() { return allowCredentials; }
        public void setAllowCredentials(final boolean v) { this.allowCredentials = v; }
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
