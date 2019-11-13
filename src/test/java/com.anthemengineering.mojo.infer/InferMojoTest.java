package com.anthemengineering.mojo.infer;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

class InferMojoTest {
  @Test
  void testDownload() throws MojoExecutionException {
    InferMojo infer = new InferMojo();
    infer.downloadInfer(Paths.get("src/test/resources").toFile());
    Assertions.assertNotNull(Paths.get("src/test/resources/infer-linux64-v0.17.0/bin/infer").toFile());
  }
}
