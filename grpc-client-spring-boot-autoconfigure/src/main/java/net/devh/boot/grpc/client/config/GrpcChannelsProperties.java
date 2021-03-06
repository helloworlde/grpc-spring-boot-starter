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

package net.devh.boot.grpc.client.config;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel的属性，每个channel都有自己的属性配置
 * <p>
 * A container for named channel properties. Each channel has its own configuration. If you try to get a channel that
 * does not have a configuration yet, it will be created. If something is not configured in the channel properties, it
 * will be copied from the global config during the first retrieval. If some property is configured in neither the
 * channel properties nor the global properties then a default value will be used.
 *
 * @author Michael (yidongnan@gmail.com)
 * @author Daniel Theuke (daniel.theuke@heuboe.de)
 * @since 5/17/16
 */
@ToString
@EqualsAndHashCode
@ConfigurationProperties("grpc")
public class GrpcChannelsProperties {

    /**
     * The key that will be used for the {@code GLOBAL} properties.
     */
    public static final String GLOBAL_PROPERTIES_KEY = "GLOBAL";

    private final Map<String, GrpcChannelProperties> client = new ConcurrentHashMap<>();

    /**
     * Gets the configuration mapping for each client.
     *
     * @return The client configuration mappings.
     */
    public final Map<String, GrpcChannelProperties> getClient() {
        return this.client;
    }

    /**
     * 根据服务名称获取 Channel 配置，如果不存在，则会创建新的，没有设置属性的值将会使用全局配置
     * Gets the properties for the given channel. If the properties for the specified channel name do not yet exist,
     * they are created automatically. Before the instance is returned, the unset values are filled with values from the
     * global properties.
     *
     * @param name The name of the channel to get the properties for.
     * @return The properties for the given channel name.
     */
    public GrpcChannelProperties getChannel(final String name) {
        final GrpcChannelProperties properties = getRawChannel(name);
        properties.copyDefaultsFrom(getGlobalChannel());
        return properties;
    }

    /**
     * 获取全局的 Channel 配置，如果 channel没有单独配置，则使用全局配置，如果全局配置也没有则使用默认配置
     * Gets the global channel properties. Global properties are used, if the channel properties don't overwrite them.
     * If neither the global nor the per client properties are set then default values will be used.
     *
     * @return The global channel properties.
     */
    public final GrpcChannelProperties getGlobalChannel() {
        // This cannot be moved to its own field,
        // as Spring replaces the instance in the map and inconsistencies would occur.
        return getRawChannel(GLOBAL_PROPERTIES_KEY);
    }

    /**
     * 根据名称获取 Channel，如果不存在则创建新的
     * Gets or creates the channel properties for the given client.
     *
     * @param name The name of the channel to get the properties for.
     * @return The properties for the given channel name.
     */
    private GrpcChannelProperties getRawChannel(final String name) {
        return this.client.computeIfAbsent(name, key -> new GrpcChannelProperties());
    }

    private String defaultScheme;

    /**
     * Get the default scheme that should be used, if the client doesn't specify a scheme/address.
     *
     * @return The default scheme to use or null.
     * @see #setDefaultScheme(String)
     */
    public String getDefaultScheme() {
        return this.defaultScheme;
    }

    /**
     * Sets the default scheme to use, if the client doesn't specify a scheme/address. If not specified it will default
     * to the default scheme of the {@link io.grpc.NameResolver.Factory}. Examples: {@code dns}, {@code discovery}.
     *
     * @param defaultScheme The default scheme to use or null.
     */
    public void setDefaultScheme(String defaultScheme) {
        this.defaultScheme = defaultScheme;
    }

}
