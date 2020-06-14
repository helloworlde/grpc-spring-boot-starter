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

package net.devh.boot.grpc.client.inject;

import com.google.common.collect.Lists;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.AbstractBlockingStub;
import io.grpc.stub.AbstractFutureStub;
import io.grpc.stub.AbstractStub;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import net.devh.boot.grpc.client.nameresolver.NameResolverRegistration;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeansException;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * BeanPostProcessor 查找 Bean 中被 @GrpcClient 注解标记的属性和方法
 * This {@link BeanPostProcessor} searches for fields and methods in beans that are annotated with {@link GrpcClient}
 * and sets them.
 *
 * @author Michael (yidongnan@gmail.com)
 * @author Daniel Theuke (daniel.theuke@heuboe.de)
 */
public class GrpcClientBeanPostProcessor implements BeanPostProcessor {

    private final ApplicationContext applicationContext;

    // Is only retrieved when needed to avoid too early initialization of these components,
    // which could lead to problems with the correct bean setup.
    private GrpcChannelFactory channelFactory = null;
    private List<StubTransformer> stubTransformers = null;

    /**
     * 根据 ApplicationContext 创建 GrpcClientBeanPostProcessor
     * Creates a new GrpcClientBeanPostProcessor with the given ApplicationContext.
     *
     * @param applicationContext The application context that will be used to get lazy access to the
     *                           {@link GrpcChannelFactory} and {@link StubTransformer}s.
     */
    public GrpcClientBeanPostProcessor(final ApplicationContext applicationContext) {
        this.applicationContext = requireNonNull(applicationContext, "applicationContext");
    }

    /**
     * 当 Bean初始化完成后为 GrpcClient 修饰的类注入 Stub
     *
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    @Override
    public Object postProcessBeforeInitialization(final Object bean, final String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();
        // 遍历查找该类的所有父类，设置相应的属性
        do {
            // 遍历所有属性，查找 GrpcClient 修饰的
            for (final Field field : clazz.getDeclaredFields()) {
                final GrpcClient annotation = AnnotationUtils.findAnnotation(field, GrpcClient.class);
                if (annotation != null) {
                    ReflectionUtils.makeAccessible(field);
                    // 为属性设置相应的 Stub
                    ReflectionUtils.setField(field, bean, processInjectionPoint(field, field.getType(), annotation));
                }
            }

            // 遍历所有的方法，查找 GrpcClient 修饰的
            for (final Method method : clazz.getDeclaredMethods()) {
                final GrpcClient annotation = AnnotationUtils.findAnnotation(method, GrpcClient.class);
                if (annotation != null) {
                    final Class<?>[] paramTypes = method.getParameterTypes();
                    if (paramTypes.length != 1) {
                        throw new BeanDefinitionStoreException("Method " + method + " doesn't have exactly one parameter.");
                    }
                    ReflectionUtils.makeAccessible(method);
                    // 为属性设置 Stub
                    ReflectionUtils.invokeMethod(method, bean, processInjectionPoint(method, paramTypes[0], annotation));
                }
            }
            clazz = clazz.getSuperclass();
        } while (clazz != null);
        return bean;
    }

    /**
     * 处理给定的注入点并设置注入值
     * Processes the given injection point and computes the appropriate value for the injection.
     *
     * @param <T>             The type of the value to be injected.
     * @param injectionTarget The target of the injection.
     * @param injectionType   The class that will be used to compute injection.
     * @param annotation      The annotation on the target with the metadata for the injection.
     * @return The value to be injected for the given injection point.
     */
    protected <T> T processInjectionPoint(final Member injectionTarget,
                                          final Class<T> injectionType,
                                          final GrpcClient annotation) {
        // 获取注解所有的拦截器
        final List<ClientInterceptor> interceptors = interceptorsFromAnnotation(annotation);
        // 应用名称
        final String name = annotation.value();
        final Channel channel;
        try {
            // 根据应用名称，拦截器，拦截器是否排序创建channel
            channel = getChannelFactory().createChannel(name, interceptors, annotation.sortInterceptors());
            if (channel == null) {
                throw new IllegalStateException("Channel factory created a null channel for " + name);
            }
        } catch (final RuntimeException e) {
            throw new IllegalStateException("Failed to create channel: " + name, e);
        }

        // 创建 Stub
        final T value = valueForMember(name, injectionTarget, injectionType, channel);
        if (value == null) {
            throw new IllegalStateException("Injection value is null unexpectedly for " + name + " at " + injectionTarget);
        }
        return value;
    }

    /**
     * 获取 GrpcChannelFactory
     * Lazy getter for the {@link GrpcChannelFactory}.
     *
     * @return The grpc channel factory to use.
     */
    private GrpcChannelFactory getChannelFactory() {
        if (this.channelFactory == null) {
            // Ensure that the NameResolverProviders have been registered
            // 从上下文中获取 NameResolverRegistration TODO 作用是啥？
            this.applicationContext.getBean(NameResolverRegistration.class);
            // 获取 GrpcChannelFactory bean
            final GrpcChannelFactory factory = this.applicationContext.getBean(GrpcChannelFactory.class);
            this.channelFactory = factory;
            return factory;
        }
        return this.channelFactory;
    }

    /**
     * 获取 StubTransformer
     * Lazy getter for the {@link StubTransformer}s.
     *
     * @return The stub transformers to use.
     */
    private List<StubTransformer> getStubTransformers() {
        if (this.stubTransformers == null) {
            final Collection<StubTransformer> transformers = this.applicationContext.getBeansOfType(StubTransformer.class).values();
            this.stubTransformers = new ArrayList<>(transformers);
            return this.stubTransformers;
        }
        return this.stubTransformers;
    }

