/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * </p>
 */
public final class ChannelOutboundBuffer {
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header
    //  - 6 reference fields
    //  - 2 long fields
    //  - 2 int fields
    //  - 1 boolean field
    //  - padding
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };
    // 表示当前出站缓冲区归属channel
    private final Channel channel;

    /**
     * 1. unflushedEntry != null && flushedEntry == null 此时出战缓冲区处于 数据入站阶段
     * 2. unfluedEntry == null && flushedEntry != null 此时出站传冲去处于数据出站阶段，调用了addFlush之后，
     * 会将flushedEntry指向原unflushedEntry的值，并且计算出来一个待刷新结点的值 flushed值
     * ----------------------------------------------------------------------------
     * 3. unflushedEntry != null && flushedEntry != null 这种情况比较极端
     * 假设业务层面不停使用ctx.write,msg最终都会调用unsafe.write(msg ...) -> channelOutboundBuffer.addMessage(msg)
     * e1 -> e2 -> e3 -> e4 -> e5 ... -> eN
     * flushedEntry -> null
     * unflushedEntry -> e1
     * tailEntry -> eN
     * 业务handler接下来，调用ctx.flush()，最终会触发unsafe.flush()
     * unsafe.flush() {
     *     1. cahnnelOutboundBuffer.addFlush() 这个方法会将 flushedEntry指向 unflushedEntry的元素， flushedEntry -> e1
     *     2. channelOutboundBuffer.nioBuffers(...)这个方法会返回byteBuffer[]数组供下面逻辑使用
     *     3. 遍历byteBuffer数组，调用JDK Channel.write(buffer)，该方法会返回真正写入socket写传冲区的字节数量，结果为res
     *     4. 根据res移除出站缓冲区内的对应entry
     * }
     * socket写缓冲区 有可能会被写满，假设写到byteBuffer[3]的时候，socket写缓冲区满了...那么此时nioEventLoop再重试去写也没啥用，需要
     * 怎么办？设置多路复用器 当前ch 关注 OP_WRITE事件，当底层socket写缓冲区有空闲时，多路复用器会再次唤醒NioEventLoop线程去处理...
     *
     * 这种情况，flushedEntry -> e4
     * 业务handle再次使用ctx.write(msg)，那么unflushedEntry就指向当前msg对应的entry了
     *
     * e4 -> e5 -> .... -> eN -> eN+1
     * flushedEntry -> e4
     * unflushedEntry -> eN+1
     * tailEntry -> eN+1
     */
    // Entry(flushedEntry) --> ... Entry(unflushedEntry) --> ... Entry(tailEntry)
    // The Entry that is the first in the linked-list structure that was flushed
    // 表示待刷新结点第一个结点
    private Entry flushedEntry;
    // The Entry which is the first unflushed in the linked-list structure
    // 为未刷新的第一个结点
    private Entry unflushedEntry;
    // The Entry which represents the tail of the buffer
    // 表示尾结点
    private Entry tailEntry;
    // The number of flushed entries that are not written yet
    // 表示剩余多少entry待刷新到ch，addFlush方法会计算这个值，计算方式：从flushedEntry一直遍历到tail,计算出有多少元素
    private int flushed;

    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    // 内部采用cas方式去更新管理的totalPendingSize 字段
    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    @SuppressWarnings("UnusedDeclaration")
    // 出站缓冲区总共有多少字节量字节量，注意：包含entry自身字段占用的空间。entry->msg + entry.filed
    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    @SuppressWarnings("UnusedDeclaration")
    // 表示出站缓冲区释放可写，0表示可写，1不可写
    // 如果业务层面不检查这个状态，不受限制
    private volatile int unwritable;

    private volatile Runnable fireChannelWritabilityChangedTask;

    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written.
     */
    public void addMessage(Object msg, int size, ChannelPromise promise) {
        // newInstance
        Entry entry = Entry.newInstance(msg, size, total(msg), promise);
        // 将包装当前msg数据的entry对象 加入到entry链表中，表示数据入站到出站缓冲区
        if (tailEntry == null) {
            flushedEntry = null;
        } else {
            Entry tail = tailEntry;
            tail.next = entry;
        }
        tailEntry = entry;
        if (unflushedEntry == null) {
            unflushedEntry = entry;
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        // 累加出站缓冲区总大小
        // 1. 当前entry pendingSize
        // 2. false
        incrementPendingOutboundBytes(entry.pendingSize, false);
    }

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     */
    public void addFlush() {
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        Entry entry = unflushedEntry;
        if (entry != null) {
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                flushedEntry = entry;
            }
            do {
                flushed ++;
                if (!entry.promise.setUncancellable()) {
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    int pending = entry.cancel();
                    decrementPendingOutboundBytes(pending, false, true);
                }
                entry = entry.next;
            } while (entry != null);

            // All flushed so reset unflushedEntry
            unflushedEntry = null;
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(long size) {
        incrementPendingOutboundBytes(size, true);
    }

    private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
        if (size == 0) {
            return;
        }
        // cas更新字段，将size累加进去
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
        // 如果累加完知乎的值，打印出站缓冲区高水位，则设置unwriteable字段表示不可写，
        // 并且想channel pipeline发起unwriteable更改事件
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {
            setUnwritable(invokeLater);
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) {
        decrementPendingOutboundBytes(size, true, true);
    }

    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) {
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater);
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     */
    public Object current() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Return the current message flush progress.
     * @return {@code 0} if nothing was flushed before for the current message or there is no current message
     */
    public long currentProgress() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return 0;
        }
        return entry.progress;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     */
    public void progress(long amount) {
        Entry e = flushedEntry;
        assert e != null;
        ChannelPromise p = e.promise;
        long progress = e.progress + amount;
        e.progress = progress;
        if (p instanceof ChannelProgressivePromise) {
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     */
    public boolean remove() {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;
        // 一般是移动flushedEntry指向当前e的下一个节点，并且更新flushed字段
        removeEntry(e);

        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            // bytebuf实现了引用计数，这里safeRelease更新引用计数，最终可能会触发byteBuf归还内存逻辑
            ReferenceCountUtil.safeRelease(msg);
            safeSuccess(promise);
            // 原子减少出站缓冲区总容量，减去移除的entry.pending
            decrementPendingOutboundBytes(size, false, true);
        }

        // recycle the entry
        // 归还当前对象到对象池
        e.recycle();

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }

    private boolean remove0(Throwable cause, boolean notifyWritability) {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e);

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);

            safeFail(promise, cause);
            decrementPendingOutboundBytes(size, false, notifyWritability);
        }

        // recycle the entry
        e.recycle();

        return true;
    }

    private void removeEntry(Entry e) {
        if (-- flushed == 0) {
            // processed everything
            flushedEntry = null;
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else {
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     * 参数writtenBytes:可能是一条buffer的大小，也可能表示多条buffer的大小...或者部分大小
     */
    public void removeBytes(long writtenBytes) {
        for (;;) {
            // 获取flushedEntry结点指向的entry.msg数据
            Object msg = current();
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            final ByteBuf buf = (ByteBuf) msg;
            final int readerIndex = buf.readerIndex();
            // 计算出msg可读数据量大小
            final int readableBytes = buf.writerIndex() - readerIndex;
            // 条件如果成立，说明unsafe写入到socket底层缓冲区的数据量 > flushedEntry.msg可读数据量大小
            // if内逻辑就是移除flushedEntry代表的entry
            if (readableBytes <= writtenBytes) {
                if (writtenBytes != 0) {
                    progress(readableBytes);
                    writtenBytes -= readableBytes;
                }
                remove();
            } else { // readableBytes > writtenBytes
                if (writtenBytes != 0) {
                    buf.readerIndex(readerIndex + (int) writtenBytes);
                    progress(writtenBytes);
                }
                break;
            }
        }
        clearNioBuffers();
    }

    // Clear all ByteBuffer from the array so these can be GC'ed.
    // See https://github.com/netty/netty/issues/3837
    private void clearNioBuffers() {
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0;
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null);
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     */
    // 将缓冲区内的部分Entry.msg转换成JDK channel以来的标准对象byteBUffer,注意值了返回的byteBuffer数组
    // 1. 最多转换1024个byteBuffer对象
    // 2. nioBuffers方法最多转换maxBytes个字节的bytebuffer对象
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) {
        assert maxCount > 0;
        assert maxBytes > 0;
        // nioBuffers 方法调用一共转换了多少容量的buffer
        long nioBufferSize = 0;
        // 本次nioBuffers方法调用一共将bytebuf转换成多少byteBuffer对象
        int nioBufferCount = 0;
        // 可以简单认为是与当前线程绑定关系的一个map对象
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 这里会给每个线程分配一个长度为1024的byteBuffer数组，避免每个线程每次调用nioBuffers方法时都会创建byteBuffer数组
        // 提升了性能
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap);
        // 循环处理开始结点 flushedEntry
        Entry entry = flushedEntry;
        // 循环条件：当前结点不是 null && 当前结点不是unflushedEntry指向结点：1.循环到末尾 2.循环到unflushedEntry
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) {
            // 条件成立，说明当前entry结点非取消状态，所以需要提取它的数据
            if (!entry.cancelled) {
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();
                // 有效数据量
                final int readableBytes = buf.writerIndex() - readerIndex;
                // 条件成立：说明msg包含待发送数据...
                if (readableBytes > 0) {
                    // 条件1:nioBufferSize + readableBytes > maxBytes:
                    // 已转换buffer容量大小 + 本次可转换大小 > 最大限制
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) {
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - http://linux.die.net/man/2/writev
                        break;
                    }
                    // 正常逻辑...
                    // 更新总转换量：原值 + 本条msg大小
                    nioBufferSize += readableBytes;
                    // 默认值count是-1
                    int count = entry.count;
                    // 大概率成立
                    if (count == -1) {
                        //noinspection ConstantValueVariableUse
                        // 由多少个bytebuffer组成的，这里msg都是direct byteBuf
                        // 正常情况下，计算出来的count是1，特殊情况是CompositeByteBuf
                        entry.count = count = buf.nioBufferCount();
                    }
                    // 计算出需要多大的byteBuffer数组
                    int neededSpace = min(maxCount, nioBufferCount + count);
                    // 如果大雨>默认值1024，执行扩容逻辑
                    if (neededSpace > nioBuffers.length) {
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);
                    }
                    // 正常情况：条件成立
                    if (count == 1) {
                        ByteBuffer nioBuf = entry.buf;
                        // 条件一般会成立
                        if (nioBuf == null) {
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer
                            /**
                             * 获取byteBuf底层真正的内存对象 byteBuffer
                             * 1.读索引
                             * 2.可读数据容量
                             * {@link io.netty.buffer.PooledByteBuf#internalNioBuffer(int, int)}
                             */
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }
                        // 将刚刚转换出来的byteBuffer对象加入到数组中...
                        nioBuffers[nioBufferCount++] = nioBuf;
                    } else {
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }
                    if (nioBufferCount == maxCount) {
                        break;
                    }
                }
            }
            entry = entry.next;
        }
        // 出站缓冲区， 有多少byteBuffer待出站
        this.nioBufferCount = nioBufferCount;
        // 出站缓冲区，与欧多少字节byteBuffer待出站
        this.nioBufferSize = nioBufferSize;
        // 返回从entry链表中提取的buffer数组
        return nioBuffers;
    }

    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) {
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            nioBuffers[nioBufferCount++] = nioBuf;
        }
        return nioBufferCount;
    }

    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public int nioBufferCount() {
        return nioBufferCount;
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public long nioBufferSize() {
        return nioBufferSize;
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     */
    public boolean isWritable() {
        return unwritable == 0;
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     */
    public boolean getUserDefinedWritability(int index) {
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    private void setWritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void setUnwritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | 1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    // 广播事件
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void fireChannelWritabilityChanged(boolean invokeLater) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (invokeLater) {
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {
            pipeline.fireChannelWritabilityChanged();
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     */
    public int size() {
        return flushed;
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     */
    public boolean isEmpty() {
        return flushed == 0;
    }

    void failFlushed(Throwable cause, boolean notify) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    void close(final Throwable cause, final boolean allowChannelOpen) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen);
                }
            });
            return;
        }

        inFail = true;

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        try {
            Entry e = unflushedEntry;
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);

                if (!e.cancelled) {
                    ReferenceCountUtil.safeRelease(e.msg);
                    safeFail(e.promise, cause);
                }
                e = e.recycleAndGetNext();
            }
        } finally {
            inFail = false;
        }
        clearNioBuffers();
    }

    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    public long bytesBeforeUnwritable() {
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0;
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    public long bytesBeforeWritable() {
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes;
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        ObjectUtil.checkNotNull(processor, "processor");

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    // 它是用来存放我们的msg的
    static final class Entry {
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });
        // 归还entry到ObjectPool使用的句柄
        private final Handle<Entry> handle;
        Entry next;
        // 组装成链表使用的字段，指向下一个entry对象
        Object msg;
        // 当unsafe调用出站缓冲区，nioBuffers方法时，被涉及到的entry都会将它的msg转换成byteBuffer，这里缓存结果使用
        ByteBuffer[] bufs;
        ByteBuffer buf;
        // 业务层面关注 msg写结果 提交的promise
        ChannelPromise promise;
        // 进度
        long progress;
        // msg bytebuf有效数据量大小
        long total;
        // byteBuf 有效数据量大小 + 96(16 + 6*8 + 16 + 8 + 1)
        // Assuming a 64-bit JVM:
        //  - 16 bytes object header
        //  - 6 reference fields
        //  - 2 long fields
        //  - 2 int fields
        //  - 1 boolean field
        //  - padding
        int pendingSize;
        // 当前msg bytebuf底层由多少byteBuffer组成，一般是1，特殊情况CompositeByteBuf底层由多个bytebuf组成
        int count = -1;
        // 当前entry是否取消刷新到socket。默认是false
        boolean cancelled;

        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }

        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) {
            // 从对象池获取一个空闲的entry对象，如果对象池内没有空闲entry,则new,否则使用空闲entry
            Entry entry = RECYCLER.get();
            entry.msg = msg;
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }

        int cancel() {
            if (!cancelled) {
                cancelled = true;
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                ReferenceCountUtil.safeRelease(msg);
                msg = Unpooled.EMPTY_BUFFER;

                pendingSize = 0;
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize;
            }
            return 0;
        }

        void recycle() {
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            handle.recycle(this);
        }

        Entry recycleAndGetNext() {
            Entry next = this.next;
            recycle();
            return next;
        }
    }
}
