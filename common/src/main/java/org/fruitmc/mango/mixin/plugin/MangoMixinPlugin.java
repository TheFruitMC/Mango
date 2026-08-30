package org.fruitmc.mango.mixin.plugin;

import org.fruitmc.mango.Constants;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MangoMixinPlugin implements IMixinConfigPlugin {

    private static final String BACKEND_PROPERTY_KEY = "mango.backend";
    private static final String JAVA_COMMAND_PROPERTY = "sun.java.command";
    private static final String USER_DIR_PROPERTY = "user.dir";
    private static final String OPTIONS_FILE_NAME = "options.txt";
    private static final String PREFERRED_GRAPHICS_BACKEND_PREFIX = "preferredGraphicsBackend:";
    private static final String VULKAN_VALUE = "vulkan";
    private static final String VULKAN_MIXIN_PACKAGE = "org.fruitmc.mango.mixin.vulkan.";
    private static final String GRAPHICS_BACKEND_FLAG = "--graphicsBackend";
    private static final String GAME_DIR_FLAG = "--gameDir";
    private static final Pattern COMMAND_LINE_TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private static boolean vulkanRequested = false;

    public static boolean isVulkanRequested() {
        return vulkanRequested;
    }

    @Override
    public void onLoad(String mixinPackage) {
        String backend = detectBackend();
        if (VULKAN_VALUE.equalsIgnoreCase(backend)) {
            vulkanRequested = true;
        }
        logDetection(backend != null ? backend : "default");
    }

    private static String detectBackend() {
        String backend = System.getProperty(BACKEND_PROPERTY_KEY);
        if (backend != null) {
            return backend.trim();
        }

        List<String> commandLine = parseCommandLine();
        backend = findCommandLineOption(commandLine, GRAPHICS_BACKEND_FLAG);
        if (backend != null) {
            return backend;
        }

        Path gameDir = findCommandLinePathOption(commandLine, GAME_DIR_FLAG)
                .orElse(Path.of(System.getProperty(USER_DIR_PROPERTY, ".")));

        return readPreferredBackendFromOptions(gameDir);
    }

    private static List<String> parseCommandLine() {
        String command = System.getProperty(JAVA_COMMAND_PROPERTY);
        if (command == null || command.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        Matcher matcher = COMMAND_LINE_TOKEN_PATTERN.matcher(command);
        while (matcher.find()) {
            tokens.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }

        if (tokens.size() <= 1) {
            return List.of();
        }

        return tokens.subList(1, tokens.size());
    }

    private static String findCommandLineOption(List<String> args, String name) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals(name) && i + 1 < args.size()) {
                return args.get(i + 1);
            }
            if (arg.startsWith(name + "=")) {
                return arg.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static Optional<Path> findCommandLinePathOption(List<String> args, String name) {
        String value = findCommandLineOption(args, name);
        return value != null ? Optional.of(Path.of(value)) : Optional.empty();
    }

    private static String readPreferredBackendFromOptions(Path gameDir) {
        Path options = gameDir.resolve(OPTIONS_FILE_NAME);
        if (!Files.isRegularFile(options)) {
            return null;
        }

        try (var lines = Files.lines(options)) {
            return lines
                    .map(String::trim)
                    .filter(line -> line.startsWith(PREFERRED_GRAPHICS_BACKEND_PREFIX))
                    .findFirst()
                    .map(line -> stripQuotes(line.substring(PREFERRED_GRAPHICS_BACKEND_PREFIX.length()).trim()))
                    .orElse(null);
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read {} to detect graphics backend", options, e);
            return null;
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void logDetection(String detected) {
        // Mixins are selected before the actual backend exists. A default request can still fall back to
        // Vulkan after OpenGL fails, but that late fallback cannot load the Vulkan-only mixins.
        Constants.LOG.info(
                "Mango detected requested graphics backend: {} (Vulkan mixins enabled: {})",
                detected,
                vulkanRequested
        );
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(VULKAN_MIXIN_PACKAGE)) {
            return vulkanRequested;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
