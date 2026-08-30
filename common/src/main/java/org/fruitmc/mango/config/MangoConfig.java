package org.fruitmc.mango.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.fruitmc.mango.Constants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MangoConfig {

    public static final MangoConfig INSTANCE = new MangoConfig();

    private static final String CONFIG_FILE_NAME = "mango.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MIN_CHUNK_BUFFER_SCALE = 1;
    private static final int MAX_CHUNK_BUFFER_SCALE = 4;

    // Video settings can be edited while the render thread reads these values.
    public volatile int chunkBufferScale = 1;
    public volatile boolean enableFrameGeneration = false;
    public volatile boolean enableHiZCulling = true;
    public volatile int frameGenerationMultiplier = DEFAULT_FRAME_GENERATION_MULTIPLIER;
    public static final int MIN_FRAME_GENERATION_MULTIPLIER = 2;
    public static final int MAX_FRAME_GENERATION_MULTIPLIER = 4;
    public static final int DEFAULT_FRAME_GENERATION_MULTIPLIER = 2;

    public static int clampFrameGenerationMultiplier(int value) {
        return Math.max(MIN_FRAME_GENERATION_MULTIPLIER, Math.min(MAX_FRAME_GENERATION_MULTIPLIER, value));
    }

    public static int clampChunkBufferScale(int value) {
        return Math.max(MIN_CHUNK_BUFFER_SCALE, Math.min(MAX_CHUNK_BUFFER_SCALE, value));
    }

    public static final class Builder {
        private int chunkBufferScale = 1;
        private boolean enableFrameGeneration = false;
        private boolean enableHiZCulling = true;
        private int frameGenerationMultiplier = DEFAULT_FRAME_GENERATION_MULTIPLIER;

        private Builder() {
        }

        public static Builder from(MangoConfig source) {
            Builder builder = new Builder();
            builder.chunkBufferScale = source.chunkBufferScale;
            builder.enableFrameGeneration = source.enableFrameGeneration;
            builder.enableHiZCulling = source.enableHiZCulling;
            builder.frameGenerationMultiplier = source.frameGenerationMultiplier;
            return builder;
        }

        public static Builder defaults() {
            return new Builder();
        }

        public Builder chunkBufferScale(int value) {
            this.chunkBufferScale = value;
            return this;
        }

        public Builder enableFrameGeneration(boolean value) {
            this.enableFrameGeneration = value;
            return this;
        }

        public Builder enableHiZCulling(boolean value) {
            this.enableHiZCulling = value;
            return this;
        }

        public Builder frameGenerationMultiplier(int value) {
            this.frameGenerationMultiplier = clampFrameGenerationMultiplier(value);
            return this;
        }

        public void applyTo(MangoConfig target) {
            target.chunkBufferScale = clampChunkBufferScale(this.chunkBufferScale);
            target.enableFrameGeneration = this.enableFrameGeneration;
            target.enableHiZCulling = this.enableHiZCulling;
            target.frameGenerationMultiplier = clampFrameGenerationMultiplier(this.frameGenerationMultiplier);
        }
    }

    public void load(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            save(configDir);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            MangoConfig loaded = GSON.fromJson(reader, MangoConfig.class);
            if (loaded == null) {
                Constants.LOG.warn("Loaded Mango config was null; using defaults");
                return;
            }
            Builder.from(loaded).applyTo(this);
        } catch (IOException | JsonParseException e) {
            Constants.LOG.error("Failed to load Mango config from {}", file, e);
        }
    }

    public void save(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE_NAME);
        try {
            Files.createDirectories(configDir);
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to save Mango config to {}", file, e);
        }
    }

    public void reload(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE_NAME);
        Builder builder;
        if (Files.isRegularFile(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                MangoConfig loaded = GSON.fromJson(reader, MangoConfig.class);
                builder = loaded != null ? Builder.from(loaded) : Builder.defaults();
            } catch (IOException | JsonParseException e) {
                Constants.LOG.error("Failed to reload Mango config from {}", file, e);
                return;
            }
        } else {
            builder = Builder.defaults();
            builder.applyTo(this);
            save(configDir);
            return;
        }
        builder.applyTo(this);
    }
}
