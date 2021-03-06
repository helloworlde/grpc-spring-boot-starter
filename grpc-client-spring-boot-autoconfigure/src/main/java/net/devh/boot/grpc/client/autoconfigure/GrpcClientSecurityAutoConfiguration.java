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

import io.grpc.CallCredentials;
import io.grpc.stub.AbstractStub;
import net.devh.boot.grpc.client.inject.StubTransformer;
import net.devh.boot.grpc.client.security.CallCredentialsHelper;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Client 端安全配置
 * The security auto configuration for the client.
 *
 * <p>
 * You can disable this config by using:
 * </p>
 *
 * <pre>
 * <code>@ImportAutoConfiguration(exclude = GrpcClientSecurityAutoConfiguration.class)</code>
 * </pre>
 *
 * @author Daniel Theuke (daniel.theuke@heuboe.de)
 */
@Configuration
@AutoConfigureBefore(GrpcClientAutoConfiguration.class)
public class GrpcClientSecurityAutoConfiguration {

    /**
     * 创建 StubTransformer 的bean，会将证书添加到创建的 stub中
     * Creates a {@link StubTransformer} bean that will add the call credentials to the created stubs.
     *
     * <p>
     * <b>Note:</b> This method will only be applied if exactly one {@link CallCredentials} is in the application
     * context.
     * 只有当上下文中有 CallCredentials 时该方法才会生效
     * </p>
     *
     * @param credentials The call credentials to configure in the stubs.
     * @return The StubTransformer bean that will add the given credentials.
     * @sse {@link CallCredentialsHelper#fixedCredentialsStubTransformer(CallCredentials)}
     * @see AbstractStub#withCallCredentials(CallCredentials)
     */
    @ConditionalOnSingleCandidate(CallCredentials.class)
    @Bean
    StubTransformer stubCallCredentialsTransformer(final CallCredentials credentials) {
        return CallCredentialsHelper.fixedCredentialsStubTransformer(credentials);
    }

}
