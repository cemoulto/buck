/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Integration test that verifies that a {@link DefaultJavaLibrary} writes its ABI key as part
 * of compilation.
 */
public class DefaultJavaLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Test
  public void testBuildJavaLibraryWithoutSrcsAndVerifyAbi() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "abi", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // Run `buck build`.
    BuildTarget target = BuildTargetFactory.newInstance("//:no_srcs");
    ProcessResult buildResult = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");
    Path outputPath =
        BuildTargets.getGenPath(target, "lib__%s__output/" + target.getShortName() + ".jar");
    Path outputFile = workspace.getPath(outputPath);
    assertTrue(Files.exists(outputFile));
    // TODO(bolinfest): When we produce byte-for-byte identical JAR files across builds, do:
    //
    //   HashCode hashOfOriginalJar = Files.hash(outputFile, Hashing.sha1());
    //
    // And then compare that to the output when //:no_srcs is built again with --no-cache.
    long sizeOfOriginalJar = Files.size(outputFile);

    // This verifies that the ABI key was written correctly.
    workspace.verify();

    // Verify the build cache.
    Path buildCache = workspace.getPath(BuckConstant.DEFAULT_CACHE_DIR);
    assertTrue(Files.isDirectory(buildCache));

    ArtifactCache dirCache = TestArtifactCaches.createDirCacheForTest(
        workspace.getDestPath(),
        buildCache);

    int totalArtifactsCount = DirArtifactCacheTestUtil.getAllFilesInCache(dirCache).length;

    assertEquals("There should be two entries (a zip and metadata) in the build cache.",
        2,
        totalArtifactsCount);

    // Run `buck clean`.
    ProcessResult cleanResult = workspace.runBuckCommand("clean");
    cleanResult.assertSuccess("Successful clean should exit with 0.");

    totalArtifactsCount = getAllFilesInPath(buildCache).size();
    assertEquals("The build cache should still exist.", 2, totalArtifactsCount);

    // Corrupt the build cache!
    File artifactZip =
        FluentIterable.from(
            ImmutableList.copyOf(DirArtifactCacheTestUtil.getAllFilesInCache(dirCache)))
            .toSortedList(Ordering.natural())
            .get(0);
    FileSystem zipFs = FileSystems.newFileSystem(artifactZip.toPath(), /* loader */ null);
    Path outputInZip = zipFs.getPath("/" + outputPath.toString());
    Files.write(outputInZip, "Hello world!".getBytes(), WRITE);
    zipFs.close();

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    buildResult2.assertSuccess("Successful build should exit with 0.");
    assertTrue(Files.isRegularFile(outputFile));
    assertEquals(
        "The content of the output file will be 'Hello World!' if it is read from the build cache.",
        "Hello world!",
        new String(Files.readAllBytes(outputFile), UTF_8));

    // Run `buck clean` followed by `buck build` yet again, but this time, specify `--no-cache`.
    ProcessResult cleanResult2 = workspace.runBuckCommand("clean");
    cleanResult2.assertSuccess("Successful clean should exit with 0.");
    ProcessResult buildResult3 =
        workspace.runBuckCommand("build", "--no-cache", target.getFullyQualifiedName());
    buildResult3.assertSuccess();
    assertNotEquals(
        "The contents of the file should no longer be pulled from the corrupted build cache.",
        "Hello world!",
        new String(Files.readAllBytes(outputFile), UTF_8));
    assertEquals(
        "We cannot do a byte-for-byte comparision with the original JAR because timestamps might " +
            "have changed, but we verify that they are the same size, as a proxy.",
        sizeOfOriginalJar,
        Files.size(outputFile));
  }

  @Test
  public void testBucksClasspathNotOnBuildClasspath() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "guava_no_deps", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:foo");
    buildResult.assertFailure(
        "Build should have failed since //:foo depends on Guava and " +
            "Args4j but does not include it in its deps.");

    workspace.verify();
  }

  @Test
  public void testNoDepsCompilesCleanly() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "guava_no_deps", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:bar");
    buildResult.assertSuccess("Build should have succeeded.");

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryWithFirstOrder() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "warn_on_transitive", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build",
        "//:raz",
        "-b",
        "FIRST_ORDER_ONLY");
    buildResult.assertFailure("Build should have failed.");

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryShouldSuggestTransitiveImportsToInclude() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "warn_on_transitive", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build",
        "//:raz");

    String expectedWarning = Joiner.on("\n").join(
      "Rule //:raz has failed to build.",
      "Blargh",
      "Meh",
      "Try adding the following deps:",
      "//:foo",
      "//:blargh");

    buildResult.assertFailure("Build should have failed with warnings.");

    assertThat(
        buildResult.getStderr(),
        containsString(expectedWarning));

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryExportsDirectoryEntries() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "export_directory_entries", tmp);
    workspace.setUp();

    // Run `buck build`.
    BuildTarget target = BuildTargetFactory.newInstance("//:empty_directory_entries");
    ProcessResult buildResult = workspace.runBuckBuild(target.getFullyQualifiedName());
    buildResult.assertSuccess();

    Path outputFile = workspace.getPath(
        BuildTargets.getGenPath(target, "lib__%s__output/" + target.getShortName() + ".jar"));
    assertTrue(Files.exists(outputFile));

    ImmutableSet.Builder<String> jarContents = ImmutableSet.builder();
    try (ZipFile zipFile = new ZipFile(outputFile.toFile())) {
      for (ZipEntry zipEntry : Collections.list(zipFile.entries())) {
        jarContents.add(zipEntry.getName());
      }
    }

    // TODO(mread): Change the output to the intended output.
    assertEquals(
        jarContents.build(),
        ImmutableSet.of(
          "META-INF/MANIFEST.MF",
          "swag.txt",
          "yolo.txt"));

    workspace.verify();
  }

  @Test
  public void testFileChangeThatDoesNotModifyAbiAvoidsRebuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "rulekey_changed_while_abi_stable", tmp);
    workspace.setUp();

    // Run `buck build`.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    BuildTarget utilTarget = BuildTargetFactory.newInstance("//:util");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    Path utilRuleKeyPath = BuildTargets.getScratchPath(utilTarget, ".%s/metadata/RULE_KEY");
    String utilRuleKey = getContents(utilRuleKeyPath);
    Path utilAbiRuleKeyPath =
        BuildTargets.getScratchPath(utilTarget, ".%s/metadata/INPUT_BASED_RULE_KEY");
    String utilAbiRuleKey = getContents(utilAbiRuleKeyPath);

    Path bizRuleKeyPath = BuildTargets.getScratchPath(bizTarget, ".%s/metadata/RULE_KEY");
    String bizRuleKey = getContents(bizRuleKeyPath);
    Path bizAbiRuleKeyPath =
        BuildTargets.getScratchPath(bizTarget, ".%s/metadata/INPUT_BASED_RULE_KEY");
    String bizAbiRuleKey = getContents(bizAbiRuleKeyPath);

    Path utilOutputPath = BuildTargets.getGenPath(
        utilTarget,
        "lib__%s__output/" + utilTarget.getShortName() + ".jar");
    long utilJarSize = Files.size(workspace.getPath(utilOutputPath));
    Path bizOutputPath = BuildTargets.getGenPath(
        bizTarget,
        "lib__%s__output/" + bizTarget.getShortName() + ".jar");
    FileTime bizJarLastModified = Files.getLastModifiedTime(workspace.getPath(bizOutputPath));

    // TODO(bolinfest): Run uber-biz.jar and verify it prints "Hello World!\n".

    // Edit Util.java in a way that does not affect its ABI.
    workspace.replaceFileContents("Util.java", "Hello World", "Hola Mundo");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:biz");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    assertThat(utilRuleKey, not(equalTo(getContents(utilRuleKeyPath))));
    assertThat(utilAbiRuleKey,
        not(equalTo(getContents(utilAbiRuleKeyPath))));

    assertThat(bizRuleKey, not(equalTo(getContents(bizRuleKeyPath))));
    assertEquals(bizAbiRuleKey, getContents(bizAbiRuleKeyPath));

    assertThat(
        "util.jar should have been rewritten, so its file size should have changed.",
        utilJarSize,
        not(equalTo(Files.size(workspace.getPath(utilOutputPath)))));
    assertEquals(
        "biz.jar should not have been rewritten, so its last-modified time should be the same.",
        bizJarLastModified,
        Files.getLastModifiedTime(workspace.getPath(bizOutputPath)));

    // TODO(bolinfest): Run uber-biz.jar and verify it prints "Hola Mundo!\n".

    // TODO(bolinfest): This last scenario that is being tested would be better as a unit test.
    // Run `buck build` one last time. This ensures that a dependency java_library() rule (:util)
    // that is built via BuildRuleSuccess.Type.MATCHING_INPUT_BASED_RULE_KEY does not
    // explode when its dependent rule (:biz) invokes the dependency's getAbiKey() method as part of
    // its own getAbiKeyForDeps().
    ProcessResult buildResult3 = workspace.runBuckCommand("build", "//:biz");
    buildResult3.assertSuccess("Successful build should exit with 0.");
  }

  @Test
  public void testClassUsageFileOutputProperly() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "class_usage_file", tmp);
    workspace.setUp();

    // Run `buck build`.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    Path bizClassUsageFilePath = BuildTargets.getGenPath(
        bizTarget,
        "lib__%s__used_classes/used-classes.json");

    final List<String> lines = Files.readAllLines(
        workspace.getPath(bizClassUsageFilePath), UTF_8);

    assertEquals("Expected just one line of JSON", 1, lines.size());

    final String expected =
        "{\"buck-out/gen/lib__util__output/util.jar\":[\"com/example/Util.class\"]}";
    final String actual = lines.get(0);

    assertEquals(expected, actual);
  }

  @Test
  public void updatingAResourceWhichIsJavaLibraryCausesAJavaLibraryToBeRepacked()
      throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "resource_change_causes_repack", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:lib");
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.copyFile("ResClass.java.new", "ResClass.java");
    workspace.resetBuildLogFile();

    // The copied file changed the contents but not the ABI of :lib. Because :lib is included as a
    // resource of :res, it's expected that both :lib and :res are rebuilt (:lib because of a code
    // change, :res in order to repack the resource)
    buildResult = workspace.runBuckCommand("build", "//:lib");
    buildResult.assertSuccess("Successful build should exit with 0.");
    workspace.getBuildLog().assertTargetBuiltLocally("//:res");
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
  }

  @Test
  public void ensureProvidedDepsAreIncludedWhenCompilingButNotWhenPackaging() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "provided_deps", tmp);
    workspace.setUp();

    // Run `buck build`.
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    BuildTarget binary2Target = BuildTargetFactory.newInstance("//:binary_2");
    ProcessResult buildResult = workspace.runBuckCommand(
        "build",
        binaryTarget.getFullyQualifiedName(),
        binary2Target.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    for (Path filename :
        new Path[]{
            BuildTargets.getGenPath(binaryTarget, "%s.jar"),
            BuildTargets.getGenPath(binary2Target, "%s.jar")}) {
      Path file = workspace.getPath(filename);
      try (Zip zip = new Zip(file, /* for writing? */ false)) {
        Set<String> allNames = zip.getFileNames();
        // Representative file from provided_deps we don't expect to be there.
        assertFalse(allNames.contains("org/junit/Test.class"));

        // Representative file from the deps that we do expect to be there.
        assertTrue(allNames.contains("com/google/common/collect/Sets.class"));

        // The file we built.
        assertTrue(allNames.contains("com/facebook/buck/example/Example.class"));
      }
    }
  }

  @Test
  public void ensureChangingDepFromProvidedToTransitiveTriggersRebuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "provided_deps", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:binary").assertSuccess("Successful build should exit with 0.");

    workspace.replaceFileContents("BUCK", "provided_deps = [ ':junit' ],", "");
    workspace.replaceFileContents("BUCK", "deps = [ ':guava' ]", "deps = [ ':guava', ':junit' ]");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//:binary").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:binary");
  }

  @Test
  public void ensureThatSourcePathIsSetSensibly() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sourcepath",
        tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:b");

    // This should fail, since we expect the symbol for A not to be found.
    result.assertFailure();
    String stderr = result.getStderr();

    assertTrue(stderr, stderr.contains("cannot find symbol"));
  }

  @Test
  public void testSaveClassFilesToDisk() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "spool_class_files_to_disk",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());

    result.assertSuccess();

    Path classesDir = workspace.getPath(BuildTargets.getScratchPath(target, "lib__%s__classes"));
    assertTrue(Files.exists(classesDir));
    assertTrue(Files.isDirectory(classesDir));
    ArrayList<String> classFiles = new ArrayList<>();
    for (File file : classesDir.toFile().listFiles()) {
      classFiles.add(file.getName());
    }
    assertThat(
        "There should be 2 class files saved to disk from the compiler",
        classFiles, hasSize(2));
    assertThat(classFiles, hasItem("A.class"));
    assertThat(classFiles, hasItem("B.class"));
  }

  @Test
  public void testSpoolClassFilesDirectlyToJar() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "spool_class_files_directly_to_jar",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());

    result.assertSuccess();

    Path classesDir = workspace.getPath(BuildTargets.getScratchPath(target, "lib__%s__classes"));

    assertThat(Files.exists(classesDir), is(Boolean.TRUE));
    assertThat(
        "There should be no class files in disk",
        ImmutableList.copyOf(classesDir.toFile().listFiles()), hasSize(0));

    Path jarPath = workspace.getPath(BuildTargets.getGenPath(target, "lib__%s__output/a.jar"));
    assertTrue(Files.exists(jarPath));
    ZipInputStream zip = new ZipInputStream(new FileInputStream(jarPath.toFile()));
    assertThat(zip.getNextEntry().getName(), is("A.class"));
    assertThat(zip.getNextEntry().getName(), is("B.class"));
    zip.close();
  }

  /**
   * Asserts that the specified file exists and returns its contents.
   */
  private String getContents(Path relativePathToFile) throws IOException {
    Path file = workspace.getPath(relativePathToFile);
    assertTrue(relativePathToFile + " should exist and be an ordinary file.", Files.exists(file));
    String content = Strings.nullToEmpty(new String(Files.readAllBytes(file), UTF_8)).trim();
    assertFalse(relativePathToFile + " should not be empty.", content.isEmpty());
    return content;
  }

  private ImmutableList<Path> getAllFilesInPath(Path path) throws IOException {
    final List<Path> allFiles = new ArrayList<>();
    Files.walkFileTree(
        path,
        ImmutableSet.<FileVisitOption>of(),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file,
              BasicFileAttributes attrs) throws IOException {
            allFiles.add(file);
            return super.visitFile(file, attrs);
          }
        });
    return ImmutableList.copyOf(allFiles);
  }
}
