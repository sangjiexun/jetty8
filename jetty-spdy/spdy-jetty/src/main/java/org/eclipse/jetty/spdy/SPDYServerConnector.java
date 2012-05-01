/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ThreadPool;

public class SPDYServerConnector extends SelectChannelConnector
{
    private static final Logger logger = Log.getLogger(SPDYServerConnector.class);

    // Order is important on server side, so we use a LinkedHashMap
    private final Map<String, AsyncConnectionFactory> factories = new LinkedHashMap<>();
    private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
    private final ByteBufferPool bufferPool = new StandardByteBufferPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ServerSessionFrameListener listener;
    private final SslContextFactory sslContextFactory;
    private AsyncConnectionFactory defaultConnectionFactory;

    public SPDYServerConnector(ServerSessionFrameListener listener)
    {
        this(listener, null);
    }

    public SPDYServerConnector(ServerSessionFrameListener listener, SslContextFactory sslContextFactory)
    {
        this.listener = listener;
        this.sslContextFactory = sslContextFactory;
        if (sslContextFactory != null)
            addBean(sslContextFactory);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public Executor getExecutor()
    {
        final ThreadPool threadPool = getThreadPool();
        if (threadPool instanceof Executor)
            return (Executor)threadPool;
        return new Executor()
        {
            @Override
            public void execute(Runnable command)
            {
                threadPool.dispatch(command);
            }
        };
    }

    public ScheduledExecutorService getScheduler()
    {
        return scheduler;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        defaultConnectionFactory = new ServerSPDYAsyncConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), scheduler, listener);
        putAsyncConnectionFactory("spdy/2", defaultConnectionFactory);
        logger.info("SPDY support is experimental. Please report feedback at jetty-dev@eclipse.org");
    }

    @Override
    protected void doStop() throws Exception
    {
        closeSessions();
        scheduler.shutdown();
        super.doStop();
    }

    @Override
    public void join() throws InterruptedException
    {
        scheduler.awaitTermination(0, TimeUnit.MILLISECONDS);
        super.join();
    }

    public AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
    {
        synchronized (factories)
        {
            return factories.get(protocol);
        }
    }

    public AsyncConnectionFactory putAsyncConnectionFactory(String protocol, AsyncConnectionFactory factory)
    {
        synchronized (factories)
        {
            return factories.put(protocol, factory);
        }
    }

    public AsyncConnectionFactory removeAsyncConnectionFactory(String protocol)
    {
        synchronized (factories)
        {
            return factories.remove(protocol);
        }
    }

    public Map<String, AsyncConnectionFactory> getAsyncConnectionFactories()
    {
        synchronized (factories)
        {
            return new LinkedHashMap<>(factories);
        }
    }

    protected List<String> provideProtocols()
    {
        synchronized (factories)
        {
            return new ArrayList<>(factories.keySet());
        }
    }

    protected AsyncConnectionFactory getDefaultAsyncConnectionFactory()
    {
        return defaultConnectionFactory;
    }

    @Override
    protected AsyncConnection newConnection(final SocketChannel channel, AsyncEndPoint endPoint)
    {
        if (sslContextFactory != null)
        {
            SSLEngine engine = newSSLEngine(sslContextFactory, channel);
            final AtomicReference<AsyncEndPoint> sslEndPointRef = new AtomicReference<>();
            SslConnection sslConnection = new SslConnection(engine, endPoint)
            {
                @Override
                public void onClose()
                {
                    sslEndPointRef.set(null);
                    super.onClose();
                }
            };
            endPoint.setConnection(sslConnection);
            AsyncEndPoint sslEndPoint = sslConnection.getSslEndPoint();
            sslEndPointRef.set(sslEndPoint);

            // Instances of the ServerProvider inner class strong reference the
            // SslEndPoint (via lexical scoping), which strong references the SSLEngine.
            // Since NextProtoNego stores in a WeakHashMap the SSLEngine as key
            // and this instance as value, we are in the situation where the value
            // of a WeakHashMap refers indirectly to the key, which is bad because
            // the entry will never be removed from the WeakHashMap.
            // We use AtomicReferences to be captured via lexical scoping,
            // and we null them out above when the connection is closed.
            NextProtoNego.put(engine, new NextProtoNego.ServerProvider()
            {
                @Override
                public void unsupported()
                {
                    AsyncConnectionFactory connectionFactory = getDefaultAsyncConnectionFactory();
                    AsyncEndPoint sslEndPoint = sslEndPointRef.get();
                    AsyncConnection connection = connectionFactory.newAsyncConnection(channel, sslEndPoint, SPDYServerConnector.this);
                    sslEndPoint.setConnection(connection);
                }

                @Override
                public List<String> protocols()
                {
                    return provideProtocols();
                }

                @Override
                public void protocolSelected(String protocol)
                {
                    AsyncConnectionFactory connectionFactory = getAsyncConnectionFactory(protocol);
                    AsyncEndPoint sslEndPoint = sslEndPointRef.get();
                    AsyncConnection connection = connectionFactory.newAsyncConnection(channel, sslEndPoint, SPDYServerConnector.this);
                    sslEndPoint.setConnection(connection);
                }
            });

            AsyncConnection connection = new EmptyAsyncConnection(sslEndPoint);
            sslEndPoint.setConnection(connection);

            startHandshake(engine);

            return sslConnection;
        }
        else
        {
            AsyncConnectionFactory connectionFactory = getDefaultAsyncConnectionFactory();
            AsyncConnection connection = connectionFactory.newAsyncConnection(channel, endPoint, this);
            endPoint.setConnection(connection);
            return connection;
        }
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
        engine.setUseClientMode(false);
        return engine;
    }

    private void startHandshake(SSLEngine engine)
    {
        try
        {
            engine.beginHandshake();
        }
        catch (SSLException x)
        {
            throw new RuntimeException(x);
        }
    }

    protected boolean sessionOpened(Session session)
    {
        // Add sessions only if the connector is not stopping
        return isRunning() && sessions.offer(session);
    }

    protected boolean sessionClosed(Session session)
    {
        // Remove sessions only if the connector is not stopping
        // to avoid concurrent removes during iterations
        return isRunning() && sessions.remove(session);
    }

    private void closeSessions()
    {
        for (Session session : sessions)
            session.goAway();
        sessions.clear();
    }

    protected Collection<Session> getSessions()
    {
        return Collections.unmodifiableCollection(sessions);
    }
}
