/*
 * Copyright (c) 2016-2020 Michael Zhang <yidongnan@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.devh.boot.grpc.client.channelfactory;

import com.google.common.collect.ImmutableMap;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ConnectivityState;
import net.devh.boot.grpc.client.config.GrpcChannelsProperties;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * 这个 Channel 工厂是 InProcessChannelFactory 和 alternative 之间的开关，
 * schema 为 in-process 的 channel 会使用 in-process-channel-factory,
 * 其他的 channel 会通过 alternative 实现处理
 * This channel factory is a switch between the {@link InProcessChannelFactory} and an alternative implementation. All
 * channels that are configured with the {@code in-process} scheme will be handled by the in-process-channel-factory,
 * the other channels will be handled by the alternative implementation.
 *
 * <p>
 * <b>The following examples show how the configured address will be mapped to an actual channel:</b>
 * </p>
 *
 * <ul>
 * <li><code>in-process:foobar</code> -&gt; will use the <code>foobar</code> in-process-channel.</li>
 * <li><code>in-process:foo/bar</code> -&gt; will use the <code>foo/bar</code> in-process-channel.</li>
 * <li><code>static://127.0.0.1</code> -&gt; will be handled by the alternative grpc channel factory.</li>
 * </ul>
 *
 * <p>
 * Using this class does not incur any additional performance or resource costs, as the actual channels (in-process or
 * other) are only created on demand.
 * </p>
 */
public class InProcessOrAlternativeChannelFactory implements GrpcChannelFactory {

    private static final String IN_PROCESS_SCHEME = "in-process";

    private final GrpcChannelsProperties properties;
    private final InProcessChannelFactory inProcessChannelFactory;
    private final GrpcChannelFactory alternativeChannelFactory;

    /**
     * 根据所给的属性创建 InProcessOrAlternativeChannelFactory
     * Creates a new InProcessOrAlternativeChannelFactory with the given properties and channel factories.
     *
     * @param properties                The properties used to resolved the target scheme
     * @param inProcessChannelFactory   The in process channel factory implementation to use.
     * @param alternativeChannelFactory The alternative channel factory implementation to use.
     */
    public InProcessOrAlternativeChannelFactory(final GrpcChannelsProperties properties,
                                                final InProcessChannelFactory inProcessChannelFactory,
                                                final GrpcChannelFactory alternativeChannelFactory) {
        this.properties = requireNonNull(properties, "properties");
        this.inProcessChannelFactory = requireNonNull(inProcessChannelFactory, "inProcessChannelFactory");
        this.alternativeChannelFactory = requireNonNull(alternativeChannelFactory, "alternativeChannelFactory");
    }

    /**
     * 根据应用名称，拦截器，是否排序创建 channel
     */
    @Override
    public Channel createChannel(final String name,
                                 final List<ClientInterceptor> interceptors,
                                 boolean sortInterceptors) {
        // 获取 channel 地址
        final URI address = this.properties.getChannel(name).getAddress();
        // 如果地址不为空，且 schema是 IN_PROCESS_SCHEME，使用 inProcessChannelFactory 创建channel， 最终调用AbstractChannelFactory 的方法
        if (address != null && IN_PROCESS_SCHEME.equals(address.getScheme())) {
            return this.inProcessChannelFactory.createChannel(address.getSchemeSpecificPart(), interceptors, sortInterceptors);
        }
        // 地址为空或 schema 不是 IN_PROCESS_SCHEME， 使用alternativeChannelFactory 创建
        return this.alternativeChannelFactory.createChannel(name, interceptors, sortInterceptors);
    }

    /**
     * 返回 channel 连接状态
     *
     * @return
     */
    @Override
    public Map<String, ConnectivityState> getConnectivityState() {
        return ImmutableMap.<String, ConnectivityState>builder()
                .putAll(inProcessChannelFactory.getConnectivityState())
                .putAll(alternativeChannelFactory.getConnectivityState())
                .build();
    }

    @Override
    public void close() {
        try {
            this.inProcessChannelFactory.close();
        } finally {
            this.alternativeChannelFactory.close();
        }
    }

}
