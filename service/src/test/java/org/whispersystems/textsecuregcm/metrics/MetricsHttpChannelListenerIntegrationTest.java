/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.net.HttpHeaders;
import io.dropwizard.core.Application;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.security.Principal;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.security.auth.Subject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.whispersystems.textsecuregcm.storage.ClientReleaseManager;
import org.whispersystems.websocket.WebSocketResourceProviderFactory;
import org.whispersystems.websocket.configuration.WebSocketConfiguration;
import org.whispersystems.websocket.setup.WebSocketEnvironment;

@ExtendWith(DropwizardExtensionsSupport.class)
class MetricsHttpChannelListenerIntegrationTest {

  private static final TrafficSource TRAFFIC_SOURCE = TrafficSource.HTTP;
  private static final MeterRegistry METER_REGISTRY = mock(MeterRegistry.class);
  private static final Counter COUNTER = mock(Counter.class);
  private static final AtomicReference<CompletableFuture<Void>> LISTENER_FUTURE_REFERENCE = new AtomicReference<>();

  private static final DropwizardAppExtension<Configuration> EXTENSION = new DropwizardAppExtension<>(
      MetricsHttpChannelListenerIntegrationTest.TestApplication.class);

  @AfterEach
  void teardown() {
    reset(METER_REGISTRY);
    reset(COUNTER);
  }

  @ParameterizedTest
  @MethodSource
  @SuppressWarnings("unchecked")
  void testSimplePath(String requestPath, String expectedTagPath, String expectedResponse) throws Exception {

    final CompletableFuture<Void> listenerCompleteFuture = new CompletableFuture<>();
    LISTENER_FUTURE_REFERENCE.set(listenerCompleteFuture);

    final ArgumentCaptor<Iterable<Tag>> tagCaptor = ArgumentCaptor.forClass(Iterable.class);
    when(METER_REGISTRY.counter(anyString(), any(Iterable.class)))
        .thenAnswer(a -> MetricsHttpChannelListener.REQUEST_COUNTER_NAME.equals(a.getArgument(0, String.class))
            ? COUNTER
            : mock(Counter.class))
        .thenReturn(COUNTER);

    Client client = EXTENSION.client();

    final String response = client.target(
            String.format("http://localhost:%d%s", EXTENSION.getLocalPort(), requestPath))
        .request()
        .header(HttpHeaders.USER_AGENT, "Signal-Android/4.53.7 (Android 8.1)")
        .get(String.class);

    assertEquals(expectedResponse, response);

    listenerCompleteFuture.get(1000, TimeUnit.MILLISECONDS);

    verify(METER_REGISTRY).counter(eq(MetricsHttpChannelListener.REQUEST_COUNTER_NAME), tagCaptor.capture());
    verify(COUNTER).increment();

    final Iterable<Tag> tagIterable = tagCaptor.getValue();
    final Set<Tag> tags = new HashSet<>();

    for (final Tag tag : tagIterable) {
      tags.add(tag);
    }

    assertEquals(5, tags.size());
    assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.PATH_TAG, expectedTagPath)));
    assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.METHOD_TAG, "GET")));
    assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.STATUS_CODE_TAG, "200")));
    assertTrue(
        tags.contains(Tag.of(MetricsHttpChannelListener.TRAFFIC_SOURCE_TAG, TRAFFIC_SOURCE.name().toLowerCase())));
    assertTrue(tags.contains(Tag.of(UserAgentTagUtil.PLATFORM_TAG, "android")));
  }

  static Stream<Arguments> testSimplePath() {
    return Stream.of(
        Arguments.of("/v1/test/hello", "/v1/test/hello", "Hello!"),
        Arguments.of("/v1/test/greet/friend", "/v1/test/greet/{name}",
            String.format(TestResource.GREET_FORMAT, "friend"))
    );
  }

  public static class TestApplication extends Application<Configuration> {

    @Override
    public void run(final Configuration configuration,
        final Environment environment) throws Exception {

      final MetricsHttpChannelListener metricsHttpChannelListener = new MetricsHttpChannelListener(
          METER_REGISTRY,
          mock(ClientReleaseManager.class));

      metricsHttpChannelListener.configure(environment);
      environment.lifecycle().addEventListener(new TestListener(LISTENER_FUTURE_REFERENCE));

      environment.jersey().register(new TestResource());

      // WebSocket set up
      final WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration();

      WebSocketEnvironment<TestPrincipal> webSocketEnvironment = new WebSocketEnvironment<>(environment,
          webSocketConfiguration, Duration.ofMillis(1000));

      webSocketEnvironment.jersey().register(new TestResource());

      JettyWebSocketServletContainerInitializer.configure(environment.getApplicationContext(), null);

      WebSocketResourceProviderFactory<TestPrincipal> webSocketServlet = new WebSocketResourceProviderFactory<>(
          webSocketEnvironment, TestPrincipal.class, webSocketConfiguration, "ignored");

      environment.servlets().addServlet("WebSocket", webSocketServlet);
    }
  }

  /**
   * A simple listener to signal that {@link HttpChannel.Listener} has completed its work, since its onComplete() is on
   * a different thread from the one that sends the response, creating a race condition between the listener and the
   * test assertions
   */
  static class TestListener implements HttpChannel.Listener, Container.Listener, LifeCycle.Listener {

    private final AtomicReference<CompletableFuture<Void>> completableFutureAtomicReference;

    TestListener(AtomicReference<CompletableFuture<Void>> completableFutureAtomicReference) {

      this.completableFutureAtomicReference = completableFutureAtomicReference;
    }

    @Override
    public void onComplete(final Request request) {
      completableFutureAtomicReference.get().complete(null);
    }

    @Override
    public void beanAdded(final Container parent, final Object child) {
      if (child instanceof Connector connector) {
          connector.addBean(this);
      }
    }

    @Override
    public void beanRemoved(final Container parent, final Object child) {

    }

  }

  @Path("/v1/test")
  public static class TestResource {

    static final String GREET_FORMAT = "Hello, %s!";


    @GET
    @Path("/hello")
    public String testGetHello() {
      return "Hello!";
    }

    @GET
    @Path("/greet/{name}")
    public String testGreetByName(@PathParam("name") String name, @Context ContainerRequestContext context) {

      context.setProperty("uriInfo", context.getUriInfo());

      return String.format(GREET_FORMAT, name);
    }
  }

  public static class TestPrincipal implements Principal {

    // Principal implementation

    @Override
    public String getName() {
      return null;
    }

    @Override
    public boolean implies(final Subject subject) {
      return false;
    }
  }
}
