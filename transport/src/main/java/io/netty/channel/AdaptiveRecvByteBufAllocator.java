/*
 * Copyright 2012 The Netty Project
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

import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.nio.AbstractNioChannel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
// 我们new 其实是它的内部类HandleImpl
    // 外层的这些东西完全是为了初始化内层 的这些东西而存在的
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    /**
     * 第一次读的时候是1024
     * 然后以后每次读都会根据预估值进行动态的改变，比如第一次读1024读满了，下次读2048，2048也读满下次可能就会读4096
     * 因为它会怀疑有很多的数据
     * {@link AbstractNioByteChannel#read()}
     * {@code allocHandl.lastBytesRead(doReadBytes(byteBuf))}
     */
    static final int DEFAULT_INITIAL = 1024;
    static final int DEFAULT_MAXIMUM = 65536;
    // 索引增量
    private static final int INDEX_INCREMENT = 4;
    // 索引减量
    private static final int INDEX_DECREMENT = 1;

    /**
     * size table
     * [16][32][64][128][....]
     * 存的是不同大小的值
     * guess()要用，会根据这个去申请bytebuffer
     */
    private static final int[] SIZE_TABLE;

    // 初始化
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        // 向size数组 添加：16 32 48 ...496
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }
        // 继续向size添加:512,1024...一直到int值溢出...成为负数
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }
        // 数组赋值
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            // 计算1024在SIZE_TABLE的下标
            index = getSizeTableIndex(initial);
            // nextReceiveBufferSize 下一次分配出来byteBuf容量的大小
            // 默认第一次是1024
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 条件成立，说明读取的数据量和评估的数据量一致，说明channel内可能还有数据没有读完
            if (bytes == attemptedBytesRead()) {
                // 想要更新 nextReceiveBufferSize 大小，因为前面评估的量被堵满了。。可能意味着ch内有很多数据，我们需要
                // 更大的容器
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        // actualReadBytes真实的数据量
        private void record(int actualReadBytes) {
            /**
             * 假设SIZE_TABLE[idx] = 512 => SIZE_TABLE[idx - 1] = 496
             * 如果本次读取的数据量 <= 496，说明ch的缓冲区数据不够多，可能不需要那么大的bytebuf
             * 如果下次读取的数据量 <= 496，说明ch的缓冲区数据不够多，不需要那么大的bytebuf
             */
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    // 获取相对减小的bytebuf值
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    // 第一次设置为true
                    decreaseNow = true;
                }
            }
            // 说明本次ch读请求已经将 bytebuf 容器已经装满了，说明ch内可能还有很多的数据。所以让index右移
            else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        // 1.64
        // 2.1024
        // 3.65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }
        // 使用二分查找算法 获取mininum size在数组内的下标(ps: SIZE_TABLE[下标] <= mininum值的)
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            // 确保 SIZE_TABLE[minIndex] >= minium
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        // 1.64在SIZE_TALBE中的下标
        // 2.65536在SIZE_TABLE中的下标
        // 3.1024
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
