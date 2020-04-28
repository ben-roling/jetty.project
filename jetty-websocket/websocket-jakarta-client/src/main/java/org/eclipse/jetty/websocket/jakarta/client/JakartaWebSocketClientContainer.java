//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.jakarta.client.internal.JsrUpgradeListener;
import org.eclipse.jetty.websocket.jakarta.common.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketExtensionConfig;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory;

/**
 * Container for Client use of the jakarta.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
@ManagedObject("JSR356 Client Container")
public class JakartaWebSocketClientContainer extends JakartaWebSocketContainer implements jakarta.websocket.WebSocketContainer
{
    protected WebSocketCoreClient coreClient;
    protected Function<WebSocketComponents, WebSocketCoreClient> coreClientFactory;
    private final JakartaWebSocketClientFrameHandlerFactory frameHandlerFactory;

    public JakartaWebSocketClientContainer()
    {
        this(new WebSocketComponents());
    }

    /**
     * Create a {@link jakarta.websocket.WebSocketContainer} using the supplied
     * {@link HttpClient} for environments where you want to configure
     * SSL/TLS or Proxy behaviors.
     *
     * @param httpClient the HttpClient instance to use
     */
    public JakartaWebSocketClientContainer(final HttpClient httpClient)
    {
        this(new WebSocketComponents(), (wsComponents) ->
        {
            WebSocketCoreClient coreClient = new WebSocketCoreClient(httpClient, wsComponents);
            coreClient.getHttpClient().setName("Jakarta-WebSocketClient@" + Integer.toHexString(coreClient.getHttpClient().hashCode()));
            return coreClient;
        });
    }

    public JakartaWebSocketClientContainer(WebSocketComponents components)
    {
        this(components, (wsComponents) ->
        {
            WebSocketCoreClient coreClient = new WebSocketCoreClient(wsComponents);
            coreClient.getHttpClient().setName("Jakarta-WebSocketClient@" + Integer.toHexString(coreClient.getHttpClient().hashCode()));
            return coreClient;
        });
    }

    public JakartaWebSocketClientContainer(WebSocketComponents components, Function<WebSocketComponents, WebSocketCoreClient> coreClientFactory)
    {
        super(components);
        this.coreClientFactory = coreClientFactory;
        this.frameHandlerFactory = new JakartaWebSocketClientFrameHandlerFactory(this);
    }

    protected HttpClient getHttpClient()
    {
        return getWebSocketCoreClient().getHttpClient();
    }

    protected WebSocketCoreClient getWebSocketCoreClient()
    {
        if (coreClient == null)
        {
            coreClient = coreClientFactory.apply(components);
            addManaged(coreClient);
        }

        return coreClient;
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param upgradeRequest the upgrade request information
     * @return the future for the session, available on success of connect
     */
    private CompletableFuture<Session> connect(JakartaClientUpgradeRequest upgradeRequest)
    {
        upgradeRequest.setConfiguration(defaultCustomizer);
        CompletableFuture<Session> futureSession = new CompletableFuture<>();

        try
        {
            WebSocketCoreClient coreClient = getWebSocketCoreClient();
            coreClient.connect(upgradeRequest).whenComplete((coreSession, error) ->
            {
                if (error != null)
                {
                    futureSession.completeExceptionally(error);
                    return;
                }

                JakartaWebSocketFrameHandler frameHandler = (JakartaWebSocketFrameHandler)upgradeRequest.getFrameHandler();
                futureSession.complete(frameHandler.getSession());
            });
        }
        catch (Exception e)
        {
            futureSession.completeExceptionally(e);
        }

        return futureSession;
    }

    private Session connect(ConfiguredEndpoint configuredEndpoint, URI destURI) throws IOException
    {
        Objects.requireNonNull(configuredEndpoint, "WebSocket configured endpoint cannot be null");
        Objects.requireNonNull(destURI, "Destination URI cannot be null");

        JakartaClientUpgradeRequest upgradeRequest = new JakartaClientUpgradeRequest(this, getWebSocketCoreClient(), destURI, configuredEndpoint);

        EndpointConfig config = configuredEndpoint.getConfig();
        if (config instanceof ClientEndpointConfig)
        {
            ClientEndpointConfig clientEndpointConfig = (ClientEndpointConfig)config;

            JsrUpgradeListener jsrUpgradeListener = new JsrUpgradeListener(clientEndpointConfig.getConfigurator());
            upgradeRequest.addListener(jsrUpgradeListener);

            for (Extension ext : clientEndpointConfig.getExtensions())
            {
                upgradeRequest.addExtensions(new JakartaWebSocketExtensionConfig(ext));
            }

            if (clientEndpointConfig.getPreferredSubprotocols().size() > 0)
                upgradeRequest.setSubProtocols(clientEndpointConfig.getPreferredSubprotocols());
        }

        long timeout = getWebSocketCoreClient().getHttpClient().getConnectTimeout();
        try
        {
            Future<Session> sessionFuture = connect(upgradeRequest);
            if (timeout > 0)
                return sessionFuture.get(timeout + 1000, TimeUnit.MILLISECONDS);
            return sessionFuture.get();
        }
        catch (ExecutionException e)
        {
            var cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            if (cause instanceof IOException)
                throw (IOException)cause;
            throw new IOException(cause);
        }
        catch (TimeoutException e)
        {
            throw new IOException("Connection future timeout " + timeout + " ms for " + destURI, e);
        }
        catch (Throwable e)
        {
            throw new IOException("Unable to connect to " + destURI, e);
        }
    }

    @Override
    public Session connectToServer(final Class<? extends Endpoint> endpointClass, final ClientEndpointConfig providedConfig, URI path) throws DeploymentException, IOException
    {
        return connectToServer(newEndpoint(endpointClass), providedConfig, path);
    }

    @Override
    public Session connectToServer(final Class<?> annotatedEndpointClass, final URI path) throws DeploymentException, IOException
    {
        return connectToServer(newEndpoint(annotatedEndpointClass), path);
    }

    @Override
    public Session connectToServer(final Endpoint endpoint, final ClientEndpointConfig providedConfig, final URI path) throws DeploymentException, IOException
    {
        ClientEndpointConfig config = providedConfig;
        if (config == null)
            config = new BasicClientEndpointConfig();

        ConfiguredEndpoint instance = new ConfiguredEndpoint(endpoint, config);
        return connect(instance, path);
    }

    @Override
    public Session connectToServer(Object endpoint, URI path) throws DeploymentException, IOException
    {
        ClientEndpointConfig config = getAnnotatedConfig(endpoint);
        ConfiguredEndpoint instance = new ConfiguredEndpoint(endpoint, config);
        return connect(instance, path);
    }

    @Override
    public JakartaWebSocketFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public Executor getExecutor()
    {
        return getHttpClient().getExecutor();
    }

    private <T> T newEndpoint(Class<T> endpointClass) throws DeploymentException
    {
        try
        {
            return endpointClass.getConstructor().newInstance();
        }
        catch (Throwable e)
        {
            throw new DeploymentException("Unable to instantiate websocket: " + endpointClass.getName());
        }
    }

    private ClientEndpointConfig getAnnotatedConfig(Object endpoint) throws DeploymentException
    {
        ClientEndpoint anno = endpoint.getClass().getAnnotation(ClientEndpoint.class);
        if (anno == null)
            throw new DeploymentException("Could not get ClientEndpoint annotation for " + endpoint.getClass().getName());

        return new AnnotatedClientEndpointConfig(anno);
    }
}