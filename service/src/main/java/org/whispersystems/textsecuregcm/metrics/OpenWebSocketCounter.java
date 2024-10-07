package org.whispersystems.textsecuregcm.metrics;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.whispersystems.textsecuregcm.util.ua.ClientPlatform;
import org.whispersystems.textsecuregcm.util.ua.UnrecognizedUserAgentException;
import org.whispersystems.textsecuregcm.util.ua.UserAgentUtil;
import org.whispersystems.websocket.session.WebSocketSessionContext;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OpenWebSocketCounter {

  private final Map<ClientPlatform, AtomicInteger> openWebsocketsByClientPlatform;
  private final AtomicInteger openWebsocketsFromUnknownPlatforms;

  private final Map<ClientPlatform, Timer> durationTimersByClientPlatform;
  private final Timer durationTimerForUnknownPlatforms;

  public OpenWebSocketCounter(final String openWebSocketGaugeName, final String durationTimerName) {
    this(openWebSocketGaugeName, durationTimerName, Tags.empty());
  }

  public OpenWebSocketCounter(final String openWebSocketGaugeName, final String durationTimerName, final Tags tags) {
    openWebsocketsByClientPlatform = Arrays.stream(ClientPlatform.values())
            .collect(Collectors.toMap(
                Function.identity(),
                clientPlatform -> buildGauge(openWebSocketGaugeName, clientPlatform.name().toLowerCase(), tags),
                (a, b) -> {
                  throw new AssertionError("Duplicate client platform enumeration key");
                },
                () -> new EnumMap<>(ClientPlatform.class)
            ));

    openWebsocketsFromUnknownPlatforms = buildGauge(openWebSocketGaugeName, "unknown", tags);

    durationTimersByClientPlatform = Arrays.stream(ClientPlatform.values())
        .collect(Collectors.toMap(
            clientPlatform -> clientPlatform,
            clientPlatform -> buildTimer(durationTimerName, clientPlatform.name().toLowerCase(), tags),
            (a, b) -> {
              throw new AssertionError("Duplicate client platform enumeration key");
            },
            () -> new EnumMap<>(ClientPlatform.class)
        ));

    durationTimerForUnknownPlatforms = buildTimer(durationTimerName, "unknown", tags);
  }

  private static AtomicInteger buildGauge(final String gaugeName, final String clientPlatformName, final Tags tags) {
    return Metrics.gauge(gaugeName,
        tags.and(Tag.of(UserAgentTagUtil.PLATFORM_TAG, clientPlatformName)),
        new AtomicInteger(0));
  }

  private static Timer buildTimer(final String timerName, final String clientPlatformName, final Tags tags) {
    return Timer.builder(timerName)
        .publishPercentileHistogram(true)
        .tags(tags.and(Tag.of(UserAgentTagUtil.PLATFORM_TAG, clientPlatformName)))
        .register(Metrics.globalRegistry);
  }

  public void countOpenWebSocket(final WebSocketSessionContext context) {
    final Timer.Sample sample = Timer.start();

    // We have to jump through some hoops here to have something "effectively final" for the close listener, but
    // assignable from a `catch` block.
    final AtomicInteger openWebSocketCounter;
    final Timer durationTimer;

    {
      AtomicInteger calculatedOpenWebSocketCounter;
      Timer calculatedDurationTimer;

      try {
        final ClientPlatform clientPlatform =
            UserAgentUtil.parseUserAgentString(context.getClient().getUserAgent()).getPlatform();

        calculatedOpenWebSocketCounter = openWebsocketsByClientPlatform.get(clientPlatform);
        calculatedDurationTimer = durationTimersByClientPlatform.get(clientPlatform);
      } catch (final UnrecognizedUserAgentException e) {
        calculatedOpenWebSocketCounter = openWebsocketsFromUnknownPlatforms;
        calculatedDurationTimer = durationTimerForUnknownPlatforms;
      }

      openWebSocketCounter = calculatedOpenWebSocketCounter;
      durationTimer = calculatedDurationTimer;
    }

    openWebSocketCounter.incrementAndGet();

    context.addWebsocketClosedListener((context1, statusCode, reason) -> {
      sample.stop(durationTimer);
      openWebSocketCounter.decrementAndGet();
    });
  }
}