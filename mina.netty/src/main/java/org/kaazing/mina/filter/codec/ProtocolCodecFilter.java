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
/** The copyright above pertains to portions created by Kaazing */

/* Differences from class of same name in Mina 2.0.0-RC1 include:
 * 1. Use IoSessionEx.BUFFER_ALLOCATOR instead of calling IoBuffer.allocate
 * 2. Use non-static attribute keys
 * 3. Make getDecoder protected instead of private
 */
package org.kaazing.mina.filter.codec;

import java.util.Queue;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.filter.codec.AbstractProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderException;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;
import org.apache.mina.filter.codec.RecoverableProtocolDecoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kaazing.mina.core.session.IoSessionEx;
import org.kaazing.mina.core.write.WriteRequestEx;

import static java.lang.String.format;

/**
 * An {@link IoFilter} which translates binary or protocol specific data into
 * message objects and vice versa using {@link ProtocolCodecFactory},
 * {@link ProtocolEncoder}, or {@link ProtocolDecoder}.
 * @org.apache.xbean.XBean
 */
public class ProtocolCodecFilter extends IoFilterAdapter {
    /** A logger for this class */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolCodecFilter.class);

    private static final Class<?>[] EMPTY_PARAMS = new Class[0];

    // Note: requires non-static attribute keys to support multiple codec filters on the same filter chain
    private final AttributeKey ENCODER = new AttributeKey(ProtocolCodecFilter.class, "encoder");
    private final AttributeKey DECODER = new AttributeKey(ProtocolCodecFilter.class, "decoder");
    private final AttributeKey DECODER_OUT = new AttributeKey(ProtocolCodecFilter.class, "decoderOut");
    private final AttributeKey ENCODER_OUT = new AttributeKey(ProtocolCodecFilter.class, "encoderOut");

    /** The factory responsible for creating the encoder and decoder */
    private final ProtocolCodecFactory factory;

    /**
     *
     * Creates a new instance of ProtocolCodecFilter, associating a factory
     * for the creation of the encoder and decoder.
     *
     * @param factory The associated factory
     */
    public ProtocolCodecFilter(ProtocolCodecFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        this.factory = factory;
    }

    /**
     * Creates a new instance of ProtocolCodecFilter, without any factory.
     * The encoder/decoder factory will be created as an inner class, using
     * the two parameters (encoder and decoder).
     *
     * @param encoder The class responsible for encoding the message
     * @param decoder The class responsible for decoding the message
     */
    public ProtocolCodecFilter(final ProtocolEncoder encoder,
            final ProtocolDecoder decoder) {
        if (encoder == null) {
            throw new NullPointerException("encoder");
        }
        if (decoder == null) {
            throw new NullPointerException("decoder");
        }

        // Create the inner Factory based on the two parameters
        this.factory = new ProtocolCodecFactory() {
            @Override
            public ProtocolEncoder getEncoder(IoSession session) {
                return encoder;
            }

            @Override
            public ProtocolDecoder getDecoder(IoSession session) {
                return decoder;
            }
        };
    }

    /**
     * Creates a new instance of ProtocolCodecFilter, without any factory.
     * The encoder/decoder factory will be created as an inner class, using
     * the two parameters (encoder and decoder), which are class names. Instances
     * for those classes will be created in this constructor.
     *
     * @param encoder The class responsible for encoding the message
     * @param decoder The class responsible for decoding the message
     */
    public ProtocolCodecFilter(
            final Class<? extends ProtocolEncoder> encoderClass,
            final Class<? extends ProtocolDecoder> decoderClass) {
        if (encoderClass == null) {
            throw new NullPointerException("encoderClass");
        }
        if (decoderClass == null) {
            throw new NullPointerException("decoderClass");
        }
        if (!ProtocolEncoder.class.isAssignableFrom(encoderClass)) {
            throw new IllegalArgumentException("encoderClass: "
                    + encoderClass.getName());
        }
        if (!ProtocolDecoder.class.isAssignableFrom(decoderClass)) {
            throw new IllegalArgumentException("decoderClass: "
                    + decoderClass.getName());
        }
        try {
            encoderClass.getConstructor(EMPTY_PARAMS);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "encoderClass doesn't have a public default constructor.");
        }
        try {
            decoderClass.getConstructor(EMPTY_PARAMS);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "decoderClass doesn't have a public default constructor.");
        }

        // Create the inner factory based on the two parameters. We instantiate
        // the encoder and decoder locally.
        this.factory = new ProtocolCodecFactory() {
            @Override
            public ProtocolEncoder getEncoder(IoSession session) throws Exception {
                return encoderClass.newInstance();
            }

            @Override
            public ProtocolDecoder getDecoder(IoSession session) throws Exception {
                return decoderClass.newInstance();
            }
        };
    }

    /**
     * Get the encoder instance from a given session.
     *
     * @param session The associated session we will get the encoder from
     * @return The encoder instance, if any
     */
    public ProtocolEncoder getEncoder(IoSession session) {
        return (ProtocolEncoder) session.getAttribute(ENCODER);
    }

    @Override
    public void onPreAdd(IoFilterChain parent, String name,
            NextFilter nextFilter) throws Exception {
        if (parent.contains(this)) {
            throw new IllegalArgumentException(
                    "You can't add the same filter instance more than once.  Create another instance and add it.");
        }

        // Initialize the encoder and decoder
        initCodec(parent.getSession());
    }

    @Override
    public void onPostRemove(IoFilterChain parent, String name,
            NextFilter nextFilter) throws Exception {
        // Clean everything
        disposeCodec(parent.getSession());
    }

    /**
     * Process the incoming message, calling the session decoder. As the incoming
     * buffer might contains more than one messages, we have to loop until the decoder
     * throws an exception.
     *
     *  while ( buffer not empty )
     *    try
     *      decode ( buffer )
     *    catch
     *      break;
     *
     */
    @Override
    public void messageReceived(NextFilter nextFilter, IoSession session,
            Object message) throws Exception {
        LOGGER.debug("Processing a MESSAGE_RECEIVED for session {}", session.getId());

        if (!(message instanceof IoBuffer)) {
            nextFilter.messageReceived(session, message);
            return;
        }

        IoBuffer in = (IoBuffer) message;
        ProtocolDecoder decoder = getDecoder(session);
        ProtocolDecoderOutput decoderOut = getDecoderOut(session, nextFilter);
        IoSessionEx sessionEx = (IoSessionEx)session;

        Thread ioThread = sessionEx.getIoThread();

        // Loop until we don't have anymore byte in the buffer,
        // or until the decoder throws an unrecoverable exception or
        // can't decoder a message, because there are not enough
        // data in the buffer
        // Note: break out to let old I/O thread unwind after deregistering
        while (in.hasRemaining()) {
            // If the session is realigned, let the new thread deal with the decoding
            if (sessionEx.getIoThread() != ioThread) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace(format("Decoding for session=%s will be continued by new thread=%s old thread=%s",
                            session, sessionEx.getIoThread(), ioThread));
                }
                break;
            }
            int oldPos = in.position();
            try {
                synchronized (decoderOut) {
                    // Call the decoder with the read bytes
                    decoder.decode(session, in, decoderOut);

                    // Finish decoding if no exception was thrown.
                    decoderOut.flush(nextFilter, session);
                }
            } catch (Throwable t) {
                ProtocolDecoderException pde;
                if (t instanceof ProtocolDecoderException) {
                    pde = (ProtocolDecoderException) t;
                } else {
                    pde = new ProtocolDecoderException(t);
                }

                if (pde.getHexdump() == null) {
                    // Generate a message hex dump
                    int curPos = in.position();
                    in.position(oldPos);
                    pde.setHexdump(in.getHexDump());
                    in.position(curPos);
                }

                // Fire the exceptionCaught event.
                synchronized (decoderOut) {
                    decoderOut.flush(nextFilter, session);
                }
                nextFilter.exceptionCaught(session, pde);

                // Retry only if the type of the caught exception is
                // recoverable and the buffer position has changed.
                // We check buffer position additionally to prevent an
                // infinite loop.
                if (!(t instanceof RecoverableProtocolDecoderException) ||
                        (in.position() == oldPos)) {
                    break;
                }
            }
        }
    }

    @Override
    public void messageSent(NextFilter nextFilter, IoSession session,
            WriteRequest writeRequest) throws Exception {

        if (writeRequest == IoSessionEx.REGISTERED_EVENT) {
            ProtocolDecoderOutput decoderOut = getDecoderOut(session, nextFilter);
            // synchronizing so that realigned thread wait until original thread unwinds
            synchronized (decoderOut) {
                decoderOut.flush(nextFilter, session);
            }
        }

        nextFilter.messageSent(session, writeRequest);
    }

    @Override
    public void filterWrite(NextFilter nextFilter, IoSession session,
            WriteRequest writeRequest) throws Exception {
        Object message = writeRequest.getMessage();

        // Bypass the encoding if the message is contained in a IoBuffer,
        // as it has already been encoded before
        if (message instanceof IoBuffer || message instanceof FileRegion) {
            nextFilter.filterWrite(session, writeRequest);
            return;
        }

        // Get the encoder in the session
        ProtocolEncoder encoder = getEncoder(session);

        ProtocolEncoderOutput encoderOut = getEncoderOut(session,
                nextFilter, writeRequest);

        try {
            // Now we can try to encode the response
            encoder.encode(session, message, encoderOut);

            // Flush the encoded message (assumes single encoded message)
            ((ProtocolEncoderOutputImpl) encoderOut).flushWithFuture(nextFilter, session, writeRequest);

        } catch (Throwable t) {
            ProtocolEncoderException pee;

            // Generate the correct exception
            if (t instanceof ProtocolEncoderException) {
                pee = (ProtocolEncoderException) t;
            } else {
                pee = new ProtocolEncoderException(t);
            }

            throw pee;
        }
    }

    @Override
    public void sessionClosed(NextFilter nextFilter, IoSession session)
            throws Exception {
        // Call finishDecode() first when a connection is closed.
        ProtocolDecoder decoder = getDecoder(session);
        ProtocolDecoderOutput decoderOut = getDecoderOut(session, nextFilter);

        try {
            decoder.finishDecode(session, decoderOut);
        } catch (Throwable t) {
            ProtocolDecoderException pde;
            if (t instanceof ProtocolDecoderException) {
                pde = (ProtocolDecoderException) t;
            } else {
                pde = new ProtocolDecoderException(t);
            }
            throw pde;
        } finally {
            // Dispose everything
            disposeCodec(session);
            decoderOut.flush(nextFilter, session);
        }

        // Call the next filter
        nextFilter.sessionClosed(session);
    }

    private static class ProtocolDecoderOutputImpl extends
            AbstractProtocolDecoderOutput {
        public ProtocolDecoderOutputImpl() {
            // Do nothing
        }

        @Override
        public void flush(NextFilter nextFilter, IoSession session) {
            IoSessionEx sessionEx = (IoSessionEx) session;
            if (!sessionEx.isIoRegistered()) {
                return;
            }

            Thread ioThread = sessionEx.getIoThread();
            Queue<Object> messageQueue = getMessageQueue();
            while (!messageQueue.isEmpty()) {
                // If the session is realigned, let the new thread deal with it
                if (sessionEx.getIoThread() != ioThread) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace(format("Decoding for session=%s will be continued by new thread=%s old thread=%s",
                                session, sessionEx.getIoThread(), ioThread));
                    }
                    break;
                }
                nextFilter.messageReceived(session, messageQueue.poll());
            }
        }
    }

    private static class ProtocolEncoderOutputImpl implements ProtocolEncoderOutput {

        private Object encodedMessage;

        @Override
        public void write(Object encodedMessage) {
            if (this.encodedMessage != null) {
                throw new IllegalStateException("called write() multiple times from ProtocolEncoder.encode()");
            }
            this.encodedMessage = encodedMessage;
        }

        @Override
        public void mergeAll() {
            // no-op
        }

        @Override
        public WriteFuture flush() {
            throw new UnsupportedOperationException();
        }

        public void flushWithFuture(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) {
            Object encodedMessage = this.encodedMessage;
            if (encodedMessage != null) {
                this.encodedMessage = null;
                WriteRequestEx writeRequestEx = (WriteRequestEx) writeRequest;
                writeRequestEx.setMessage(encodedMessage);
                nextFilter.filterWrite(session, writeRequestEx);
            }
            else {
                WriteFuture future = writeRequest.getFuture();
                future.setWritten();
            }
        }
    }

    //----------- Helper methods ---------------------------------------------
    /**
     * Initialize the encoder and the decoder, storing them in the
     * session attributes.
     */
    private void initCodec(IoSession session) throws Exception {
        // Creates the decoder and stores it into the newly created session
        ProtocolDecoder decoder = factory.getDecoder(session);
        session.setAttribute(DECODER, decoder);

        // Creates the encoder and stores it into the newly created session
        ProtocolEncoder encoder = factory.getEncoder(session);
        session.setAttribute(ENCODER, encoder);
    }

    /**
     * Dispose the encoder, decoder, and the callback for the decoded
     * messages.
     */
    private void disposeCodec(IoSession session) {
        // We just remove the two instances of encoder/decoder to release resources
        // from the session
        disposeEncoder(session);
        disposeDecoder(session);

        // We also remove the callback
        disposeDecoderOut(session);
    }

    /**
     * Dispose the encoder, removing its instance from the
     * session's attributes, and calling the associated
     * dispose method.
     */
    private void disposeEncoder(IoSession session) {
        ProtocolEncoder encoder = (ProtocolEncoder) session
                .removeAttribute(ENCODER);
        if (encoder == null) {
            return;
        }

        try {
            encoder.dispose(session);
        } catch (Throwable t) {
            LOGGER.warn(
                    "Failed to dispose: " + encoder.getClass().getName() + " (" + encoder + ')');
        }
    }

    /**
     * Get the decoder instance from a given session.
     *
     * @param session The associated session we will get the decoder from
     * @return The decoder instance
     */
    protected ProtocolDecoder getDecoder(IoSession session) {
        return (ProtocolDecoder) session.getAttribute(DECODER);
    }

    /**
     * Dispose the decoder, removing its instance from the
     * session's attributes, and calling the associated
     * dispose method.
     */
    private void disposeDecoder(IoSession session) {
        ProtocolDecoder decoder = (ProtocolDecoder) session
                .removeAttribute(DECODER);
        if (decoder == null) {
            return;
        }

        try {
            decoder.dispose(session);
        } catch (Throwable t) {
            LOGGER.warn(
                    "Failed to dispose: " + decoder.getClass().getName() + " (" + decoder + ')');
        }
    }

    /**
     * Return a reference to the decoder callback. If it's not already created
     * and stored into the session, we create a new instance.
     */
    private ProtocolDecoderOutput getDecoderOut(IoSession session,
            NextFilter nextFilter) {
        ProtocolDecoderOutput out = (ProtocolDecoderOutput) session.getAttribute(DECODER_OUT);

        if (out == null) {
            // Create a new instance, and stores it into the session
            out = new ProtocolDecoderOutputImpl();
            session.setAttribute(DECODER_OUT, out);
        }

        return out;
    }

    private ProtocolEncoderOutput getEncoderOut(IoSession session,
        NextFilter nextFilter, WriteRequest writeRequest) {
        ProtocolEncoderOutput out = (ProtocolEncoderOutput) session.getAttribute(ENCODER_OUT);

        if (out == null) {
            // Create a new instance, and stores it into the session
            out = new ProtocolEncoderOutputImpl();
            session.setAttribute(ENCODER_OUT, out);
        }

        return out;
    }

    /**
     * Remove the decoder callback from the session's attributes.
     */
    private void disposeDecoderOut(IoSession session) {
        session.removeAttribute(DECODER_OUT);
    }
}
