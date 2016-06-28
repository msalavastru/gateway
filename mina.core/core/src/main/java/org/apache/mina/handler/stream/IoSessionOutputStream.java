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
package org.apache.mina.handler.stream;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;

/**
 * An {@link OutputStream} that forwards all write operations to
 * the associated {@link IoSession}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
class IoSessionOutputStream extends OutputStream {
    private final IoSession session;

    private WriteFuture lastWriteFuture;

    public IoSessionOutputStream(IoSession session) {
        this.session = session;
    }

    @Override
    public void close() throws IOException {
        try {
            flush();
        } finally {
            session.close(true).awaitUninterruptibly();
        }
    }

    private void checkClosed() throws IOException {
        if (!session.isConnected()) {
            throw new IOException("The session has been closed.");
        }
    }

    private synchronized void write(IoBuffer buf) throws IOException {
        checkClosed();
        WriteFuture future = session.write(buf);
        lastWriteFuture = future;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        write(IoBuffer.wrap(b.clone(), off, len));
    }

    @Override
    public void write(int b) throws IOException {
        IoBuffer buf = IoBuffer.allocate(1);
        buf.put((byte) b);
        buf.flip();
        write(buf);
    }

    @Override
    public synchronized void flush() throws IOException {
        if (lastWriteFuture == null) {
            return;
        }

        lastWriteFuture.awaitUninterruptibly();
        if (!lastWriteFuture.isWritten()) {
            throw new IOException(
                    "The bytes could not be written to the session");
        }
    }
}
