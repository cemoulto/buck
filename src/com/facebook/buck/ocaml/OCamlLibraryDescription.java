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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.OCamlSource;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class OCamlLibraryDescription implements
    Description<OCamlLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<OCamlLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("ocaml_library");

  private final OCamlBuckConfig ocamlBuckConfig;

  public OCamlLibraryDescription(OCamlBuckConfig ocamlBuckConfig) {
    this.ocamlBuckConfig = ocamlBuckConfig;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AbstractBuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    ImmutableList<OCamlSource> srcs = args.srcs.get();
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(args.compilerFlags.get());
    if (ocamlBuckConfig.getWarningsFlags().isPresent() ||
        args.warningsFlags.isPresent()) {
      flags.add("-w");
      flags.add(ocamlBuckConfig.getWarningsFlags().or("") +
          args.warningsFlags.or(""));
    }
    ImmutableList<String> linkerflags = args.linkerFlags.get();
    return OCamlRuleBuilder.createBuildRule(
        ocamlBuckConfig,
        params,
        resolver,
        srcs,
        /*isLibrary*/ true,
        args.bytecodeOnly.or(false),
        flags.build(),
        linkerflags);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      OCamlLibraryDescription.Arg constructorArg) {
    return CxxPlatforms.getParseTimeDeps(ocamlBuckConfig.getCxxPlatform());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<ImmutableList<OCamlSource>> srcs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableList<String>> linkerFlags;
    public Optional<String> warningsFlags;
    public Optional<Boolean> bytecodeOnly;
  }

}
