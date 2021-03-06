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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            // baseGroup是ServerChannel使用
            // workerGroup是由Server产生的客户端channel使用
            b.group(bossGroup, workerGroup)
                    // 设置服务端channel类
                    /**
                     * 里面绑定了一个 {@link io.netty.channel.ReflectiveChannelFactory}
                     */
             .channel(NioServerSocketChannel.class)
                    // 保存server端的信息
             .option(ChannelOption.SO_BACKLOG, 100)
                    // 配置server端的pipeline处理器。后续创建出NioServerChannel后，会把handler加入到pipeline中
             .handler(new LoggingHandler(LogLevel.INFO))
                    // 配置服务端上连接进来的客户端
                    /**
                     * ChannelInitializer它不是一个handler，只是通过适配器实现了Hanler接口
                     * 它存在的意义就是为了延迟初始化pipeline,当pipeline上的channel激活以后。真正添加handler才执行
                     * 添加之后我们的pipeline长这个样子：head <--> ChannelInitializer <--> tail
                     * 后面合适的时候这个"zip" ChannelInitializer 会将添加的handler解压出来添加到pipeline中，并且将自己进行移除
                     */
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         // 看一下addLast
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });
            // 前面相当于都是给bootStrap设置一些配置，包括了abstractBootStrap和ServerBootStrap

            /**
             *        Start the server.
             *              点进去bind方法，查看server流程
             *              bind返回的是channelPromise
             * {@link io.netty.channel.ChannelPromise}
             *
             */
            ChannelFuture f = b.bind(PORT) // 与绑定相关的promise对象
                    /**
                     * {@link DefaultChannelPromise#sync()}
                     */
                    .sync();

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
