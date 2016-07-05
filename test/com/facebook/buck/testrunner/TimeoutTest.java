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

package com.facebook.buck.testrunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.junit.Test;
import org.junit.runner.Computer;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

import java.util.Set;

public class TimeoutTest {

  /**
   * Verify that we avoid the issue where adding a timeout causes tests to be run on different
   * thread to the one that they were created on.
   *
   * https://github.com/junit-team/junit/issues/686
   */
  @Test
  public void testsShouldRunOnTheThreadTheyAreCreatedOn() throws InitializationError {
    Class<?> testClass = ThreadGuardedTest.class;

    RunnerBuilder builder = new RunnerBuilder() {
      @Override
      public Runner runnerForClass(Class<?> clazz) throws Throwable {
        return new BuckBlockJUnit4ClassRunner(clazz, /* defaultTestTimeoutMillis */ 10);
      }
    };
    Runner suite = new Computer().getSuite(builder, new Class<?>[]{testClass});
    Request request = Request.runner(suite);

    final Set<Result> results = Sets.newHashSet();
    JUnitCore core = new JUnitCore();
    core.addListener(new RunListener() {
      @Override
      public void testRunFinished(Result result) throws Exception {
        results.add(result);
      }
    });
    core.run(request);

    Result result = Iterables.getOnlyElement(results);
    assertEquals(3, result.getRunCount());
    assertEquals(2, result.getFailureCount());

    // The order in which the tests were run doesn't matter. What matters is that we see our
    // expected messages.
    Set<String> messages = FluentIterable
        .from(result.getFailures())
        .transform(new Function<Failure, String>() {
          @Override
          public String apply(Failure failure) {
            return failure.getMessage();
          }
        })
        .toSet();
    assertEquals(
        "Should contain explicit call to fail() from failingTestsAreReported() and " +
            "the timeout message from testsMayTimeOut().",
        ImmutableSet.of(
            "This is expected",
            "test testsMayTimeOut timed out after 10 milliseconds"),
        messages);
  }

  public static class ThreadGuardedTest {
    private long creatorThreadId = Thread.currentThread().getId();

    @Test
    public void verifyTestRunsOnCreatorThread() {
      assertEquals(creatorThreadId, Thread.currentThread().getId());
    }

    @Test
    public void testsMayTimeOut() throws InterruptedException {
      Thread.sleep(1000);
    }

    @Test
    public void failingTestsAreReported() {
      fail("This is expected");
    }
  }
}
