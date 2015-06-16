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

package com.anthemengineering;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Runs Infer over Java source files in the main source (test sources are ignored). Basic results information of the
 * Infer check is printed to the console and the output of infer is printed to {@code target/infer-out} in the
 * project Maven is run from.
 *</p>
 * For each source file, an execution of {@code infer -i -o [execution_dir/target/] -- javac [source_file.java]} is run.
 *</p>
 * If the directory Maven is run from is the parent of a multi module project, Infer results will continue to
 * accumulate in {@code target/infer-out/} as each module is built.
 * </p>
 * Java 8 is not yet supported by Infer.
 * </p>
 * Before this plugin executes, it is recommended that a {@code mvn clean} takes
 * place.
 */
@Mojo(name = "infer", defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class InferMojo extends AbstractMojo {

    /**
     * Total number of files analyzed during this build.
     */
    private static int fileCount;

    /**
     * Currently unused, this map keeps track of Infer executions that failed; since there is only one java source file
     * specifically targeted per process running Infer, this keeps a map of that source file name to the exit code of
     * the Infer process analyzing it.
     */
    private static final Map<File, Integer> FAILED_CHECKS = new HashMap<File, Integer>();

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * Display the output of each execution of Infer.
     */
    @Parameter(property = "infer.consoleOut")
    private boolean consoleOut;

    @Override
    public void execute() throws MojoExecutionException {

        try {
            // get source directory, if it doesn't exist then we're done
            final File sourceDir = new File(project.getBuild().getSourceDirectory());

            if (sourceDir == null || !sourceDir.exists()) {
                return;
            }

            final File inferOutputDir = getInferOutputDir();

            // collect source files
            final Collection<File> sourceFiles = FileUtils.listFiles(sourceDir, new String[] {"java"}, true);

            // TODO: fix me SimpleSourceInclusionScanner;
            //     new SimpleSourceInclusionScanner(Collections.singleton("**/*.java"), Collections.EMPTY_SET)
            //           .getIncludedSources(sourceDir, null);

            final int numSourceFiles = sourceFiles.size();
            fileCount = fileCount + numSourceFiles;

            final String classpath = getRuntimeAndCompileClasspath();

            completeInferExecutions(classpath, inferOutputDir, sourceFiles, numSourceFiles);

            reportResults(inferOutputDir, numSourceFiles);
        } catch (DependencyResolutionRequiredException e) {
            getLog().error(e);
            throw new MojoExecutionException("Unable to get required dependencies to perform Infer check!", e);
        }
        //catch (InclusionScanException e) {
        //    getLog().error(e);
        // }
    }

    /**
     * Gets/Creates the directory where Infer output will be written.
     *
     * @return the directory where Infer output will be written
     * @throws MojoExecutionException if the Infer output directory cannot be created
     */
    private File getInferOutputDir() throws MojoExecutionException {
        final File currentDir = new File(System.getProperty("user.dir"));

        // infer output to build dir of project maven was run from
        final File inferDir = new File(currentDir, "target/infer-out");
        try {
            FileUtils.forceMkdir(inferDir);
        } catch (final IOException e) {
            getLog().error(e);
            throw new MojoExecutionException("Exception occurred trying to generate output directory for Infer!", e);
        }

        return inferDir;
    }

    /**
     * Logs results of Infer check to the Maven console.
     * @param inferOutputDir directory where Infer wrote its results
     * @param numSourceFiles number of source files analyzed in this module
     */
    private void reportResults(File inferOutputDir, int numSourceFiles) {
        final File bugsFile = new File(inferOutputDir, "bugs.txt");
        getLog().info("Infer output can be located at: " + inferOutputDir.toString());
        getLog().info("");
        getLog().info("Results of Infer check:");

        if (bugsFile.exists()) {
            try {
                final String bugs;
                bugs = FileUtils.readFileToString(bugsFile, StandardCharsets.UTF_8);
                getLog().info(System.lineSeparator() + System.lineSeparator() + bugs);
            } catch (IOException e) {
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

        //TODO: consider adding this when analyze doesnt fail.
        //printFailedChecks();
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
        final File buildTmpDir = new File(project.getBuild().getDirectory(), "javacOut");
        try {
            FileUtils.forceMkdir(buildTmpDir);
        } catch (IOException e) {
            final String errMsg = String.format("Unable to temp directory %s!", buildTmpDir.getAbsolutePath());
            getLog().error(errMsg, e);
            throw new MojoExecutionException(errMsg, e);
        }
        buildTmpDir.deleteOnExit();

        // used to wait for all processes running infer to complete
        final CountDownLatch doneSignal = new CountDownLatch(numSourceFiles);

        // TODO: optionally allow debugging info? Output directory?

        // TODO: a better way to do this may be to determine if there is an entry point that takes a set of source
        //  files and the classpath and use this. @See mvn, inferj and inferlib in the infer repository.
        for (final File sourceFile : sourceFiles) {
            final Runnable r = new Runnable() {
                @Override
                public void run() {
                    Process proc = null;

                    try {
                        // infer
                        List<String> command = new ArrayList<String>();
                        command.add("infer");
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
                            builder.inheritIO();
                        }

                        proc = builder.start();

                        // NOTE: most/all executions end in failure during analysis, however,
                        // supported java bugs are still reported
                        proc.waitFor();
                    } catch (final IOException e) {
                        getLog().error(
                                "Exception occurred while trying to perform Infer execution; output not complete", e);
                    } catch (final InterruptedException e) {
                        getLog().error("Problem while waiting for Infer to finish; Infer output may be innacurate.", e);
                    } finally {
                        try {
                            // currently they all fail, although java bugs are still reported
                            if (proc.exitValue() != 0) {
                                FAILED_CHECKS.put(sourceFile, proc.exitValue());
                            }
                        } catch (Exception e) {
                            FAILED_CHECKS.put(sourceFile, -1);
                        }
                        doneSignal.countDown();
                    }
                }
            };

            new Thread(r).start();
        }

        try {
            doneSignal.await();
        } catch (final InterruptedException e) {
            getLog().error("Problem while waiting for Infer to finish; Infer output may be innacurate.", e);
        }
    }

    /**
     * Prints the classes which could not be analyzed successfully along with the status code that process failed with.
     */
    private void printFailedChecks() {
        if (!FAILED_CHECKS.isEmpty()) {
            getLog().info("The following checks failed: ");

            for (Map.Entry<File, Integer> entry : FAILED_CHECKS.entrySet()) {
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
        final List<String> compileClasspathElements = project.getCompileClasspathElements();
        final List<String> runtimeClasspathElements = project.getRuntimeClasspathElements();

        final Set<String> classPathElements = new HashSet();
        classPathElements.addAll(compileClasspathElements);
        classPathElements.addAll(runtimeClasspathElements);

        final StringBuilder classpath = new StringBuilder();
        boolean first = true;
        for (String element : classPathElements) {
            if (!first) {
                classpath.append(':');
            }
            classpath.append(element);
            first = false;
        }

        return classpath.toString();
    }

    /**
     * Currently unused.
     *
     * @return the absolute path to the {@code infer} executable
     */
    private String getInferPath() {
        final String path = System.getenv("PATH");
        for (String dir : path.split(System.getProperty("path.separator"))) {
            final File probablyDir = new File(dir);
            if (probablyDir.isDirectory()) {
                File maybeInfer = new File(probablyDir, "infer");
                if (maybeInfer.exists() && maybeInfer.isFile()) {
                    return maybeInfer.getAbsolutePath();
                }
            }
        }

        throw new NullPointerException("Cannot find infer on PATH, infer-maven-plugin aborting execution!");
    }
}
