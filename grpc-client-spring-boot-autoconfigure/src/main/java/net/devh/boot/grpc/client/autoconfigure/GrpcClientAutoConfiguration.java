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

package net.devh.boot.grpc.client.autoconfigure;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import net.devh.boot.grpc.client.channelfactory.InProcessChannelFactory;
import net.devh.boot.grpc.client.channelfactory.InProcessOrAlternativeChannelFactory;
import net.devh.boot.grpc.client.channelfactory.NettyChannelFactory;
import net.devh.boot.grpc.client.channelfactory.ShadedNettyChannelFactory;
import net.devh.boot.grpc.client.config.GrpcChannelsProperties;
import net.devh.boot.grpc.client.inject.GrpcClientBeanPostProcessor;
import net.devh.boot.grpc.client.interceptor.AnnotationGlobalClientInterceptorConfigurer;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorRegistry;
import net.devh.boot.grpc.client.nameresolver.NameResolverRegistration;
import net.devh.boot.grpc.common.autoconfigure.GrpcCommonCodecAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.Collections;
import java.util.List;


/**
 * 初始化 gRPC 的 Bean
 * The auto configuration used by Spring-Boot that contains all beans to create and inject grpc clients into beans.
 *
 * @author Michael (yidongnan@gmail.com)
 * @since 5/17/16
 */
@Configuration
@EnableConfigurationProperties
@AutoConfigureAfter(name = "org.springframework.cloud.client.CommonsClientAutoConfiguration",
        value = GrpcCommonCodecAutoConfiguration.class)
public class GrpcClientAutoConfiguration {

    /**
     * Bean 初始化后注入 Stub属性
     *
     * @param applicationContext
     * @return
     */
    @Bean
    static GrpcClientBeanPostProcessor grpcClientBeanPostProcessor(final ApplicationContext applicationContext) {
        return new GrpcClientBeanPostProcessor(applicationContext);
    }


    /**
     * Channel 属性配置
     *
     * @return
     */
    @ConditionalOnMissingBean
    @Bean
    GrpcChannelsProperties grpcChannelsProperties() {
        return new GrpcChannelsProperties();
    }

    /**
     * 全局的 Client 拦截器注册
     *
     * @return
     */
    @ConditionalOnMissingBean
    @Bean
    GlobalClientInterceptorRegistry globalClientInterceptorRegistry() {
        return new GlobalClientInterceptorRegistry();
    }

    /**
     * 查找 GrpcGlobalClientInterceptor 修饰的拦截器并加入到拦截器配置中
     *
     * @return
     */
    @Bean
    AnnotationGlobalClientInterceptorConfigurer annotationGlobalClientInterceptorConfigurer() {
        return new AnnotationGlobalClientInterceptorConfigurer();
    }

    /**
     * 创建新的 NameResolverRegistration，确保 NameResolverProvider 在 Spring 关闭的时候可以关闭
     * Creates a new NameResolverRegistration. This ensures that the NameResolverProvider's get unregistered when spring
     * shuts down. This is mostly required for tests/when running multiple application contexts within the same JVM.
     *
     * @param nameResolverProviders The spring managed providers to manage.
     * @return The newly created NameResolverRegistration bean.
     */
    @ConditionalOnMissingBean
    @Lazy
    @Bean
    NameResolverRegistration grpcNameResolverRegistration(@Autowired(required = false) final List<NameResolverProvider> nameResolverProviders) {
        NameResolverRegistration nameResolverRegistration = new NameResolverRegistration(nameResolverProviders);
        nameResolverRegistration.register(NameResolverRegistry.getDefaultRegistry());
        return nameResolverRegistration;
    }

    /**
     * ManagedChannelBuilder 的配置，GrpcChannelFactory 创建定制的channel
     *
     * @param registry
     * @return
     */
    @ConditionalOnBean(CompressorRegistry.class)
    @Bean
    GrpcChannelConfigurer compressionChannelConfigurer(final CompressorRegistry registry) {
        return (builder, name) -> builder.compressorRegistry(registry);
    }

    @ConditionalOnBean(DecompressorRegistry.class)
    @Bean
    GrpcChannelConfigurer decompressionChannelConfigurer(final DecompressorRegistry registry) {
        return (builder, name) -> builder.decompressorRegistry(registry);
    }

    /**
     * 默认channel 配置
     *
     * @return
     */
    @ConditionalOnMissingBean(GrpcChannelConfigurer.class)
    @Bean
    List<GrpcChannelConfigurer> defaultChannelConfigurers() {
        return Collections.emptyList();
    }

    /**
     * 尝试创建 Shaded Netty
     *
     * @param properties
     * @param globalClientInterceptorRegistry
     * @param channelConfigurers
     * @return
     */
    // First try the shaded netty channel factory
    @ConditionalOnMissingBean(GrpcChannelFactory.class)
    @ConditionalOnClass(name = {"io.grpc.netty.shaded.io.netty.channel.Channel", "io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder"})
    @Bean
    @Lazy
    GrpcChannelFactory shadedNettyGrpcChannelFactory(final GrpcChannelsProperties properties,
                                                     final GlobalClientInterceptorRegistry globalClientInterceptorRegistry,
                                                     final List<GrpcChannelConfigurer> channelConfigurers) {
        // alternativeChannelFactory
        final ShadedNettyChannelFactory channelFactory = new ShadedNettyChannelFactory(properties, globalClientInterceptorRegistry, channelConfigurers);
        final InProcessChannelFactory inProcessChannelFactory = new InProcessChannelFactory(properties, globalClientInterceptorRegistry, channelConfigurers);
        return new InProcessOrAlternativeChannelFactory(properties, inProcessChannelFactory, channelFactory);
    }

    /**
     * 如果服务地址的 Schema 是 in-progress，则使用 InProcessChannelFactory 创建，否则使用 NettyChannelFactory 创建
     *
     * @param properties
     * @param globalClientInterceptorRegistry
     * @param channelConfigurers
     * @return
     */
    // Then try the normal netty channel factory
    @ConditionalOnMissingBean(GrpcChannelFactory.class)
    @ConditionalOnClass(name = {"io.netty.channel.Channel", "io.grpc.netty.NettyChannelBuilder"})
    @Bean
    @Lazy
    GrpcChannelFactory nettyGrpcChannelFactory(final GrpcChannelsProperties properties,
                                               final GlobalClientInterceptorRegistry globalClientInterceptorRegistry,
                                               final List<GrpcChannelConfigurer> channelConfigurers) {
        final NettyChannelFactory channelFactory = new NettyChannelFactory(properties, globalClientInterceptorRegistry, channelConfigurers);
        final InProcessChannelFactory inProcessChannelFactory = new InProcessChannelFactory(properties, globalClientInterceptorRegistry, channelConfigurers);
        return new InProcessOrAlternativeChannelFactory(properties, inProcessChannelFactory, channelFactory);
    }

    /**
     * 使用 InProcessChannelFactory 创建
     *
     * @param properties
     * @param globalClientInterceptorRegistry
     * @param channelConfigurers
     * @return
     */
    // Finally try the in process channel factory
    @ConditionalOnMissingBean(GrpcChannelFactory.class)
    @Bean
    @Lazy
    GrpcChannelFactory inProcessGrpcChannelFactory(final GrpcChannelsProperties properties,
                                                   final GlobalClientInterceptorRegistry globalClientInterceptorRegistry,
                                                   final List<GrpcChannelConfigurer> channelConfigurers) {
        return new InProcessChannelFactory(properties, globalClientInterceptorRegistry, channelConfigurers);
    }

}
