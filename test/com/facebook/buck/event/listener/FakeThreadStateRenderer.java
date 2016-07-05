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

package com.facebook.buck.event.listener;

import com.google.common.collect.ImmutableList;

public class FakeThreadStateRenderer implements ThreadStateRenderer {
  private final ImmutableList<Long> threadIds;
  private boolean lastSortWasByTime = false;

  public FakeThreadStateRenderer(ImmutableList<Long> threadIds) {
    this.threadIds = threadIds;
  }

  @Override
  public int getThreadCount() {
    return threadIds.size();
  }

  @Override
  public ImmutableList<Long> getSortedThreadIds(boolean sortByTime) {
    lastSortWasByTime = sortByTime;
    return threadIds;
  }

  @Override
  public String renderStatusLine(long threadID, StringBuilder lineBuilder) {
    return " |=> Status of thread " + threadID;
  }

  @Override
  public String renderShortStatus(long threadID) {
    return "t" + threadID;
  }

  public boolean lastSortWasByTime() {
    return lastSortWasByTime;
  }
}
