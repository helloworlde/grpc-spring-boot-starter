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

import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import net.devh.boot.grpc.client.config.GrpcChannelProperties;
import net.devh.boot.grpc.client.config.GrpcChannelProperties.Security;
import net.devh.boot.grpc.client.config.GrpcChannelsProperties;
import net.devh.boot.grpc.client.config.NegotiationType;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorRegistry;
import org.springframework.core.io.Resource;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * 创建管理基于 GrpcChannelFactory 的netty
 * This channel factory creates and manages netty based {@link GrpcChannelFactory}s.
 *
 * <p>
 * This class utilizes connection pooling and thus needs to be {@link #close() closed} after usage.
 * </p>
 *
 * @author Michael (yidongnan@gmail.com)
 * @author Daniel Theuke (daniel.theuke@heuboe.de)
 * @since 5/17/16
 */
// Keep this file in sync with ShadedNettyChannelFactory
public class NettyChannelFactory extends AbstractChannelFactory<NettyChannelBuilder> {

    /**
     * 根据所给的属性创建新的 GrpcChannelFactory
     * Creates a new GrpcChannelFactory for netty with the given options.
     *
     * @param properties                      The properties for the channels to create.
     * @param globalClientInterceptorRegistry The interceptor registry to use.
     * @param channelConfigurers              The channel configurers to use. Can be empty.
     */
    public NettyChannelFactory(final GrpcChannelsProperties properties,
                               final GlobalClientInterceptorRegistry globalClientInterceptorRegistry,
                               final List<GrpcChannelConfigurer> channelConfigurers) {
        super(properties, globalClientInterceptorRegistry, channelConfigurers);
    }

    /**
     * 将交换类型转为 Netty 的交换类型
     * Converts the given negotiation type to netty's negotiation type.
     *
     * @param negotiationType The negotiation type to convert.
     * @return The converted negotiation type.
     */
    protected static io.grpc.netty.NegotiationType of(final NegotiationType negotiationType) {
        switch (negotiationType) {
            case PLAINTEXT:
                return io.grpc.netty.NegotiationType.PLAINTEXT;
            case PLAINTEXT_UPGRADE:
                return io.grpc.netty.NegotiationType.PLAINTEXT_UPGRADE;
            case TLS:
                return io.grpc.netty.NegotiationType.TLS;
            default:
                throw new IllegalArgumentException("Unsupported NegotiationType: " + negotiationType);
        }
    }

    /**
     * 根据服务名称创建 Channel
     *
     * @param name The name to create the channel builder for.
     * @return
     */
    @Override
    protected NettyChannelBuilder newChannelBuilder(final String name) {
        final GrpcChannelProperties properties = getPropertiesFor(name);
        URI address = properties.getAddress();
        // 如果地址为空，则根据服务名称创建
        if (address == null) {
            address = URI.create(name);
        }
        // 使用服务地址和默认的负载均衡策略创建 Builder
        return NettyChannelBuilder.forTarget(address.toString())
                                  .defaultLoadBalancingPolicy(properties.getDefaultLoadBalancingPolicy());
    }

    /**
     * 配置安全属性
     *
     * @param builder The channel builder to configure.
     * @param name    The name of the client to configure.
     */
    @Override
    protected void configureSecurity(final NettyChannelBuilder builder, final String name) {
        final GrpcChannelProperties properties = getPropertiesFor(name);

        final NegotiationType negotiationType = properties.getNegotiationType();
        builder.negotiationType(of(negotiationType));

        if (negotiationType == NegotiationType.TLS) {
            final Security security = properties.getSecurity();

            final String authorityOverwrite = security.getAuthorityOverride();
            if (authorityOverwrite != null && !authorityOverwrite.isEmpty()) {
                builder.overrideAuthority(authorityOverwrite);
            }

            final SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();

            if (security.isClientAuthEnabled()) {
                final Resource certificateChain =
                        requireNonNull(security.getCertificateChain(), "certificateChain not configured");
                final Resource privateKey = requireNonNull(security.getPrivateKey(), "privateKey not configured");
                try (InputStream certificateChainStream = certificateChain.getInputStream();
                     InputStream privateKeyStream = privateKey.getInputStream()) {
                    sslContextBuilder.keyManager(certificateChainStream, privateKeyStream,
                            security.getPrivateKeyPassword());
                } catch (IOException | RuntimeException e) {
                    throw new IllegalArgumentException("Failed to create SSLContext (PK/Cert)", e);
                }
            }

            final Resource trustCertCollection = security.getTrustCertCollection();
            if (trustCertCollection != null) {
                try (InputStream trustCertCollectionStream = trustCertCollection.getInputStream()) {
                    sslContextBuilder.trustManager(trustCertCollectionStream);
                } catch (IOException | RuntimeException e) {
                    throw new IllegalArgumentException("Failed to create SSLContext (TrustStore)", e);
                }
            }

            if (security.getCiphers() != null && !security.getCiphers().isEmpty()) {
                sslContextBuilder.ciphers(security.getCiphers());
            }

            if (security.getProtocols() != null && security.getProtocols().length > 0) {
                sslContextBuilder.protocols(security.getProtocols());
            }

            try {
                builder.sslContext(sslContextBuilder.build());
            } catch (final SSLException e) {
                throw new IllegalStateException("Failed to create ssl context for grpc client", e);
            }
        }
    }

}
