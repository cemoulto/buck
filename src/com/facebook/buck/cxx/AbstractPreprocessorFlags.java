/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import org.immutables.value.Value;

import java.nio.file.Path;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractPreprocessorFlags {

  /**
   * File set via {@code -include}.
   */
  @Value.Parameter
  public abstract Optional<SourcePath> getPrefixHeader();

  /**
   * Other flags included as is.
   */
  @Value.Parameter
  @Value.Default
  public CxxToolFlags getOtherFlags() {
    return CxxToolFlags.of();
  }

  @Value.Parameter
  public abstract ImmutableList<CxxHeaders> getIncludes();

  /**
   * Directories set via {@code -F}.
   */
  @Value.Parameter
  public abstract ImmutableSet<FrameworkPath> getFrameworkPaths();

  /**
   * Directories set via {@code -isystem}.
   */
  @Value.Parameter
  public abstract ImmutableSet<Path> getSystemIncludePaths();

  /**
   * Append to rule key the members which are not handled elsewhere.
   */
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder, DebugPathSanitizer sanitizer) {
    builder.setReflectively("prefixHeader", getPrefixHeader());
    builder.setReflectively("frameworkRoots", getFrameworkPaths());

    // Sanitize any relevant paths in the flags we pass to the preprocessor, to prevent them
    // from contributing to the rule key.
    builder.setReflectively(
        "platformPreprocessorFlags",
        sanitizer.sanitizeFlags(getOtherFlags().getPlatformFlags()));
    builder.setReflectively(
        "rulePreprocessorFlags",
        sanitizer.sanitizeFlags(getOtherFlags().getRuleFlags()));
    return builder;
  }

  public CxxToolFlags toToolFlags(
      SourcePathResolver resolver,
      Function<Path, Path> pathShortener,
      Function<FrameworkPath, Path> frameworkPathTransformer) {
    ExplicitCxxToolFlags.Builder builder = CxxToolFlags.explicitBuilder();
    ExplicitCxxToolFlags.addCxxToolFlags(builder, getOtherFlags());
    builder.addAllRuleFlags(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-include"),
            FluentIterable.from(getPrefixHeader().asSet())
                .transform(resolver.getAbsolutePathFunction())
                .transform(Functions.toStringFunction())));
    builder.addAllRuleFlags(
        CxxHeaders.getArgs(getIncludes(), resolver, Optional.of(pathShortener)));
    builder.addAllRuleFlags(
        MoreIterables.zipAndConcat(
            Iterables.cycle(CxxPreprocessables.IncludeType.SYSTEM.getFlag()),
            Iterables.transform(
                getSystemIncludePaths(),
                Functions.compose(Functions.toStringFunction(), pathShortener))));
    builder.addAllRuleFlags(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-F"),
            FluentIterable.from(getFrameworkPaths())
                .transform(frameworkPathTransformer)
                .transform(Functions.toStringFunction())
                .toSortedSet(Ordering.natural())));
    return builder.build();
  }

}
