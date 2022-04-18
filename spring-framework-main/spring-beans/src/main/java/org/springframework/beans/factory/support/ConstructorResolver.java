/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 * @since 2.0
 */
class ConstructorResolver {

    private static final Object[] EMPTY_ARGS = new Object[0];

    /**
     * Marker for autowired arguments in a cached argument array, to be replaced
     * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
     */
    private static final Object autowiredArgumentMarker = new Object();

    private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
            new NamedThreadLocal<>("Current injection point");


    private final AbstractAutowireCapableBeanFactory beanFactory;

    private final Log logger;


    /**
     * Create a new ConstructorResolver for the given factory and instantiation strategy.
     *
     * @param beanFactory the BeanFactory to work with
     */
    public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        this.logger = beanFactory.getLogger();
    }


    /**
     * "autowire constructor" (with constructor arguments by type) behavior.
     * Also applied if explicit constructor argument values are specified,
     * matching all remaining arguments with beans from the bean factory.
     * <p>This corresponds to constructor injection: In this mode, a Spring
     * bean factory is able to host components that expect constructor-based
     * dependency resolution.
     *
     * @param beanName     the name of the bean
     * @param mbd          the merged bean definition for the bean
     * @param chosenCtors  chosen candidate constructors (or {@code null} if none)
     * @param explicitArgs argument values passed in programmatically via the getBean method,
     *                     or {@code null} if none (-> use constructor argument values from bean definition)
     * @return a BeanWrapper for the new instance
     */
    public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
                                           @Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {
		/**
		 * 一句废话:
		 * 	BeanWrapperImpl是BeanWrapper的实现类
		 *  并且这个类用来存储Bean的名称,class,实例对象等.
		 * */
        BeanWrapperImpl bw = new BeanWrapperImpl();
        // 设置转换服务 注册
        this.beanFactory.initBeanWrapper(bw);
		/**
		 * constructorToUse是我们实际上使用的构造器,因为传进来的构造器一看见就知道是个数组,那么Spring需要筛选出来最适合的构造器,那么筛选出来最适合的构造器就会赋值给这里的constructorToUse
		 * */
        Constructor<?> constructorToUse = null;
		/**
		 * argsHolderToUse用来存储用到的构造器的参数,下面的argsToUse的值也是从这个argsHolderToUse中取出来的
		 */
        ArgumentsHolder argsHolderToUse = null;

		/**
		 * 构造方法中使用到的参数列表实际的值
		 * */
        Object[] argsToUse = null;

		/**
		 * 总结一下这个if和else:
		 * 	1、判断是否传入构造参数值列表,如果传入则赋值
		 * 	2、没有传入则从缓存中去取
		 * */
		/**
		 * 如果我们getBean的时候传入了参数,那么Spring就认为我们希望按照指定的构造参数列表去寻找构造器并实例化对象.
		 * 那么这里如果不为空则实际上需要使用的构造方法参数列表值就已经确定了
		 * */
        if (explicitArgs != null) {
            argsToUse = explicitArgs;
        } else {
            Object[] argsToResolve = null;
            synchronized (mbd.constructorArgumentLock) {
                //尝试从mbd的缓存中拿取构造方法
                constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
                //构造方法不为空并且构造方法已经解析
                if (constructorToUse != null && mbd.constructorArgumentsResolved) {
                    // Found a cached constructor...
                    //直接从mbd缓存中拿取构造方法的参数
                    argsToUse = mbd.resolvedConstructorArguments;
                    if (argsToUse == null) {
                        //如果没有拿到构造方法的参数，就获取缓存中的配置文件的参数
                        argsToResolve = mbd.preparedConstructorArguments;
                    }
                }
            }
            if (argsToResolve != null) {
                //正确拿到配置文件的参数之后，对参数进行解析，最后生成构造方法的参数
                argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
            }
        }
        /**
         * 这里判断constructorToUse是否为空是因为上面有可能从缓存里面已经拿到了,如果拿到了则不需要进if里面去寻找,直接去调用创建实例化操作了.
         * 这里重要的是if里面的代码.
         * */
        if (constructorToUse == null || argsToUse == null) {
            // Take specified constructors, if any.
            //获取传入的构造器
            Constructor<?>[] candidates = chosenCtors;
            //如果构造器不为空
            if (candidates == null) {
                //获取bean的类型
                Class<?> beanClass = mbd.getBeanClass();
                try {
                    //判断是否允许非公开访问，如果允许就获取所有的构造方法，如果不允许就获取public的构造方法
                    candidates = (mbd.isNonPublicAccessAllowed() ?
                            beanClass.getDeclaredConstructors() : beanClass.getConstructors());
                } catch (Throwable ex) {
                    throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                            "Resolution of declared constructors on bean Class [" + beanClass.getName() +
                                    "] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
                }
            }
            //如果只有一个构造方法并且，指定参数为空，并且配置文件里面没有构造方法的参数值
            if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
                Constructor<?> uniqueCandidate = candidates[0];
                //判断这个构造方法的参数个数为0，这个构造方式 就是默认的构造方法
                if (uniqueCandidate.getParameterCount() == 0) {
                    synchronized (mbd.constructorArgumentLock) {
                        //记录到mbd的缓存中去
                        mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
                        mbd.constructorArgumentsResolved = true;
                        mbd.resolvedConstructorArguments = EMPTY_ARGS;
                    }
                    //初始化bean， 并且设置到bw（bean 的包装对象）中去
                    bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
                    return bw;
                }
            }

            // Need to resolve the constructor.
            //这里判断了是否是自动导入的
            boolean autowiring = (chosenCtors != null ||
                    mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
            ConstructorArgumentValues resolvedValues = null;

            int minNrOfArgs;
            if (explicitArgs != null) {
                //获取用户传入参数的个数
                minNrOfArgs = explicitArgs.length;
            } else {
                //从配置文件中拿到参数的值
                ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
                resolvedValues = new ConstructorArgumentValues();
                //获取到构造方法中参数的方法的参数个数
                minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
            }
            //对构造方法进行排序，public的排在前面，参数多的排在前面
            /**
             * 对构造器进行排序:
             * 	public的大于其他的权限
             * 	如果都是public的,那么参数越多越靠前.
             * 	可以看这个sort方法里面可以看到的
             * */
            AutowireUtils.sortConstructors(candidates);
            /**
             * 差异变量
             * */
            int minTypeDiffWeight = Integer.MAX_VALUE;
            /**
             * 有歧义的构造器,就是参数数量一致的,这种情况下的构造器就被列为有歧义的.
             * 正常情况下,如果出现有歧义的构造器,那么就使用第一个,这取决于spring设置的宽松模式.
             * 默认为宽松,如此的话就默认使用第一个构造器使用
             * 如果设置为严格,则会报错
             * 设置宽松/严格模式标志:beanDefinition.setLenientConstructorResolution
             * */
            Set<Constructor<?>> ambiguousConstructors = null;
            Deque<UnsatisfiedDependencyException> causes = null;
            //逐一遍历所有的构造方法
            /**
             * 下面就是循环的拿构造器去校验判断选取一个合适的构造器了.在此之前我们总结一下上述代码做的事情.
             * 1、定义constructorToUse、argsHolderToUse、argsToUse,这些分别用来存后面实际上需要使用的构造器、构造器参数、值等
             * 2、如果getBean调用的时候传入了构造器参数,那么argsToUse的值就被赋值为传入的构造器参数,否则就尝试从缓存里面去拿constructorToUse和argsToUse
             * 		这个缓存就是当bean不是原型的时候实例化时找到的合适的构造器等参数,当然如果是第一次进来,或者bean是单例的,那么此缓存中肯定没有这个bean相关的构造器数据
             * 3、如果缓存里面有,则直接实例化bean后放到wrapper中并return,如果不存在则需要再次进行一些操作
             * 4、在不存在时,首先定义resolvedValues,这个是后续循环里面需要使用到的构造器使用的参数列表,定义minNrOfArgs,这个是最小参数个数,首先如果getBean传入了构造器参数
             * 那么此值就是传入构造参数的长度,否则就尝试看我们有没有配置使用某个构造器,如果都没有,那么这个值就是0了,这个变量用来后面在循环构造器的时候筛选用的
             * 5、然后定义candidates变量,然后将chosenCtors(是前面传入的构造器列表)赋值过去,如果它为空,那么则需要去通过class去拿构造器,拿的时候判断了一手
             * 去拿BeanDefinition中的isNonPublicAccessAllowed,这个isNonPublicAccessAllowed意思为是否允许访问非public的构造器,如果为true,则去获取所有的构造器,否则只获取public的
             * 6、然后对所有的构造器进行排序,规则为public>其他权限,参数个数多的>参数个数少的,至于为什么排序这个可能是spring认为参数越多的越科学吧
             * 7、差异变量,这个看循环里面的代码才能理解
             * 8、定义ambiguousConstructors为有歧义的构造器,意思就是如果两个构造器参数一致,那Spring就不知道该去用哪个,这时这两个构造器就被放入ambiguousConstructors集合中,他们两个就是有歧义的构造器
             * ================================================================================
             * 下面循环里面需要搞清楚的就是它具体是如何选取到合适的构造器来使用
             * */
            for (Constructor<?> candidate : candidates) {

                int parameterCount = candidate.getParameterCount();
                //这里的判断构造方法和构造方法参数 都不是空，又由于之前对构造方法做了排序。
                // 所以在使用的参数的个数已经大于当前构造方法的参数个数的时候，实际上已经取到了想要的构造方法。
                if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
                    // Already found greedy constructor that can be satisfied ->
                    // do not look any further, there are only less greedy constructors left.
                    break;
                }
                //通过构造方法的参数个数 快速的做一个判断
                /**
                 * 如果当前构造器的参数数量比最小参数列表数量小的时候,那么跳过这个构造器.
                 * minNrOfArgs的值有两个地方赋值了:
                 * 	1、如果我们getBean时传入了其他参数,那么其他参数的个数就是minNrOfArgs的值
                 * 	2、如果我们getBean没有传参数,那么minNrOfArgs的值就是我们配置让Spring指定使用某些参数的构造器,那么我们配置的参数列表数量也就是当前的minNrOfArgs
                 * 	3、如果上述的情况都不存在,那么minNrOfArgs就是0了,大多数时候都是这种情况,如果都没配置,那么就得Spring自己慢慢而不存在此处的筛选了.
                 * 所以总结来说此处就是做了一个根据我们自己定义的来筛选的操作
                 * */
                if (parameterCount < minNrOfArgs) {
                    continue;
                }
                /**
                 * 存储构造器需要的参数
                 * */
                ArgumentsHolder argsHolder;
                //获取构造方法的参数类型
                /**
                 * 拿到当前构造方法的参数class数组
                 * */
                Class<?>[] paramTypes = candidate.getParameterTypes();
                /**
                 * 此处resolvedValues不为空则说明getBean时传入的参数explicitArgs为空的,
                 * 因为上面的代码是如果explicitArgs不为空则不对resolvedValues赋值,否则就对resolvedValues赋值
                 * 此处先看else的代码,会更清晰.
                 * 如果传入的参数为空,那么则会去拿参数了
                 * */
                if (resolvedValues != null) {
                    try {
                        String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
                        if (paramNames == null) {
                            //参数名冲突的解决器
                            ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
                            if (pnd != null) {
                                paramNames = pnd.getParameterNames(candidate);
                            }
                        }
                        //用获取到的参数名和和构造函数以及参数类型生成用户创建构造函数使用的构造参数数组，
                        // 数组里会同时持有原始的参数列表和构造后的参数列表。
                        /**
                         * 此处会去获取这些参数名称的参数值,如果是自动注入的就会通过getBean获取,当前这种构造器注入的情况如果循环依赖则会报错的.
                         * 这里我们只需要知道,此处将构造器需要的参数值拿出来后并封装到了argsHolder中去.
                         * 当然如果你构造器里面给个Integer的参数,那肯定是会报错的,因为这里面会去Spring容器中拿这个Integer,结果呢,肯定是NoSuchBeanDefinitionException了
                         * 其余这里不用太过于细究,有兴趣可以详细看.
                         * */
                        argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
                                getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
                    } catch (UnsatisfiedDependencyException ex) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
                        }
                        // Swallow and try next constructor.
                        if (causes == null) {
                            causes = new ArrayDeque<>(1);
                        }
                        causes.add(ex);
                        continue;
                    }
                } else {
                    /**
                     * 到了这个else里面来,说明getBean调用的时候传入了构造器参数,那么就说明我们希望按照指定的构造器去初始化Bean.
                     * 那么这里就需要判断当前构造器的参数个数是否和我们希望的个数一样,如果不是,那么就循环去找下一个构造器,
                     * 如果和我们希望的是一样的,那么就将我们给的参数封装到argsHolder里面去
                     * */
                    if (parameterCount != explicitArgs.length) {
                        continue;
                    }
                    argsHolder = new ArgumentsHolder(explicitArgs);
                }

                //isLenientConstructorResolution()判断策略是否宽松
                //宽松策略下，使用spring构造的参数数组的类型和获取到的构造方法的参数类型进行对比。
                //严格策略下，还需要检查能否将构造方法的参数复制到对应的属性中
                //会返回一个数值，作为构造方法和参数的差异值
                /**
                 * 当到达这里的时候,至此我们拿到了:
                 * 	1、构造器
                 * 	2、构造器需要的参数和值
                 * 那么这里就去结算前面定义的那个差异值.
                 * 注意这里的:isLenientConstructorResolution意思是是否为宽松的模式,为true的时候是宽松,false的时候是严格,默认为true,这个东西前面已经说了.
                 * 这个差异值越小越那就说明越合适.
                 * 具体差异值如何计算出来的这个可以自行去看里面的代码,argsHolder.getTypeDifferenceWeight(paramTypes)
                 * */
                int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
                        argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
                // Choose this constructor if it represents the closest match.
                //判断当前的差异值是否小于之前的最小差异值
                /**
                 * 如果本次计算到的差异值比上一次获取到的差异值小,那么就需要做这几件事:
                 * 	1、设置constructorToUse为当前的这个构造器
                 * 	2、设置参数和参数值
                 * 	3、给差异值赋值为当前计算出来的差异值
                 * 	4、清空有歧义的集合(因为此时我们已经得到了更合适的构造器,所以有歧义的构造器里面保存的构造器已经没有存在的意义了)
                 * */
                if (typeDiffWeight < minTypeDiffWeight) {
                    constructorToUse = candidate;
                    argsHolderToUse = argsHolder;
                    argsToUse = argsHolder.arguments;
                    minTypeDiffWeight = typeDiffWeight;
                    ambiguousConstructors = null;
                    /**
                     * 如果已经找到过一次构造器,并且当前的差异值和上一次的差异值一致的话,那么说明这两个构造器是有歧义的,
                     * 那么就将这两个构造器放到[有歧义的构造器]集合中去.
                     * */
                } else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
                    if (ambiguousConstructors == null) {
                        ambiguousConstructors = new LinkedHashSet<>();
                        ambiguousConstructors.add(constructorToUse);
                    }
                    ambiguousConstructors.add(candidate);
                }
            }
            /**
             * 到达这里的时候,如果还没有找到合适的构造器,那么则直接抛出异常,这个类没法实例化
             * */
            if (constructorToUse == null) {
                if (causes != null) {
                    UnsatisfiedDependencyException ex = causes.removeLast();
                    for (Exception cause : causes) {
                        this.beanFactory.onSuppressedException(cause);
                    }
                    throw ex;
                }
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
                                "(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
            } else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
                                "(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
                                ambiguousConstructors);
            }

            if (explicitArgs == null && argsHolderToUse != null) {
                //做一个缓存，方便下次的使用
                //如果当前getBean没有传参数,那么则将当前的构造器和参数放到缓存里面去,可能Spring认为传入参数的情况下说不准我们准备怎么做,所以干脆我们自己传入参数的就不缓存了
                argsHolderToUse.storeCache(mbd, constructorToUse);
            }
        }

        Assert.state(argsToUse != null, "Unresolved constructor arguments");
        //用上面得到的构造器和参数来反射创建bean实例，并放到BeanWrapperImpl对象中然后返回
        bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
        return bw;
    }

    private Object instantiate(
            String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

        try {
            InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
            return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
        } catch (Throwable ex) {
            throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                    "Bean instantiation via constructor failed", ex);
        }
    }

    /**
     * Resolve the factory method in the specified bean definition, if possible.
     * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
     *
     * @param mbd the bean definition to check
     */
    public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
        Class<?> factoryClass;
        boolean isStatic;
        if (mbd.getFactoryBeanName() != null) {
            factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
            isStatic = false;
        } else {
            factoryClass = mbd.getBeanClass();
            isStatic = true;
        }
        Assert.state(factoryClass != null, "Unresolvable factory class");
        factoryClass = ClassUtils.getUserClass(factoryClass);

        Method[] candidates = getCandidateMethods(factoryClass, mbd);
        Method uniqueCandidate = null;
        for (Method candidate : candidates) {
            if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
                if (uniqueCandidate == null) {
                    uniqueCandidate = candidate;
                } else if (isParamMismatch(uniqueCandidate, candidate)) {
                    uniqueCandidate = null;
                    break;
                }
            }
        }
        mbd.factoryMethodToIntrospect = uniqueCandidate;
    }

    private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
        int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
        int candidateParameterCount = candidate.getParameterCount();
        return (uniqueCandidateParameterCount != candidateParameterCount ||
                !Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
    }

    /**
     * Retrieve all candidate methods for the given class, considering
     * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
     * Called as the starting point for factory method determination.
     */
    private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
        return (mbd.isNonPublicAccessAllowed() ?
                ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
    }

    /**
     * Instantiate the bean using a named factory method. The method may be static, if the
     * bean definition parameter specifies a class, rather than a "factory-bean", or
     * an instance variable on a factory object itself configured using Dependency Injection.
     * <p>Implementation requires iterating over the static or instance methods with the
     * name specified in the RootBeanDefinition (the method may be overloaded) and trying
     * to match with the parameters. We don't have the types attached to constructor args,
     * so trial and error is the only way to go here. The explicitArgs array may contain
     * argument values passed in programmatically via the corresponding getBean method.
     *
     * @param beanName     the name of the bean
     * @param mbd          the merged bean definition for the bean
     * @param explicitArgs argument values passed in programmatically via the getBean
     *                     method, or {@code null} if none (-> use constructor argument values from bean definition)
     * @return a BeanWrapper for the new instance
     */
    public BeanWrapper instantiateUsingFactoryMethod(
            String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
        // 声明一个包装类
        BeanWrapperImpl bw = new BeanWrapperImpl();
        this.beanFactory.initBeanWrapper(bw);

        Object factoryBean;
        Class<?> factoryClass;
        // 是否是静态工厂 还是实例工厂
        boolean isStatic;

        String factoryBeanName = mbd.getFactoryBeanName();
        // 等于空就是一个静态工厂 否则是个静态工厂
        if (factoryBeanName != null) {
            if (factoryBeanName.equals(beanName)) {
                throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
                        "factory-bean reference points back to the same bean definition");
            }
            factoryBean = this.beanFactory.getBean(factoryBeanName);
            if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
                throw new ImplicitlyAppearedSingletonException();
            }
            this.beanFactory.registerDependentBean(factoryBeanName, beanName);
            factoryClass = factoryBean.getClass();
            isStatic = false;
        } else {
            // It's a static factory method on the bean class.
            if (!mbd.hasBeanClass()) {
                throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
                        "bean definition declares neither a bean class nor a factory-bean reference");
            }
            factoryBean = null;
            factoryClass = mbd.getBeanClass();
            isStatic = true;
        }
        // 工厂方法
        Method factoryMethodToUse = null;
        // 参数解析器
        ArgumentsHolder argsHolderToUse = null;
        // 参数
        Object[] argsToUse = null;

        if (explicitArgs != null) {
            argsToUse = explicitArgs;
        } else {
            Object[] argsToResolve = null;
            synchronized (mbd.constructorArgumentLock) {
                factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
                if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
                    // Found a cached factory method...
                    argsToUse = mbd.resolvedConstructorArguments;
                    if (argsToUse == null) {
                        argsToResolve = mbd.preparedConstructorArguments;
                    }
                }
            }
            if (argsToResolve != null) {
                argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
            }
        }

        if (factoryMethodToUse == null || argsToUse == null) {
            // Need to determine the factory method...
            // Try all methods with this name to see if they match the given arguments.
            factoryClass = ClassUtils.getUserClass(factoryClass);

            List<Method> candidates = null;
            if (mbd.isFactoryMethodUnique) {
                if (factoryMethodToUse == null) {
                    factoryMethodToUse = mbd.getResolvedFactoryMethod();
                }
                if (factoryMethodToUse != null) {
                    candidates = Collections.singletonList(factoryMethodToUse);
                }
            }
            if (candidates == null) {
                candidates = new ArrayList<>();
                // 获取factoryClass 并所以父类的方法
                Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
                for (Method candidate : rawCandidates) {
                    if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
                        candidates.add(candidate);
                    }
                }
            }

            if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
                Method uniqueCandidate = candidates.get(0);
                if (uniqueCandidate.getParameterCount() == 0) {
                    mbd.factoryMethodToIntrospect = uniqueCandidate;
                    synchronized (mbd.constructorArgumentLock) {
                        mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
                        mbd.constructorArgumentsResolved = true;
                        mbd.resolvedConstructorArguments = EMPTY_ARGS;
                    }
                    bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
                    return bw;
                }
            }

            if (candidates.size() > 1) {  // explicitly skip immutable singletonList
                candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
            }

            ConstructorArgumentValues resolvedValues = null;
            boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
            int minTypeDiffWeight = Integer.MAX_VALUE;
            Set<Method> ambiguousFactoryMethods = null;

            int minNrOfArgs;
            if (explicitArgs != null) {
                minNrOfArgs = explicitArgs.length;
            } else {
                // We don't have arguments passed in programmatically, so we need to resolve the
                // arguments specified in the constructor arguments held in the bean definition.
                if (mbd.hasConstructorArgumentValues()) {
                    ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
                    resolvedValues = new ConstructorArgumentValues();
                    minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
                } else {
                    minNrOfArgs = 0;
                }
            }

            Deque<UnsatisfiedDependencyException> causes = null;

            for (Method candidate : candidates) {
                int parameterCount = candidate.getParameterCount();

                if (parameterCount >= minNrOfArgs) {
                    ArgumentsHolder argsHolder;

                    Class<?>[] paramTypes = candidate.getParameterTypes();
                    if (explicitArgs != null) {
                        // Explicit arguments given -> arguments length must match exactly.
                        if (paramTypes.length != explicitArgs.length) {
                            continue;
                        }
                        argsHolder = new ArgumentsHolder(explicitArgs);
                    } else {
                        // Resolved constructor arguments: type conversion and/or autowiring necessary.
                        try {
                            String[] paramNames = null;
                            ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
                            if (pnd != null) {
                                paramNames = pnd.getParameterNames(candidate);
                            }
                            argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
                                    paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
                        } catch (UnsatisfiedDependencyException ex) {
                            if (logger.isTraceEnabled()) {
                                logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
                            }
                            // Swallow and try next overloaded factory method.
                            if (causes == null) {
                                causes = new ArrayDeque<>(1);
                            }
                            causes.add(ex);
                            continue;
                        }
                    }

                    int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
                            argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
                    // Choose this factory method if it represents the closest match.
                    if (typeDiffWeight < minTypeDiffWeight) {
                        factoryMethodToUse = candidate;
                        argsHolderToUse = argsHolder;
                        argsToUse = argsHolder.arguments;
                        minTypeDiffWeight = typeDiffWeight;
                        ambiguousFactoryMethods = null;
                    }
                    // Find out about ambiguity: In case of the same type difference weight
                    // for methods with the same number of parameters, collect such candidates
                    // and eventually raise an ambiguity exception.
                    // However, only perform that check in non-lenient constructor resolution mode,
                    // and explicitly ignore overridden methods (with the same parameter signature).
                    else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
                            !mbd.isLenientConstructorResolution() &&
                            paramTypes.length == factoryMethodToUse.getParameterCount() &&
                            !Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
                        if (ambiguousFactoryMethods == null) {
                            ambiguousFactoryMethods = new LinkedHashSet<>();
                            ambiguousFactoryMethods.add(factoryMethodToUse);
                        }
                        ambiguousFactoryMethods.add(candidate);
                    }
                }
            }

            if (factoryMethodToUse == null || argsToUse == null) {
                if (causes != null) {
                    UnsatisfiedDependencyException ex = causes.removeLast();
                    for (Exception cause : causes) {
                        this.beanFactory.onSuppressedException(cause);
                    }
                    throw ex;
                }
                List<String> argTypes = new ArrayList<>(minNrOfArgs);
                if (explicitArgs != null) {
                    for (Object arg : explicitArgs) {
                        argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
                    }
                } else if (resolvedValues != null) {
                    Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
                    valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
                    valueHolders.addAll(resolvedValues.getGenericArgumentValues());
                    for (ValueHolder value : valueHolders) {
                        String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
                                (value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
                        argTypes.add(argType);
                    }
                }
                String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "No matching factory method found on class [" + factoryClass.getName() + "]: " +
                                (mbd.getFactoryBeanName() != null ?
                                        "factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
                                "factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
                                "Check that a method with the specified name " +
                                (minNrOfArgs > 0 ? "and arguments " : "") +
                                "exists and that it is " +
                                (isStatic ? "static" : "non-static") + ".");
            } else if (void.class == factoryMethodToUse.getReturnType()) {
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
                                factoryClass.getName() + "]: needs to have a non-void return type!");
            } else if (ambiguousFactoryMethods != null) {
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
                                "(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
                                ambiguousFactoryMethods);
            }

            if (explicitArgs == null && argsHolderToUse != null) {
                mbd.factoryMethodToIntrospect = factoryMethodToUse;
                argsHolderToUse.storeCache(mbd, factoryMethodToUse);
            }
        }

        bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
        return bw;
    }

    private Object instantiate(String beanName, RootBeanDefinition mbd,
                               @Nullable Object factoryBean, Method factoryMethod, Object[] args) {

        try {
            return this.beanFactory.getInstantiationStrategy().instantiate(
                    mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
        } catch (Throwable ex) {
            throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                    "Bean instantiation via factory method failed", ex);
        }
    }

    /**
     * Resolve the constructor arguments for this bean into the resolvedValues object.
     * This may involve looking up other beans.
     * <p>This method is also used for handling invocations of static factory methods.
     */
    private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
                                            ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

        TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
        TypeConverter converter = (customConverter != null ? customConverter : bw);
        BeanDefinitionValueResolver valueResolver =
                new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

        int minNrOfArgs = cargs.getArgumentCount();

        // 遍历构造函数中的对象 根据下标一一创建
        for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
            int index = entry.getKey();
            if (index < 0) {
                throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                        "Invalid constructor argument index: " + index);
            }
            if (index + 1 > minNrOfArgs) {
                minNrOfArgs = index + 1;
            }
            ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
            if (valueHolder.isConverted()) {
                resolvedValues.addIndexedArgumentValue(index, valueHolder);
            } else {
            	// 创建构造函数中的引用对象
                Object resolvedValue =
                        valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
                ConstructorArgumentValues.ValueHolder resolvedValueHolder =
                        new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
                resolvedValueHolder.setSource(valueHolder);
                // 将封装好的参数添加
                resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
            }
        }

        for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
            if (valueHolder.isConverted()) {
                resolvedValues.addGenericArgumentValue(valueHolder);
            } else {
                Object resolvedValue =
                        valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
                ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
                        resolvedValue, valueHolder.getType(), valueHolder.getName());
                resolvedValueHolder.setSource(valueHolder);
                resolvedValues.addGenericArgumentValue(resolvedValueHolder);
            }
        }

        return minNrOfArgs;
    }

    /**
     * Create an array of arguments to invoke a constructor or factory method,
     * given the resolved constructor argument values.
     */
    private ArgumentsHolder createArgumentArray(
            String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
            BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
            boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

        TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
        TypeConverter converter = (customConverter != null ? customConverter : bw);

        ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
        Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
        Set<String> autowiredBeanNames = new LinkedHashSet<>(4);

        for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
            Class<?> paramType = paramTypes[paramIndex];
            String paramName = (paramNames != null ? paramNames[paramIndex] : "");
            // Try to find matching constructor argument value, either indexed or generic.
            ConstructorArgumentValues.ValueHolder valueHolder = null;
            if (resolvedValues != null) {
                valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
                // If we couldn't find a direct match and are not supposed to autowire,
                // let's try the next generic, untyped argument value as fallback:
                // it could match after type conversion (for example, String -> int).
                if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
                    valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
                }
            }
            if (valueHolder != null) {
                // We found a potential match - let's give it a try.
                // Do not consider the same value definition multiple times!
                usedValueHolders.add(valueHolder);
                Object originalValue = valueHolder.getValue();
                Object convertedValue;
                if (valueHolder.isConverted()) {
                    convertedValue = valueHolder.getConvertedValue();
                    args.preparedArguments[paramIndex] = convertedValue;
                } else {
                    MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
                    try {
                        convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
                    } catch (TypeMismatchException ex) {
                        throw new UnsatisfiedDependencyException(
                                mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
                                "Could not convert argument value of type [" +
                                        ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
                                        "] to required type [" + paramType.getName() + "]: " + ex.getMessage());
                    }
                    Object sourceHolder = valueHolder.getSource();
                    if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
                        Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
                        args.resolveNecessary = true;
                        args.preparedArguments[paramIndex] = sourceValue;
                    }
                }
                args.arguments[paramIndex] = convertedValue;
                args.rawArguments[paramIndex] = originalValue;
            } else {
                MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
                // No explicit match found: we're either supposed to autowire or
                // have to fail creating an argument array for the given constructor.
                if (!autowiring) {
                    throw new UnsatisfiedDependencyException(
                            mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
                            "Ambiguous argument values for parameter of type [" + paramType.getName() +
                                    "] - did you specify the correct bean references as arguments?");
                }
                try {
                    Object autowiredArgument = resolveAutowiredArgument(
                            methodParam, beanName, autowiredBeanNames, converter, fallback);
                    args.rawArguments[paramIndex] = autowiredArgument;
                    args.arguments[paramIndex] = autowiredArgument;
                    args.preparedArguments[paramIndex] = autowiredArgumentMarker;
                    args.resolveNecessary = true;
                } catch (BeansException ex) {
                    throw new UnsatisfiedDependencyException(
                            mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
                }
            }
        }

        for (String autowiredBeanName : autowiredBeanNames) {
            this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
            if (logger.isDebugEnabled()) {
                logger.debug("Autowiring by type from bean name '" + beanName +
                        "' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
                        " to bean named '" + autowiredBeanName + "'");
            }
        }

        return args;
    }

    /**
     * Resolve the prepared arguments stored in the given bean definition.
     */
    private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
                                              Executable executable, Object[] argsToResolve) {

        TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
        TypeConverter converter = (customConverter != null ? customConverter : bw);
        BeanDefinitionValueResolver valueResolver =
                new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
        Class<?>[] paramTypes = executable.getParameterTypes();

        Object[] resolvedArgs = new Object[argsToResolve.length];
        for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
            Object argValue = argsToResolve[argIndex];
            MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
            if (argValue == autowiredArgumentMarker) {
                argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, true);
            } else if (argValue instanceof BeanMetadataElement) {
                argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
            } else if (argValue instanceof String) {
                argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
            }
            Class<?> paramType = paramTypes[argIndex];
            try {
                resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
            } catch (TypeMismatchException ex) {
                throw new UnsatisfiedDependencyException(
                        mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
                        "Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
                                "] to required type [" + paramType.getName() + "]: " + ex.getMessage());
            }
        }
        return resolvedArgs;
    }

    protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
        Class<?> declaringClass = constructor.getDeclaringClass();
        Class<?> userClass = ClassUtils.getUserClass(declaringClass);
        if (userClass != declaringClass) {
            try {
                return userClass.getDeclaredConstructor(constructor.getParameterTypes());
            } catch (NoSuchMethodException ex) {
                // No equivalent constructor on user class (superclass)...
                // Let's proceed with the given constructor as we usually would.
            }
        }
        return constructor;
    }

    /**
     * Template method for resolving the specified argument which is supposed to be autowired.
     */
    @Nullable
    protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
                                              @Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

        Class<?> paramType = param.getParameterType();
        if (InjectionPoint.class.isAssignableFrom(paramType)) {
            InjectionPoint injectionPoint = currentInjectionPoint.get();
            if (injectionPoint == null) {
                throw new IllegalStateException("No current InjectionPoint available for " + param);
            }
            return injectionPoint;
        }
        try {
            return this.beanFactory.resolveDependency(
                    new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
        } catch (NoUniqueBeanDefinitionException ex) {
            throw ex;
        } catch (NoSuchBeanDefinitionException ex) {
            if (fallback) {
                // Single constructor or factory method -> let's return an empty array/collection
                // for e.g. a vararg or a non-null List/Set/Map parameter.
                if (paramType.isArray()) {
                    return Array.newInstance(paramType.getComponentType(), 0);
                } else if (CollectionFactory.isApproximableCollectionType(paramType)) {
                    return CollectionFactory.createCollection(paramType, 0);
                } else if (CollectionFactory.isApproximableMapType(paramType)) {
                    return CollectionFactory.createMap(paramType, 0);
                }
            }
            throw ex;
        }
    }

    static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
        InjectionPoint old = currentInjectionPoint.get();
        if (injectionPoint != null) {
            currentInjectionPoint.set(injectionPoint);
        } else {
            currentInjectionPoint.remove();
        }
        return old;
    }


    /**
     * Private inner class for holding argument combinations.
     */
    private static class ArgumentsHolder {

        public final Object[] rawArguments;

        public final Object[] arguments;

        public final Object[] preparedArguments;

        public boolean resolveNecessary = false;

        public ArgumentsHolder(int size) {
            this.rawArguments = new Object[size];
            this.arguments = new Object[size];
            this.preparedArguments = new Object[size];
        }

        public ArgumentsHolder(Object[] args) {
            this.rawArguments = args;
            this.arguments = args;
            this.preparedArguments = args;
        }

        public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
            // If valid arguments found, determine type difference weight.
            // Try type difference weight on both the converted arguments and
            // the raw arguments. If the raw weight is better, use it.
            // Decrease raw weight by 1024 to prefer it over equal converted weight.
            int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
            int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
            return Math.min(rawTypeDiffWeight, typeDiffWeight);
        }

        public int getAssignabilityWeight(Class<?>[] paramTypes) {
            for (int i = 0; i < paramTypes.length; i++) {
                if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
                    return Integer.MAX_VALUE;
                }
            }
            for (int i = 0; i < paramTypes.length; i++) {
                if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
                    return Integer.MAX_VALUE - 512;
                }
            }
            return Integer.MAX_VALUE - 1024;
        }

        public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
            synchronized (mbd.constructorArgumentLock) {
                mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
                mbd.constructorArgumentsResolved = true;
                if (this.resolveNecessary) {
                    mbd.preparedConstructorArguments = this.preparedArguments;
                } else {
                    mbd.resolvedConstructorArguments = this.arguments;
                }
            }
        }
    }


    /**
     * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
     */
    private static class ConstructorPropertiesChecker {

        @Nullable
        public static String[] evaluate(Constructor<?> candidate, int paramCount) {
            ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
            if (cp != null) {
                String[] names = cp.value();
                if (names.length != paramCount) {
                    throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
                            "corresponding to actual number of parameters (" + paramCount + "): " + candidate);
                }
                return names;
            } else {
                return null;
            }
        }
    }

}
