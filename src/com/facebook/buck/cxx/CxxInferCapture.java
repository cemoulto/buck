/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Generate the CFG for a source file
 */
public class CxxInferCapture
    extends AbstractBuildRule
    implements RuleKeyAppendable, SupportsDependencyFileRuleKey {

  @AddToRuleKey
  private final InferBuckConfig inferConfig;
  private final CxxToolFlags preprocessorFlags;
  private final CxxToolFlags compilerFlags;
  @AddToRuleKey
  private final SourcePath input;
  private final CxxSource.Type inputType;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final PreprocessorDelegate preprocessorDelegate;

  private final Path resultsDir;
  private final DebugPathSanitizer sanitizer;

  CxxInferCapture(
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      CxxToolFlags preprocessorFlags,
      CxxToolFlags compilerFlags,
      SourcePath input,
      AbstractCxxSource.Type inputType,
      Path output,
      PreprocessorDelegate preprocessorDelegate,
      InferBuckConfig inferConfig,
      DebugPathSanitizer sanitizer) {
    super(buildRuleParams, pathResolver);
    this.preprocessorFlags = preprocessorFlags;
    this.compilerFlags = compilerFlags;
    this.input = input;
    this.inputType = inputType;
    this.output = output;
    this.preprocessorDelegate = preprocessorDelegate;
    this.inferConfig = inferConfig;
    this.resultsDir = BuildTargets.getGenPath(this.getBuildTarget(), "infer-out-%s");
    this.sanitizer = sanitizer;
  }

  private CxxToolFlags getSearchPathFlags() {
    return preprocessorDelegate.getFlagsWithSearchPaths();
  }

  private ImmutableList<String> getFrontendCommand() {
    // TODO(martinoluca): Add support for extra arguments (and add them to the rulekey)
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    return commandBuilder
        .add(this.inferConfig.getInferTopLevel().toString())
        .add("-a", "capture")
        .add("--project_root", getProjectFilesystem().getRootPath().toString())
        .add("--out", resultsDir.toString())
        .add("--")
        .add("clang")
        .add("-MD", "-MF", getTempDepFilePath().toString())
        .addAll(
            CxxToolFlags.concat(preprocessorFlags, getSearchPathFlags(), compilerFlags)
                .getAllFlags())
        .add("-x", inputType.getLanguage())
        .add("-o", output.toString()) // TODO(martinoluca): Use -fsyntax-only for better perf
        .add("-c")
        .add(getResolver().deprecatedGetPath(input).toString())
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList<String> frontendCommand = getFrontendCommand();
    buildableContext.recordArtifact(this.getPathToOutput());

    return ImmutableList.<Step>builder()
        .add(new MkdirStep(getProjectFilesystem(), resultsDir))
        .add(new MkdirStep(getProjectFilesystem(), output.getParent()))
        .add(
            new DefaultShellStep(
                getProjectFilesystem().getRootPath(),
                frontendCommand,
                ImmutableMap.<String, String>of()))
        .add(new ParseAndWriteBuckCompatibleDepfileStep(getTempDepFilePath(), getDepFilePath()))
        .build();
  }

  @Override
  public Path getPathToOutput() {
    return this.resultsDir;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    return builder
        .setReflectively(
            "platformPreprocessorFlags",
            sanitizer.sanitizeFlags(preprocessorFlags.getPlatformFlags()))
        .setReflectively(
            "rulePreprocessorFlags",
            sanitizer.sanitizeFlags(preprocessorFlags.getRuleFlags()))
        .setReflectively(
            "platformCompilerFlags",
            sanitizer.sanitizeFlags(compilerFlags.getPlatformFlags()))
        .setReflectively(
            "ruleCompilerFlags",
            sanitizer.sanitizeFlags(compilerFlags.getRuleFlags()));
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // include all inputs coming from the preprocessor tool.
    inputs.addAll(preprocessorDelegate.getInputsAfterBuildingLocally(readDepFileLines()));

    // Add the input.
    inputs.add(input);

    return inputs.build();
  }

  private Path getDepFilePath() {
    return output.getFileSystem().getPath(output.toString() + ".dep");
  }

  private Path getTempDepFilePath() {
    return output.getFileSystem().getPath(getDepFilePath().toString() + ".tmp.dep");
  }

  private ImmutableList<String> readDepFileLines() throws IOException {
    return ImmutableList.copyOf(getProjectFilesystem().readLines(getDepFilePath()));
  }


  private class ParseAndWriteBuckCompatibleDepfileStep implements Step {

    private Path sourceDepfile;
    private Path destDepfile;

    public ParseAndWriteBuckCompatibleDepfileStep(Path sourceDepfile, Path destDepfile) {
      this.sourceDepfile = sourceDepfile;
      this.destDepfile = destDepfile;
    }

    @Override
    public int execute(ExecutionContext context) throws IOException, InterruptedException {
      Depfiles.parseAndWriteBuckCompatibleDepfile(
          context,
          getProjectFilesystem(),
          preprocessorDelegate.getHeaderPathNormalizer(),
          preprocessorDelegate.getHeaderVerification(),
          sourceDepfile,
          destDepfile,
          getResolver().deprecatedGetPath(input),
          output);
      return 0;
    }

    @Override
    public String getShortName() {
      return "depfile-parse";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "Parse depfiles and write them in a Buck compatible format";
    }
  }

}
