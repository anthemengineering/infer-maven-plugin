/*
 * Copyright 2015 Anthem Engineering LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.anthemengineering.mojo.infer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs Infer over Java source files in the main source (test sources are ignored). Basic results information of the
 * Infer check is printed to the console and the output of infer is printed to {@code target/infer-out} in the
 * project Maven is run from.
 * <p>
 * For each source file, an execution of {@code infer -i -o [execution_dir/target/] -- javac [source_file.java]} is run.
 * <p>
 * If the directory Maven is run from is the parent of a multi module project, Infer results will continue to
 * accumulate in {@code target/infer-out/} as each module is built.
 * <p>
 * Java 8 is not yet supported by Infer.
 * <p>
 * The {@code PATH} is searched for the Infer script/command; if it is not found, Infer will be downloaded.
 */
@Mojo(name = "infer", defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class InferMojo extends AbstractMojo {
    // constants for downloading
    private static final int CONNECTION_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;
    private static final String LINUX_INFER_DOWNLOAD_URL =
            "https://github.com/facebook/infer/releases/download/v0.1.0/infer-linux64-v0.1.0.tar.xz";

    private static final String OSX_INFER_DOWNLOAD_URL =
            "https://github.com/facebook/infer/releases/download/v0.1.0/infer-osx-v0.1.0.tar.xz";

    // repeated error message
    private static final String EARLY_EXECUTION_TERMINATION_EXCEPTION_MSG =
            "Problem while waiting for Infer to finish; Infer output may be innacurate.";

    // system environment variable property names
    private static final String PATH_SEPARATOR = "path.separator";
    private static final String LINE_SEPARATOR = "line.separator";
    private static final String USER_DIR = "user.dir";
    private static final String OS_NAME = "os.name";

    // file names
    private static final String INFER_BUG_REPORT_FILE_NAME = "bugs.txt";
    private static final String INFER_DOWNLOAD_DIRETORY_NAME = "infer-download";
    private static final String DEFAULT_MAVEN_BUILD_DIR_NAME = "target";
    private static final String INFER_OUTPUT_DIRECTORY_NAME = "infer-out";
    private static final String JAVAC_OUTPUT_DIRECTORY_NAME = "javacOut";
    private static final String INFER_CMD_NAME = "infer";

    // misc strings
    private static final String UTF_8 = "UTF-8";
    private static final String JAVA_SRC_EXTENSION = ".java";

    /**
     * Total number of files analyzed during this build.
     */
    private static int fileCount;

    /**
     * Path to Infer script.
     */
    private static String inferPath;

    /**
     * Currently unused, this map keeps track of Infer executions that failed; since there is only one java source file
     * specifically targeted per process running Infer, this keeps a map of that source file name to the exit code of
     * the Infer process analyzing it.
     */
    private static final Map<File, Integer> FAILED_CHECKS = new HashMap<File, Integer>();

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * True if Infer should be downloaded rather than using an installed version. Infer will be installed once per build
     * into the build directory of the project Maven was run from.
     */
    @Parameter(property = "infer.download", defaultValue = "true")
    private boolean download;

    /**
     * Path to the infer executable/script; or by default {@code infer}, which works when the infer directory has
     * been added to the {@code PATH} environment variable.
     */
    @Parameter(property = "infer.commandPath", defaultValue = "infer")
    private String inferCommand;

    /**
     * Display the output of each execution of Infer.
     */
    @Parameter(property = "infer.consoleOut")
    private boolean consoleOut;

    /**
     * Output directory for Infer.
     */
    @Parameter(property = "infer.outputDir")
    private String inferDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (inferDir == null) {
            inferDir = new File(System.getProperty(USER_DIR), DEFAULT_MAVEN_BUILD_DIR_NAME).getAbsolutePath();
        }

        if (inferPath != null && !INFER_CMD_NAME.equals(inferPath)) {
            getLog().info(String.format("Infer path set to: %s", inferPath));
        }
        // check if infer is on the PATH and if not, then download it.
        if (inferPath == null && download) {
            inferPath = downloadInfer(new File(inferDir, INFER_DOWNLOAD_DIRETORY_NAME));
        } else if (inferPath == null) {
            inferPath = inferCommand;
        }

        try {
            // get source directory, if it doesn't exist then we're done
            final File sourceDir = new File(project.getBuild().getSourceDirectory());

            if (!sourceDir.exists()) {
                return;
            }

            final File inferOutputDir = getInferOutputDir();

            final SimpleSourceInclusionScanner scanner = new SimpleSourceInclusionScanner(
                    Collections.singleton("**/*.java"), Collections.<String>emptySet());
            scanner.addSourceMapping(new SuffixMapping(JAVA_SRC_EXTENSION, Collections.<String>emptySet()));
            final Collection<File> sourceFiles = scanner.getIncludedSources(sourceDir, null);

            final int numSourceFiles = sourceFiles.size();
            fileCount += numSourceFiles;

            final String classpath = getRuntimeAndCompileClasspath();

            completeInferExecutions(classpath, inferOutputDir, sourceFiles, numSourceFiles);

            reportResults(inferOutputDir, numSourceFiles);
        } catch (final DependencyResolutionRequiredException e) {
            getLog().error(e);
            throw new MojoExecutionException("Unable to get required dependencies to perform Infer check!", e);
        } catch (final InclusionScanException e) {
            getLog().error(e);
            throw new MojoExecutionException("Failed to get sources! Cannot complete Infer check", e);
        }
    }

    /**
     * Gets/Creates the directory where Infer output will be written.
     *
     * @return the directory where Infer output will be written
     * @throws MojoExecutionException if the Infer output directory cannot be created
     */
    private File getInferOutputDir() throws MojoExecutionException {
        // infer output to build dir of project maven was run from
        final File outputDir = new File(inferDir, INFER_OUTPUT_DIRECTORY_NAME);
        try {
            FileUtils.forceMkdir(outputDir);
        } catch (final IOException e) {
            getLog().error(e);
            throw new MojoExecutionException("Exception occurred trying to generate output directory for Infer!", e);
        }

        return outputDir;
    }

    /**
     * Logs results of Infer check to the Maven console.
     * @param inferOutputDir directory where Infer wrote its results
     * @param numSourceFiles number of source files analyzed in this module
     */
    private void reportResults(File inferOutputDir, int numSourceFiles) {
        final File bugsFile = new File(inferOutputDir, INFER_BUG_REPORT_FILE_NAME);
        getLog().info("Infer output can be located at: " + inferOutputDir);
        getLog().info("");
        getLog().info("Results of Infer check:");

        if (bugsFile.exists()) {
            try {
                final String bugs;
                bugs = FileUtils.readFileToString(bugsFile, UTF_8);
                getLog().info(System.getProperty(LINE_SEPARATOR) + System.getProperty(LINE_SEPARATOR) + bugs);
            } catch (final IOException e) {
                getLog().error(
                        String.format(
                                "Exception occurred trying to read bugs report at: %s, no bugs will be reported.",
                                bugsFile.getAbsolutePath()), e);
            }
        } else {
            getLog().error("No bugs report generated; infer probably did not complete successfully.");
        }
        getLog().info("");
        getLog().info(
                String.format(
                        "Infer review complete; %s files were analyzed for this module, "
                                + "%s files have been analyzed so far, in total.", numSourceFiles, fileCount));

        // TODO: consider adding this when analyze doesn't fail.
        // printFailedChecks();
        getLog().info("");
    }

    /**
     * Executes infer once for each source file and writes the output to {@code inferOutputDir}.
     *
     * @param classpath classpath used as an argument to the javac command given to Infer.
     * @param inferOutputDir directory where Infer will write its output
     * @param sourceFiles collection of files for Infer to analyze
     * @param numSourceFiles number of source files to analyze; used to make sure every Infer execution finishes
     * before moving on.
     */
    private void completeInferExecutions(
            final String classpath, final File inferOutputDir, Collection<File> sourceFiles, int numSourceFiles)
            throws MojoExecutionException {
        // temporary directory for storing .class files created by {@code javac}; placed in build directory
        final File buildTmpDir = new File(project.getBuild().getDirectory(), JAVAC_OUTPUT_DIRECTORY_NAME);
        try {
            FileUtils.forceMkdir(buildTmpDir);
        } catch (final IOException e) {
            final String errMsg = String.format("Unable to make temp directory %s!", buildTmpDir.getAbsolutePath());
            getLog().error(errMsg, e);
            throw new MojoExecutionException(errMsg, e);
        }
        buildTmpDir.deleteOnExit();

        // used to wait for all processes running infer to complete
        final CountDownLatch doneSignal = new CountDownLatch(numSourceFiles);

        // TODO: optionally allow debugging info? Output directory?

        // TODO: a better way to do this may be to determine if there is an entry point that takes a set of source
        //  files and the classpath and use this. @See mvn, inferj and inferlib in the infer repository.

        ExecutorService pool = null;
        try {
            pool = Executors.newFixedThreadPool(4);

            for (final File sourceFile : sourceFiles) {
                final Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        Process proc = null;

                        try {
                            // infer
                            final List<String> command = new ArrayList<String>();
                            command.add(inferPath);
                            command.add("-i");
                            command.add("-o");
                            command.add(inferOutputDir.getAbsolutePath());

                            command.add("--");

                            // javac
                            command.add("javac");
                            command.add(sourceFile.getAbsolutePath());
                            command.add("-d");
                            command.add(buildTmpDir.getAbsolutePath());
                            command.add("-classpath");
                            command.add(classpath);

                            final ProcessBuilder builder = new ProcessBuilder(command);
                            builder.environment().putAll(System.getenv());

                            if (consoleOut) {
                                builder.redirectErrorStream(true);
                                proc = builder.start();

                                InputStreamReader isr = null;
                                BufferedReader br = null;
                                InputStream pis = null;

                                try {
                                    pis = proc.getInputStream();

                                    isr = new InputStreamReader(pis);
                                    br = new BufferedReader(isr);
                                    String line = null;
                                    while ((line = br.readLine()) != null) {
                                        getLog().info(line);
                                    }
                                } catch (final IOException e) {
                                    getLog().error(
                                            String.format(
                                                    "Error writing process output for file: %s.",
                                                    sourceFile.getAbsolutePath()), e);
                                } finally {
                                    if (isr != null) {
                                        isr.close();
                                    }

                                    if (br != null) {
                                        br.close();
                                    }

                                    if (pis != null) {
                                        pis.close();
                                    }
                                }
                            } else {
                                // no logging.
                                proc = builder.start();
                            }

                            // NOTE: most/all executions end in failure during analysis, however,
                            // supported java bugs are still reported
                            proc.waitFor();
                        } catch (final IOException e) {
                            getLog().error(
                                    "Exception occurred while trying to perform Infer execution; output not complete"
                                            + "", e);
                        } catch (final InterruptedException e) {
                            getLog().error(EARLY_EXECUTION_TERMINATION_EXCEPTION_MSG, e);
                        } finally {
                            try {
                                // currently they all fail, although java bugs are still reported
                                if (proc != null && proc.exitValue() != 0) {
                                    FAILED_CHECKS.put(sourceFile, proc.exitValue());
                                }
                            } catch (final Exception e) {
                                FAILED_CHECKS.put(sourceFile, -1);
                            }
                            doneSignal.countDown();
                        }
                    }
                };

                pool.submit(r);
            }
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
        try {
            doneSignal.await();
        } catch (final InterruptedException e) {
            getLog().error(EARLY_EXECUTION_TERMINATION_EXCEPTION_MSG, e);
        }
    }

    /**
     * Prints the classes which could not be analyzed successfully along with the status code that process failed with.
     */
    private void printFailedChecks() {
        if (!FAILED_CHECKS.isEmpty()) {
            getLog().info("The following checks failed: ");

            for (final Entry<File, Integer> entry : FAILED_CHECKS.entrySet()) {
                getLog().info(
                        String.format(
                                "Class: %s: Exit code: %s.", entry.getKey().getPath(), entry.getValue()));
            }
        }
    }

    /**
     * Generates a classpath with which source files in the main source directory can be compiled.
     *
     * @return a String containing the complete classpath, with entries separated by {@code :} so it can be given as the
     * classpath argument to javac
     * @throws DependencyResolutionRequiredException
     */
    private String getRuntimeAndCompileClasspath() throws DependencyResolutionRequiredException {
        final List compileClasspathElements = project.getCompileClasspathElements();
        final List runtimeClasspathElements = project.getRuntimeClasspathElements();

        final Set<String> classPathElements = new HashSet<String>();
        classPathElements.addAll(compileClasspathElements);
        classPathElements.addAll(runtimeClasspathElements);

        final StringBuilder classpath = new StringBuilder();
        boolean first = true;
        for (final String element : classPathElements) {
            if (!first) {
                classpath.append(':');
            }
            classpath.append(element);
            first = false;
        }

        return classpath.toString();
    }

    /**
     * Gets the path to the infer executable; currently unused
     *
     * @return the absolute path to the {@code infer} executable
     */
    private static String getInferPath() {
        final String path = System.getenv("PATH");
        for (final String dir : path.split(System.getProperty(PATH_SEPARATOR))) {
            final File probablyDir = new File(dir);
            if (probablyDir.isDirectory()) {
                final File maybeInfer = new File(probablyDir, "infer");
                if (maybeInfer.exists() && maybeInfer.isFile()) {
                    return maybeInfer.getAbsolutePath();
                }
            }
        }

        return null;
    }

    /**
     * Downloads a distribution of Infer appropriate for the current operating system or fails if the current
     * operating system is not supported.
     * @param inferDownloadDir directory to which to download Infer
     * @return the path to the executable Infer script
     * @throws MojoExecutionException if an Exception occurs that should fail execution
     */
    private String downloadInfer(File inferDownloadDir) throws MojoExecutionException {
        getLog().info("Maven-infer-plugin is configured to download Infer. Downloading now.");
        try {
            final OperatingSystem system = currentOs();
            final URL downloadUrl;
            if (system == OperatingSystem.OSX) {
                downloadUrl = new URL(OSX_INFER_DOWNLOAD_URL);
            } else if (system == OperatingSystem.LINUX) {
                downloadUrl = new URL(LINUX_INFER_DOWNLOAD_URL);
            } else {
                final String errMsg = String.format(
                        "Unsupported operating system: %s. Cannot continue Infer analysis.",
                        System.getProperty(OS_NAME));

                getLog().error(errMsg);
                throw new MojoExecutionException(errMsg);
            }
            getLog().info(String.format("Downloading: %s", downloadUrl.toString()));
            final File downloadedFile = new File(inferDownloadDir, downloadUrl.getFile());

            // TODO: could make these configurable
            FileUtils.copyURLToFile(downloadUrl, downloadedFile, CONNECTION_TIMEOUT, READ_TIMEOUT);

            getLog().info(String.format("Infer downloaded to %s; now extracting.", inferDownloadDir.getAbsolutePath()));

            extract(downloadedFile, inferDownloadDir);

            getLog().info("Infer has been extracted, continuing with Infer check.");

            final Collection<File> files = FileUtils.listFiles(inferDownloadDir, null, true);
            for (final File file : files) {
                if (INFER_CMD_NAME.equals(file.getName()) && "bin".equals(file.getParentFile().getName())) {
                    return file.getAbsolutePath();
                }
            }
        } catch (final IOException e) {
            final String errMsg = "Invalid URL! Cannot continue Infer check.";
            getLog().error(errMsg, e);
            throw new MojoExecutionException(errMsg, e);
        }
        throw new MojoExecutionException("unable to download infer! Aborting execution...");
    }

    /**
     * Gets the current operating system, in terms of its relevance to this Mojo.
     *
     * @return the current operating system or 'UNSUPPORTED'
     */
    private static OperatingSystem currentOs() {
        final String os = System.getProperty(OS_NAME).toLowerCase();
        if (os.contains("mac")) {
            return OperatingSystem.OSX;
        } else if (os.contains("nix") || os.contains("nux") || os.indexOf("aix") > 0) {
            return OperatingSystem.LINUX;
        } else {
            return OperatingSystem.UNSUPPORTED;
        }
    }

    private enum OperatingSystem {
        OSX,
        LINUX,
        UNSUPPORTED
    }

    /**
     * Extracts a given infer.tar.xz file to the given directory.
     *
     * @param tarXzToExtract the file to extract
     * @param inferDownloadDir the directory to extract the file to
     */
    private static void extract(File tarXzToExtract, File inferDownloadDir) throws IOException {

        FileInputStream fin = null;
        BufferedInputStream in = null;
        XZCompressorInputStream xzIn = null;
        TarArchiveInputStream tarIn = null;

        try {
            fin = new FileInputStream(tarXzToExtract);
            in = new BufferedInputStream(fin);
            xzIn = new XZCompressorInputStream(in);
            tarIn = new TarArchiveInputStream(xzIn);

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                final File fileToWrite = new File(inferDownloadDir, entry.getName());

                if (entry.isDirectory()) {
                    FileUtils.forceMkdir(fileToWrite);
                } else {
                    BufferedOutputStream out = null;
                    try {
                        out = new BufferedOutputStream(new FileOutputStream(fileToWrite));
                        final byte[] buffer = new byte[4096];
                        int n = 0;
                        while (-1 != (n = tarIn.read(buffer))) {
                            out.write(buffer, 0, n);
                        }
                    } finally {
                        if (out != null) {
                            out.close();
                        }
                    }
                }

                // assign file permissions
                final int mode = entry.getMode();

                fileToWrite.setReadable((mode & 0004) != 0, false);
                fileToWrite.setReadable((mode & 0400) != 0, true);
                fileToWrite.setWritable((mode & 0002) != 0, false);
                fileToWrite.setWritable((mode & 0200) != 0, true);
                fileToWrite.setExecutable((mode & 0001) != 0, false);
                fileToWrite.setExecutable((mode & 0100) != 0, true);
            }
        } finally {
            if (tarIn != null) {
                tarIn.close();
            }
        }
    }
}

