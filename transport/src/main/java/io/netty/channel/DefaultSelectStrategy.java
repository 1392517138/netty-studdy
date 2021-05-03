/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    /**
     *
     * @param selectSupplier The supplier with the result of a select result.
     *     {@code private final IntSupplier selectNowSupplier = new IntSupplier() {
     *         @Override
     *         public int get() throws Exception {
     *             return selectNow(); // 调用多路复用器的selectNow方法，该方法不阻塞线程，直接返回
     *         }
     *     };}
     * @param hasTasks true if tasks are waiting to be processed.
     * @return
     * @throws Exception
     */
    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        // 如果有任务，调用selectSupplier.get()
        // 如果没有任务就返回 SELECT 这个常量，是-1
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