    /**
     * 根据给定的注解获取或创建其拦截器
     * <p>
     * Gets or creates the {@link ClientInterceptor}s that are referenced in the given annotation.
     *
     * <p>
     * <b>Note:</b> This methods return value does not contain the global client interceptors because they are handled
     * by the {@link GrpcChannelFactory}.
     * 因为全局的 Client 拦截器已经被 GrpcChannelFactory 处理了，所以该方法不会返回
     * </p>
     *
     * @param annotation The annotation to get the interceptors for.
     * @return A list containing the interceptors for the given annotation.
     * @throws BeansException If the referenced interceptors weren't found or could not be created.
     */
    protected List<ClientInterceptor> interceptorsFromAnnotation(final GrpcClient annotation) throws BeansException {
        final List<ClientInterceptor> list = Lists.newArrayList();
        // 根据拦截器类获取拦截器
        for (final Class<? extends ClientInterceptor> interceptorClass : annotation.interceptors()) {
            final ClientInterceptor clientInterceptor;
            // 根据拦截器类获取实例，如果实例不存在则创建一个新的
            if (this.applicationContext.getBeanNamesForType(ClientInterceptor.class).length > 0) {
                clientInterceptor = this.applicationContext.getBean(interceptorClass);
            } else {
                try {
                    clientInterceptor = interceptorClass.getConstructor().newInstance();
                } catch (final Exception e) {
                    throw new BeanCreationException("Failed to create interceptor instance", e);
                }
            }
            list.add(clientInterceptor);
        }
        // 根据拦截器名称获取拦截器
        for (final String interceptorName : annotation.interceptorNames()) {
            list.add(this.applicationContext.getBean(interceptorName, ClientInterceptor.class));
        }
        return list;
    }

    /**
     * 根据所给的 Member 创建 Stub 并返回
     * Creates the instance to be injected for the given member.
     *
     * @param <T>             The type of the instance to be injected.
     * @param name            The name that was used to create the channel.
     * @param injectionTarget The target member for the injection.
     * @param injectionType   The class that should injected.
     * @param channel         The channel that should be used to create the instance.
     * @return The value that matches the type of the given field.
     * @throws BeansException If the value of the field could not be created or the type of the field is unsupported.
     */
    protected <T> T valueForMember(final String name,
                                   final Member injectionTarget,
                                   final Class<T> injectionType,
                                   final Channel channel) throws BeansException {
        // 如果是 channel，则转为channel后返回
        if (Channel.class.equals(injectionType)) {
            return injectionType.cast(channel);
        } else if (AbstractStub.class.isAssignableFrom(injectionType)) {
            // 如果是 Stub，则创建 stub，并处理后返回
            AbstractStub<?> stub = createStub(injectionType.asSubclass(AbstractStub.class), channel);
            // 获取 StubTransformer 并转换 stub
            for (final StubTransformer stubTransformer : getStubTransformers()) {
                stub = stubTransformer.transform(name, stub);
            }
            return injectionType.cast(stub);
        } else {
            throw new InvalidPropertyException(injectionTarget.getDeclaringClass(), injectionTarget.getName(), "Unsupported type " + injectionType.getName());
        }
    }

    /**
     * 根据给定的类型创建 Stub
     * Creates a stub of the given type.
     *
     * @param <T>      The type of the instance to be injected.
     * @param stubType The type of the stub to create.
     * @param channel  The channel used to create the stub.
     * @return The newly created stub.
     * @throws BeanInstantiationException If the stub couldn't be created.
     */
    protected <T extends AbstractStub<T>> T createStub(final Class<T> stubType, final Channel channel) {
        try {
            // 根据类获取要创建的 Stub 类型
            final String methodName = deriveStubFactoryMethodName(stubType);
            // 获取工厂信息
            final Class<?> enclosingClass = stubType.getEnclosingClass();
            final Method factoryMethod = enclosingClass.getMethod(methodName, Channel.class);
            // 调用工厂方法创建 Stub并返回
            return stubType.cast(factoryMethod.invoke(null, channel));
        } catch (final Exception e) {
            try {
                // Use the private constructor as backup
                // 直接通过 channel创建新的实例
                final Constructor<T> constructor = stubType.getDeclaredConstructor(Channel.class);
                constructor.setAccessible(true);
                return constructor.newInstance(channel);
            } catch (final Exception e1) {
                e.addSuppressed(e1);
            }
            throw new BeanInstantiationException(stubType, "Failed to create gRPC client", e);
        }
    }

    /**
     * 根据类返回要创建的 Stub 类型
     * Derives the name of the factory method from the given stub type.
     *
     * @param stubType The type of the stub to get it for.
     * @return The name of the factory method.
     * @throws IllegalArgumentException If the method was called with an unsupported stub type.
     */
    protected String deriveStubFactoryMethodName(final Class<? extends AbstractStub<?>> stubType) {
        if (AbstractAsyncStub.class.isAssignableFrom(stubType)) {
            return "newStub";
        } else if (AbstractBlockingStub.class.isAssignableFrom(stubType)) {
            return "newBlockingStub";
        } else if (AbstractFutureStub.class.isAssignableFrom(stubType)) {
            return "newFutureStub";
        } else {
            throw new IllegalArgumentException("Unsupported stub type: " + stubType.getName() + " -> Please report this issue.");
        }
    }

}
