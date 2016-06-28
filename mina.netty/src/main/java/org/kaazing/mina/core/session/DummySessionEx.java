/**
 * Copyright 2007-2016, Kaazing Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaazing.mina.core.session;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.service.DefaultTransportMetadata;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.service.IoService;
import org.apache.mina.core.service.TransportMetadata;
import org.apache.mina.core.session.DefaultIoSessionDataStructureFactory;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionConfig;
import org.apache.mina.core.session.IoSessionDataStructureFactory;
import org.apache.mina.core.write.WriteRequest;

import org.kaazing.mina.core.buffer.IoBufferAllocatorEx;
import org.kaazing.mina.core.buffer.SimpleBufferAllocator;
import org.kaazing.mina.core.future.BindFuture;
import org.kaazing.mina.core.future.UnbindFuture;
import org.kaazing.mina.core.service.AbstractIoAcceptorEx;
import org.kaazing.mina.core.service.IoAcceptorEx;
import org.kaazing.mina.core.service.IoProcessorEx;
import org.kaazing.mina.core.service.IoServiceEx;
import org.kaazing.mina.core.write.DefaultWriteRequestEx.ShareableWriteRequest;

/**
 * This is based on Mina's DummySession. It is a dummy IoSessionEx for unit-testing or non-network-use of
 * the classes that depends on IoSessionEx.
 */
public class DummySessionEx extends AbstractIoSessionEx {

    private static final TransportMetadata TRANSPORT_METADATA =
            new DefaultTransportMetadata(
                    "mina", "dummy", false, true,
                    SocketAddress.class, IoSessionConfig.class, Object.class);

    private static final SocketAddress ANONYMOUS_ADDRESS = new SocketAddress() {
        private static final long serialVersionUID = -496112902353454179L;

        @Override
        public String toString() {
            return "?";
        }
    };

    private volatile IoServiceEx service;

    private volatile IoSessionConfigEx config = new AbstractIoSessionConfigEx() {
        @Override
        protected void doSetAll(IoSessionConfigEx config) {
            // Do nothing
        }
    };

    private final IoProcessorEx<? extends IoSessionEx> processor;

    private volatile IoHandler handler = new IoHandlerAdapter();
    private volatile SocketAddress localAddress = ANONYMOUS_ADDRESS;
    private volatile SocketAddress remoteAddress = ANONYMOUS_ADDRESS;
    private volatile TransportMetadata transportMetadata = TRANSPORT_METADATA;

    /**
     * Creates a new instance.
     */
    public DummySessionEx() {
        this(CURRENT_THREAD, IMMEDIATE_EXECUTOR);
    }

    public DummySessionEx(Thread thread, Executor executor) {
        this(thread, executor,  null);
    }

