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

package com.facebook.buck.lua;

import com.facebook.buck.cxx.AbstractCxxLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;

import org.immutables.value.Value;

import java.nio.file.Paths;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractFakeLuaConfig implements LuaConfig {

  public static final FakeLuaConfig DEFAULT = FakeLuaConfig.builder().build();

  @Value.Default
  public Tool getLua() {
    return new CommandTool.Builder()
        .addArg("lua")
        .build();
  }

  @Override
  public Tool getLua(BuildRuleResolver resolver) {
    return getLua();
  }

  @Value.Default
  public AbstractCxxLibrary getLuaCxxLibrary() {
    return new SystemLuaCxxLibrary(
        BuildTarget.of(
            UnflavoredBuildTarget.of(Paths.get(""), Optional.<String>absent(), "//system", "lua")));
  }

  @Override
  public AbstractCxxLibrary getLuaCxxLibrary(BuildRuleResolver resolver) {
    return getLuaCxxLibrary();
  }

  @Override
  @Value.Default
  public String getExtension() {
    return ".lex";
  }

}
