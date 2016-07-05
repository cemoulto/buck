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

package com.facebook.buck.go;

import static org.junit.Assume.assumeNoException;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;

abstract class GoAssumptions {
  public static void assumeGoCompilerAvailable() throws InterruptedException, IOException {
    Throwable exception = null;
    try {
      ProcessExecutor executor = new ProcessExecutor(new TestConsole());
      new GoBuckConfig(
          FakeBuckConfig.builder().build(),
          executor,
          FlavorDomain.from("Cxx", ImmutableSet.<CxxPlatform>of())).getCompiler();
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }
}