    public DummySessionEx(Thread thread, Executor executor, IoProcessorEx<? extends IoSessionEx> processor) {
        super(0, thread, executor, new ShareableWriteRequest());
        // Initialize dummy service.
        IoAcceptorEx acceptor = new AbstractIoAcceptorEx(
                new AbstractIoSessionConfigEx() {
                    @Override
                    protected void doSetAll(IoSessionConfigEx config) {
                        // Do nothing
                    }
                },
                new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        // Do nothing
                    }
                }) {

            @Override
            protected Set<SocketAddress> bindInternal(List<? extends SocketAddress> localAddresses) throws Exception {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void unbind0(List<? extends SocketAddress> localAddresses) throws Exception {
                throw new UnsupportedOperationException();
            }

            @Override
            public IoSession newSession(SocketAddress remoteAddress, SocketAddress localAddress) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TransportMetadata getTransportMetadata() {
                return TRANSPORT_METADATA;
            }

            @Override
            protected IoFuture dispose0() throws Exception {
                return null;
            }

            @Override
            protected BindFuture bindAsyncInternal(SocketAddress localAddress) {
                return null;
            }

            @Override
            public UnbindFuture unbindAsyncInternal(SocketAddress localAddress) {
                return null;
            }
        };

        // Set meaningless default values.
        acceptor.setHandler(new IoHandlerAdapter());

        service = acceptor;

        this.processor = processor != null ? processor : new IoProcessorEx<AbstractIoSessionEx>() {
            @Override
            public void add(AbstractIoSessionEx session) {
                // Do nothing
            }

            @Override
            public void flush(AbstractIoSessionEx session) {
                DummySessionEx s = (DummySessionEx) session;
                WriteRequest req = s.getWriteRequestQueue().poll(session);

                // Chek that the request is not null. If the session has been closed,
                // we may not have any pending requests.
                if (req != null) {
                    Object m = req.getMessage();
                    if (m instanceof FileRegion) {
                        FileRegion file = (FileRegion) m;
                        try {
                            file.getFileChannel().position(file.getPosition() + file.getRemainingBytes());
                            file.update(file.getRemainingBytes());
                        } catch (IOException e) {
                            s.getFilterChain().fireExceptionCaught(e);
                        }
                    }
                    getFilterChain().fireMessageSent(req);
                }
            }

            @Override
            public void remove(AbstractIoSessionEx session) {
                if (!session.getCloseFuture().isClosed()) {
                    session.getFilterChain().fireSessionClosed();
                }
            }

            @Override
            public void updateTrafficControl(AbstractIoSessionEx session) {
                // Do nothing
            }

            @Override
            public void dispose() {
                // Do nothing
            }

            @Override
            public boolean isDisposed() {
                return false;
            }

            @Override
            public boolean isDisposing() {
                return false;
            }

        };

        try {
            IoSessionDataStructureFactory factory = new DefaultIoSessionDataStructureFactory();
            setAttributeMap(factory.getAttributeMap(this));
            setWriteRequestQueue(factory.getWriteRequestQueue(this));
        } catch (Exception e) {
            throw new InternalError();
        }
    }

    @Override
    public IoBufferAllocatorEx<?> getBufferAllocator() {
        return SimpleBufferAllocator.BUFFER_ALLOCATOR;
    }

    @Override
    public IoSessionConfigEx getConfig() {
        return config;
    }

    /**
     * Sets the configuration of this session.
     */
    public void setConfig(IoSessionConfigEx config) {
        if (config == null) {
            throw new NullPointerException("config");
        }

        this.config = config;
    }

    @Override
    public IoHandler getHandler() {
        return handler;
    }

    /**
     * Sets the {@link IoHandler} which handles this session.
     */
    public void setHandler(IoHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        this.handler = handler;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Sets the socket address of local machine which is associated with
     * this session.
     */
    public void setLocalAddress(SocketAddress localAddress) {
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }

        this.localAddress = localAddress;
    }

    /**
     * Sets the socket address of remote peer.
     */
    public void setRemoteAddress(SocketAddress remoteAddress) {
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }

        this.remoteAddress = remoteAddress;
    }

    @Override
    public IoServiceEx getService() {
        return service;
    }

    /**
     * Sets the {@link IoService} which provides I/O service to this session.
     */
    public void setService(IoServiceEx service) {
        if (service == null) {
            throw new NullPointerException("service");
        }

        this.service = service;
    }

    @Override
    public final IoProcessorEx<? extends IoSessionEx> getProcessor() {
        return processor;
    }

    @Override
    public TransportMetadata getTransportMetadata() {
        return transportMetadata;
    }

    /**
     * Sets the {@link TransportMetadata} that this session runs on.
     */
    public void setTransportMetadata(TransportMetadata transportMetadata) {
        if (transportMetadata == null) {
            throw new NullPointerException("transportMetadata");
        }

        this.transportMetadata = transportMetadata;
    }

    @Override
    public void setScheduledWriteBytes(int byteCount) {
        super.setScheduledWriteBytes(byteCount);
    }


    /**
     * Update all statistical properties related with throughput.  By default
     * this method returns silently without updating the throughput properties
     * if they were calculated already within last
     * {@link IoSessionConfig#getThroughputCalculationInterval() calculation interval}.
     * If, however, <tt>force</tt> is specified as <tt>true</tt>, this method
     * updates the throughput properties immediately.
     */
    public void updateThroughput(boolean force) {
        super.updateThroughput(System.currentTimeMillis(), force);
    }
}
