/*
 * Copyright 2014-present Facebook, Inc.
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
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.keys.AbiRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CalculateAbiStepTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void shouldCalculateAbiFromAStubJar() throws IOException {
    Path outDir = temp.newFolder().toPath().toAbsolutePath();
    ProjectFilesystem filesystem = new ProjectFilesystem(outDir);

    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path source = directory.resolve("prebuilt/junit.jar");
    Path binJar = Paths.get("source.jar");
    Files.copy(source, outDir.resolve(binJar));

    Path abiJar = outDir.resolve("abi.jar");

    ExecutionContext executionContext = TestExecutionContext.newInstance();

    FakeBuildableContext context = new FakeBuildableContext();
    new CalculateAbiStep(context, filesystem, binJar, abiJar).execute(executionContext);

    String expectedHash = filesystem.computeSha1(Paths.get("abi.jar"));
    ImmutableMap<String, Object> metadata = context.getRecordedMetadata();
    Object seenHash = metadata.get(AbiRule.ABI_KEY_ON_DISK_METADATA);

    assertEquals(expectedHash, seenHash);

    // Hi there! This is hardcoded here because we want to make sure buck always produces the same
    // jar files across timezones and versions. If the test is failing because of an intentional
    // modification to how we produce abi .jar files, then just update the hash, otherwise please
    // investigate why the value is different.
    // NOTE: If this starts failing on CI for no obvious reason it's possible that the offset
    // calculation in ZipConstants.getFakeTime() does not account for DST correctly.
    assertEquals("8678c53b9ba104fac9626416959ff50e6fafc269", seenHash);
  }

  @Test
  public void fallsBackToCalculatingAbiFromInputJarIfClassFileIsMalformed() throws IOException {
    Path outDir = temp.newFolder().toPath().toAbsolutePath();
    ProjectFilesystem filesystem = new ProjectFilesystem(outDir);

    Path binJar = outDir.resolve("bad.jar");
    try (Zip zip = new Zip(binJar, true)){
      zip.add("Broken.class", "cafebabe bacon and cheese".getBytes(UTF_8));
    }
    String expectedHash = filesystem.computeSha1(binJar);

    Path abiJar = outDir.resolve("abi.jar");

    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .build();

    FakeBuildableContext context = new FakeBuildableContext();
    new CalculateAbiStep(context, filesystem, binJar, abiJar).execute(executionContext);

    ImmutableMap<String, Object> metadata = context.getRecordedMetadata();
    Object seenHash = metadata.get(AbiRule.ABI_KEY_ON_DISK_METADATA);

    assertEquals(expectedHash, seenHash);
  }
}
