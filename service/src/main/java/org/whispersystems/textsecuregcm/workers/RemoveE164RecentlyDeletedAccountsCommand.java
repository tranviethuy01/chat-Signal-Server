/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.workers;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Environment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.metrics.MetricsUtil;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class RemoveE164RecentlyDeletedAccountsCommand extends AbstractCommandWithDependencies {

  private static final String DRY_RUN_ARGUMENT = "dry-run";
  private static final String MAX_CONCURRENCY_ARGUMENT = "max-concurrency";
  private static final String SEGMENTS_ARGUMENT = "segments";
  private static final String BUFFER_ARGUMENT = "buffer";

  private static final String RECORDS_INSPECTED_COUNTER_NAME =
      MetricsUtil.name(RemoveE164RecentlyDeletedAccountsCommand.class, "recordsInspected");

  private static final String RECORDS_DELETED_COUNTER_NAME =
      MetricsUtil.name(RemoveE164RecentlyDeletedAccountsCommand.class, "recordsDeleted");

  private static final String DRY_RUN_TAG = "dryRun";

  private static final Logger logger = LoggerFactory.getLogger(RemoveE164RecentlyDeletedAccountsCommand.class);

  public RemoveE164RecentlyDeletedAccountsCommand() {

    super(new Application<>() {
      @Override
      public void run(final WhisperServerConfiguration configuration, final Environment environment) {
      }
    }, "remove-e164-recently-deleted-accounts", "Delete e164-associated recently-deleted account records");
  }

  @Override
  public void configure(final Subparser subparser) {
    super.configure(subparser);

    subparser.addArgument("--dry-run")
        .type(Boolean.class)
        .dest(DRY_RUN_ARGUMENT)
        .required(false)
        .setDefault(true)
        .help("If true, don’t actually delete any registration recovery passwords");

    subparser.addArgument("--max-concurrency")
        .type(Integer.class)
        .dest(MAX_CONCURRENCY_ARGUMENT)
        .setDefault(16)
        .help("Max concurrency for DynamoDB operations");

    subparser.addArgument("--segments")
        .type(Integer.class)
        .dest(SEGMENTS_ARGUMENT)
        .required(false)
        .setDefault(1)
        .help("The total number of segments for a DynamoDB scan");

    subparser.addArgument("--buffer")
        .type(Integer.class)
        .dest(BUFFER_ARGUMENT)
        .setDefault(16_384)
        .help("Records to buffer");
  }

  @Override
  protected void run(final Environment environment, final Namespace namespace,
      final WhisperServerConfiguration configuration, final CommandDependencies commandDependencies) throws Exception {

    final boolean dryRun = namespace.getBoolean(DRY_RUN_ARGUMENT);
    final int maxConcurrency = namespace.getInt(MAX_CONCURRENCY_ARGUMENT);
    final int segments = namespace.getInt(SEGMENTS_ARGUMENT);
    final int bufferSize = namespace.getInt(BUFFER_ARGUMENT);

    final Counter recordsInspectedCounter =
        Metrics.counter(RECORDS_INSPECTED_COUNTER_NAME, DRY_RUN_TAG, String.valueOf(dryRun));

    final Counter recordsDeletedCounter =
        Metrics.counter(RECORDS_DELETED_COUNTER_NAME, DRY_RUN_TAG, String.valueOf(dryRun));

    final AccountsManager accountsManager = commandDependencies.accountsManager();

    accountsManager.getE164sForRecentlyDeletedAccounts(segments, Schedulers.parallel())
        .buffer(bufferSize)
        .map(source -> {
          final List<String> shuffled = new ArrayList<>(source);
          Collections.shuffle(shuffled);
          return shuffled;
        })
        .limitRate(2)
        .flatMapIterable(Function.identity())
        .doOnNext(e164 -> recordsInspectedCounter.increment())
        .flatMap(e164 -> {
          final Mono<Void> deleteMono = dryRun
              ? Mono.empty()
              : Mono.fromFuture(() -> accountsManager.removeRecentlyDeletedAccountRecord(e164))
                  .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                  .onErrorResume(throwable -> {
                    logger.warn("Failed to remove recently-deleted account record for {}", e164, throwable);
                    return Mono.empty();
                  });

          return deleteMono.doOnSuccess(ignored -> recordsDeletedCounter.increment());
        }, maxConcurrency)
        .then()
        .block();
  }
}
