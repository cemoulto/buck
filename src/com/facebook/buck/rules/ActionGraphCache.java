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

package com.facebook.buck.rules;

import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;

import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * A cache for the last ActionGraph buck created.
 */
public class ActionGraphCache {

  private static final String COUNTER_CATEGORY = "buck_action_graph_cache";
  private static final String CACHE_HIT_COUNTER_NAME = "cache_hit";
  private static final String CACHE_MISS_COUNTER_NAME = "cache_miss";
  private static final String NEW_AND_CACHED_ACTIONGRAPHS_MISMATCH_NAME =
      "new_and_cached_actiongraphs_mismatch";

  private final IntegerCounter cacheHitCounter;
  private final IntegerCounter cacheMissCounter;
  private final IntegerCounter actionGraphsMismatch;

  @Nullable
  private Pair<TargetGraph, ActionGraphAndResolver> lastActionGraph;

  public ActionGraphCache() {
    this.cacheHitCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        CACHE_HIT_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.cacheMissCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        CACHE_MISS_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.actionGraphsMismatch = new IntegerCounter(
        COUNTER_CATEGORY,
        NEW_AND_CACHED_ACTIONGRAPHS_MISMATCH_NAME,
        ImmutableMap.<String, String>of());
  }

  /**
   * It returns an {@link ActionGraphAndResolver}. If the {@code targetGraph} exists in the cache
   * it returns a cached version of the {@link ActionGraphAndResolver}, else creates one and
   * updates the cache.
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndResolver}
   */
  public ActionGraphAndResolver getActionGraph(
      BuckEventBus eventBus,
      final TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    Pair<TargetGraph, ActionGraphAndResolver> newActionGraph =
        new Pair<TargetGraph, ActionGraphAndResolver>(
            targetGraph,
            createActionGraph(eventBus,
                new DefaultTargetNodeToBuildRuleTransformer(),
                targetGraph));

    if (lastActionGraph != null && lastActionGraph.getFirst().equals(targetGraph)) {
      cacheHitCounter.inc();
    } else {
      cacheMissCounter.inc();
    }

    lastActionGraph = newActionGraph;
    eventBus.post(ActionGraphEvent.finished(started));
    return lastActionGraph.getSecond();
  }

  /**
   * * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking
   * the cache. It uses a {@link DefaultTargetNodeToBuildRuleTransformer}.
   * @param eventBus the {@link BuckEventBus} to post the events of the processing.
   * @param targetGraph the target graph that the action graph will be based on.
   * @return a {@link ActionGraphAndResolver}
   */
  public static ActionGraphAndResolver getFreshActionGraph(
      final BuckEventBus eventBus,
      final TargetGraph targetGraph) {
    TargetNodeToBuildRuleTransformer transformer = new DefaultTargetNodeToBuildRuleTransformer();
    return getFreshActionGraph(eventBus, transformer, targetGraph);
  }

  /**
   * It returns a new {@link ActionGraphAndResolver} based on the targetGraph without checking the
   * cache. It uses a custom {@link TargetNodeToBuildRuleTransformer}.
   * @param eventBus The {@link BuckEventBus} to post the events of the processing.
   * @param transformer Custom {@link TargetNodeToBuildRuleTransformer} that the transformation will
   *                    be based on.
   * @param targetGraph The target graph that the action graph will be based on.
   * @return It returns a {@link ActionGraphAndResolver}
   */
  public static ActionGraphAndResolver getFreshActionGraph(
      final BuckEventBus eventBus,
      final TargetNodeToBuildRuleTransformer transformer,
      final TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    ActionGraphAndResolver actionGraph = createActionGraph(eventBus, transformer, targetGraph);

    eventBus.post(ActionGraphEvent.finished(started));
    return actionGraph;
  }

  private static ActionGraphAndResolver createActionGraph(
      final BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph) {
    final BuildRuleResolver resolver = new BuildRuleResolver(targetGraph, transformer);

    final int numberOfNodes = targetGraph.getNodes().size();
    final AtomicInteger processedNodes = new AtomicInteger(0);

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            try {
              resolver.requireRule(node.getBuildTarget());
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }
            eventBus.post(ActionGraphEvent.processed(
                processedNodes.incrementAndGet(),
                numberOfNodes));
          }
        };
    bottomUpTraversal.traverse();

    return ActionGraphAndResolver.builder()
        .setActionGraph(new ActionGraph(resolver.getBuildRules()))
        .setResolver(resolver)
        .build();
  }

  public ImmutableList<Counter> getCounters() {
    return ImmutableList.<Counter>of(
        cacheHitCounter,
        cacheMissCounter,
        actionGraphsMismatch);
  }

  public void invalidateBasedOn(WatchEvent<?> event) throws InterruptedException {
    if (!isFileContentModificationEvent(event)) {
      invalidateCache();
    }
  }

  @Subscribe
  private static boolean isFileContentModificationEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_MODIFY;
  }

  private void invalidateCache() {
    lastActionGraph = null;
  }

  @VisibleForTesting
  boolean isEmpty() {
    return lastActionGraph == null;
  }
}
