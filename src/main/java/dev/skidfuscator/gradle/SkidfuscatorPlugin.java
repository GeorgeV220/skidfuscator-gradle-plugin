package dev.skidfuscator.gradle;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.process.ExecOperations;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class SkidfuscatorPlugin implements Plugin<Project> {

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Override
    public void apply(@NotNull Project project) {
        addExclude(project);

        NamedDomainObjectContainer<TransformerSpec> transformerContainer =
                project.getObjects().domainObjectContainer(
                        TransformerSpec.class,
                        TransformerSpec::new
                );

        SkidfuscatorExtension extension = project.getExtensions().create(
                "skidfuscator",
                SkidfuscatorExtension.class,
                transformerContainer
        );

        TaskProvider<Task> collectDependencies = project.getTasks().register(
                "collectSkidfuscatorDependencies",
                task -> {
                    task.setGroup("skidfuscator");
                    task.setDescription(
                            "Collects dependencies required by Skidfuscator."
                    );

                    task.doLast(t -> collectDependencies(project));
                }
        );

        project.getTasks().register(
                "skidfuscate",
                task -> {
                    task.setGroup("skidfuscator");
                    task.setDescription(
                            "Obfuscates the configured input JAR using Skidfuscator."
                    );

                    task.dependsOn(collectDependencies);

                    task.doLast(t ->
                            runSkidfuscator(project, extension)
                    );
                }
        );
    }

    private void collectDependencies(@NonNull Project project) {
        File depsDir = new File(
                project.getLayout()
                        .getBuildDirectory()
                        .get()
                        .getAsFile(),
                "skidfuscator/dependencies"
        );

        if (!depsDir.exists() && !depsDir.mkdirs()) {
            throw new GradleException(
                    "Failed to create Skidfuscator dependencies directory: "
                            + depsDir.getAbsolutePath()
            );
        }

        File[] existingFiles = depsDir.listFiles();

        if (existingFiles != null) {
            for (File file : existingFiles) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    throw new GradleException(
                            "Failed to delete old Skidfuscator dependency: "
                                    + file.getAbsolutePath(),
                            e
                    );
                }
            }
        }

        Set<File> dependencies = project
                .getConfigurations()
                .getByName("compileClasspath")
                .getResolvedConfiguration()
                .getResolvedArtifacts()
                .stream()
                .map(ResolvedArtifact::getFile)
                .collect(Collectors.toSet());

        project.getLogger().lifecycle(
                "Collecting {} dependencies for Skidfuscator...",
                dependencies.size()
        );

        for (File dependency : dependencies) {
            File destination = new File(
                    depsDir,
                    dependency.getName()
            );

            project.getLogger().info(
                    "Skidfuscator dependency: {}",
                    dependency.getAbsolutePath()
            );

            try {
                Files.copy(
                        dependency.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException e) {
                throw new GradleException(
                        "Failed to copy dependency: "
                                + dependency.getAbsolutePath(),
                        e
                );
            }
        }

        project.getLogger().lifecycle(
                "Collected {} dependencies for Skidfuscator.",
                dependencies.size()
        );
    }

    private void runSkidfuscator(
            Project project,
            @NonNull SkidfuscatorExtension extension
    ) {
        String configuredInput = extension.getInput();

        if (configuredInput == null
                || configuredInput.trim().isEmpty()) {
            throw new GradleException(
                    "Skidfuscator input JAR is not configured. "
                            + "Set skidfuscator.input in your build script."
            );
        }

        File inputJar = project.file(configuredInput);

        if (!inputJar.exists()) {
            throw new GradleException(
                    "Skidfuscator input JAR does not exist: "
                            + inputJar.getAbsolutePath()
            );
        }

        if (!inputJar.isFile()) {
            throw new GradleException(
                    "Skidfuscator input is not a file: "
                            + inputJar.getAbsolutePath()
            );
        }

        File skidDir = new File(
                project.getLayout()
                        .getBuildDirectory()
                        .get()
                        .getAsFile(),
                "skidfuscator"
        );

        if (!skidDir.exists() && !skidDir.mkdirs()) {
            throw new GradleException(
                    "Failed to create Skidfuscator directory: "
                            + skidDir.getAbsolutePath()
            );
        }

        String resolvedVersion;

        try {
            resolvedVersion = resolveVersion(
                    extension.getSkidfuscatorVersion()
            );
        } catch (IOException e) {
            throw new GradleException(
                    "Failed to resolve Skidfuscator version",
                    e
            );
        }

        File versionFile = new File(
                skidDir,
                ".version"
        );

        String currentVersion =
                readVersionFile(versionFile);

        File skidJar = new File(
                project.getProjectDir(),
                ".skidfuscator/skidfuscator-"
                        + resolvedVersion
                        + ".jar"
        );

        File skidJarDirectory =
                skidJar.getParentFile();

        if (!skidJarDirectory.exists()
                && !skidJarDirectory.mkdirs()) {
            throw new GradleException(
                    "Failed to create Skidfuscator directory: "
                            + skidJarDirectory.getAbsolutePath()
            );
        }

        boolean shouldDownload =
                !skidJar.exists();

        if (!"dev".equalsIgnoreCase(resolvedVersion)
                && !resolvedVersion.equals(currentVersion)) {
            shouldDownload = true;
        }

        if (shouldDownload) {
            project.getLogger().lifecycle(
                    "Downloading Skidfuscator {}...",
                    resolvedVersion
            );

            try {
                downloadSkidfuscatorJar(
                        resolvedVersion,
                        skidJar
                );

                writeVersionFile(
                        versionFile,
                        resolvedVersion
                );
            } catch (IOException e) {
                throw new GradleException(
                        "Failed to download Skidfuscator "
                                + resolvedVersion,
                        e
                );
            }
        }

        File depsDir = new File(
                skidDir,
                "dependencies"
        );

        if (depsDir.exists()) {
            File[] dependencyJars = depsDir.listFiles(
                    file ->
                            file.isFile()
                                    && file.getName()
                                    .endsWith(".jar")
            );

            if (dependencyJars != null) {
                Arrays.sort(
                        dependencyJars,
                        (a, b) ->
                                a.getName()
                                        .compareToIgnoreCase(
                                                b.getName()
                                        )
                );

                project.getLogger().lifecycle(
                        "Adding {} libraries to Skidfuscator...",
                        dependencyJars.length
                );

                for (File dependencyJar :
                        dependencyJars) {

                    String path =
                            dependencyJar.getAbsolutePath();

                    project.getLogger().info(
                            "Skidfuscator library: {}",
                            dependencyJar.getName()
                    );

                    if (!extension.getLibs()
                            .contains(path)) {
                        extension.getLibs()
                                .add(path);
                    }
                }
            }
        }

        File configFile = new File(
                skidDir,
                extension.getConfigFileName()
        );

        try {
            writeHoconConfig(
                    extension,
                    configFile
            );
        } catch (IOException e) {
            throw new GradleException(
                    "Failed to generate Skidfuscator config: "
                            + configFile.getAbsolutePath(),
                    e
            );
        }

        File resultJar;

        if (extension.getOutput() != null
                && !extension.getOutput()
                .trim()
                .isEmpty()) {

            resultJar =
                    project.file(
                            extension.getOutput()
                    );
        } else {
            String name =
                    inputJar.getName();

            if (name.endsWith(".jar")) {
                name = name.substring(
                        0,
                        name.length() - 4
                ) + "-obfuscated.jar";
            } else {
                name += "-obfuscated.jar";
            }

            resultJar = new File(
                    inputJar.getParentFile(),
                    name
            );
        }

        File resultDirectory =
                resultJar.getParentFile();

        if (resultDirectory != null
                && !resultDirectory.exists()
                && !resultDirectory.mkdirs()) {
            throw new GradleException(
                    "Failed to create output directory: "
                            + resultDirectory.getAbsolutePath()
            );
        }

        List<String> args =
                new ArrayList<>();

        args.add("obfuscate");

        args.add("-cfg");
        args.add(
                configFile.getAbsolutePath()
        );

        args.add("-o");
        args.add(
                resultJar.getAbsolutePath()
        );

        if (extension.isPhantom()) {
            args.add("-ph");
        }

        if (extension.isFuckit()) {
            args.add("-fuckit");
        }

        if (extension.isDebug()) {
            args.add("--debug");
        }

        if (extension.isNotrack()) {
            args.add("-notrack");
        }

        if (extension.getRuntime() != null
                && !extension.getRuntime()
                .trim()
                .isEmpty()) {

            File runtime =
                    project.file(
                            extension.getRuntime()
                    );

            if (runtime.exists()) {
                args.add("-rt");
                args.add(
                        runtime.getAbsolutePath()
                );
            } else {
                project.getLogger().warn(
                        "Configured Skidfuscator runtime does not exist: {}",
                        runtime.getAbsolutePath()
                );
            }
        }

        args.add(
                inputJar.getAbsolutePath()
        );

        List<String> fullArgs =
                new ArrayList<>();

        fullArgs.add("-jar");
        fullArgs.add(
                skidJar.getAbsolutePath()
        );

        fullArgs.addAll(args);

        project.getLogger().lifecycle(
                "Running Skidfuscator {}",
                resolvedVersion
        );

        project.getLogger().lifecycle(
                "Input : {}",
                inputJar.getAbsolutePath()
        );

        project.getLogger().lifecycle(
                "Output: {}",
                resultJar.getAbsolutePath()
        );

        File javaExecutable = new File(
                System.getProperty("java.home"),
                "bin/java"
        );

        getExecOperations().exec(spec -> {
            spec.setExecutable(
                    javaExecutable.getAbsolutePath()
            );

            spec.setArgs(fullArgs);

            spec.setWorkingDir(
                    skidDir
            );

            spec.setIgnoreExitValue(false);
        });

        if (!resultJar.exists()) {
            throw new GradleException(
                    "Skidfuscator completed without producing "
                            + "the expected output JAR: "
                            + resultJar.getAbsolutePath()
            );
        }

        project.getLogger().lifecycle(
                "Skidfuscation complete: {}",
                resultJar.getAbsolutePath()
        );
    }

    private String resolveVersion(
            String requestedVersion
    ) throws IOException {
        if (!"latest".equalsIgnoreCase(
                requestedVersion
        )) {
            return requestedVersion;
        }

        URL url = new URL(
                "https://api.github.com/repos/"
                        + "skidfuscatordev/"
                        + "skidfuscator-java-obfuscator/"
                        + "releases/latest"
        );

        HttpURLConnection connection =
                (HttpURLConnection)
                        url.openConnection();

        connection.setRequestProperty(
                "Accept",
                "application/vnd.github.v3+json"
        );

        connection.connect();

        if (connection.getResponseCode()
                != HttpURLConnection.HTTP_OK) {
            throw new IOException(
                    "Failed to fetch latest Skidfuscator release. HTTP "
                            + connection.getResponseCode()
            );
        }

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     connection.getInputStream()
                             )
                     )) {

            String json = reader
                    .lines()
                    .collect(
                            Collectors.joining()
                    );

            int tagIndex =
                    json.indexOf("\"tag_name\"");

            if (tagIndex == -1) {
                throw new IOException(
                        "Could not find tag_name in GitHub response"
                );
            }

            int start =
                    json.indexOf(
                            ":",
                            tagIndex
                    ) + 1;

            int end =
                    json.indexOf(
                            ",",
                            start
                    );

            if (end == -1) {
                end = json.indexOf(
                        "}",
                        start
                );
            }

            String tag = json
                    .substring(start, end)
                    .replace("\"", "")
                    .trim();

            return tag.startsWith("v")
                    ? tag.substring(1)
                    : tag;
        } finally {
            connection.disconnect();
        }
    }

    private void downloadSkidfuscatorJar(
            String version,
            File target
    ) throws IOException {
        String urlString =
                "https://github.com/"
                        + "skidfuscatordev/"
                        + "skidfuscator-java-obfuscator/"
                        + "releases/download/"
                        + version
                        + "/skidfuscator.jar";

        URL url =
                new URL(urlString);

        try (
                InputStream input =
                        url.openStream();

                OutputStream output =
                        new FileOutputStream(target)
        ) {
            byte[] buffer =
                    new byte[8192];

            int read;

            while ((read =
                    input.read(buffer))
                    != -1) {

                output.write(
                        buffer,
                        0,
                        read
                );
            }
        }
    }

    private void writeHoconConfig(
            SkidfuscatorExtension extension,
            File configFile
    ) throws IOException {
        Config config =
                buildConfig(extension);

        String rendered =
                config.root().render(
                        ConfigRenderOptions
                                .defaults()
                                .setComments(false)
                                .setJson(false)
                                .setOriginComments(false)
                );

        try (FileWriter writer =
                     new FileWriter(configFile)) {
            writer.write(rendered);
        }
    }

    private Config buildConfig(
            SkidfuscatorExtension extension
    ) {
        Map<String, Object> root =
                new HashMap<>();

        root.put(
                "exempt",
                extension.getExempt()
        );

        root.put(
                "libraries",
                extension.getLibs()
        );

        extension.getTransformers()
                .getTransformers()
                .forEach(transformer ->
                        root.put(
                                transformer.getName(),
                                transformer.getProperties()
                        )
                );

        return ConfigFactory.parseMap(root);
    }

    private String readVersionFile(
            File versionFile
    ) {
        if (!versionFile.exists()) {
            return "";
        }

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(versionFile)
                     )) {

            String line =
                    reader.readLine();

            return line != null
                    ? line.trim()
                    : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private void writeVersionFile(
            File versionFile,
            String version
    ) throws IOException {
        try (FileWriter writer =
                     new FileWriter(versionFile)) {
            writer.write(version);
        }
    }

    private void addExclude(
            Project project
    ) {
        File gitignore =
                new File(
                        project.getRootDir(),
                        ".gitignore"
                );

        try {
            boolean needsEntry = true;

            if (gitignore.exists()) {
                try (BufferedReader reader =
                             new BufferedReader(
                                     new FileReader(gitignore)
                             )) {

                    needsEntry =
                            reader.lines()
                                    .noneMatch(
                                            line ->
                                                    line.trim()
                                                            .equals(
                                                                    ".skidfuscator"
                                                            )
                                    );
                }
            }

            if (!needsEntry) {
                return;
            }

            boolean needsNewline =
                    gitignore.exists()
                            && gitignore.length() > 0;

            if (needsNewline) {
                byte[] content =
                        Files.readAllBytes(
                                gitignore.toPath()
                        );

                needsNewline =
                        content.length > 0
                                && content[
                                content.length - 1
                                ] != '\n';
            }

            try (FileWriter writer =
                         new FileWriter(
                                 gitignore,
                                 true
                         )) {

                if (needsNewline) {
                    writer.write("\n");
                }

                writer.write(
                        ".skidfuscator\n"
                );
            }

            project.getLogger().lifecycle(
                    "Added .skidfuscator to .gitignore"
            );
        } catch (IOException e) {
            project.getLogger().warn(
                    "Failed to update .gitignore: {}",
                    e.getMessage()
            );
        }
    }
}
