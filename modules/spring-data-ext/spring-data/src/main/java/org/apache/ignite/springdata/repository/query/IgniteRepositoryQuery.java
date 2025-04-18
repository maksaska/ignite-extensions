/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.springdata.repository.query;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.cache.Cache;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.reflect.FieldUtils;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.query.FieldsQueryCursor;
import org.apache.ignite.cache.query.Query;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.SqlQuery;
import org.apache.ignite.cache.query.TextQuery;
import org.apache.ignite.internal.util.GridUnsafe;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.springdata.proxy.IgniteCacheProxy;
import org.apache.ignite.springdata.proxy.IgniteClientCacheProxy;
import org.apache.ignite.springdata.repository.config.DynamicQueryConfig;
import org.apache.ignite.springdata.repository.query.StringQuery.ParameterBinding;
import org.apache.ignite.springdata.repository.query.StringQuery.ParameterBindingParser;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.Parameter;
import org.springframework.data.repository.query.Parameters;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.QueryMethodEvaluationContextProvider;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

import static org.apache.ignite.internal.processors.query.QueryUtils.KEY_FIELD_NAME;
import static org.apache.ignite.internal.processors.query.QueryUtils.VAL_FIELD_NAME;
import static org.apache.ignite.springdata.repository.support.IgniteRepositoryFactory.isFieldQuery;

/**
 * Ignite query implementation.
 * <p>
 * <p>
 * Features:
 * <ol>
 * <li> Supports query tuning parameters</li>
 * <li> Supports projections</li>
 * <li> Supports Page and Stream responses</li>
 * <li> Supports SqlFieldsQuery resultset transformation into the domain entity</li>
 * <li> Supports named parameters (:myParam) into SQL queries, declared using @Param("myParam") annotation</li>
 * <li> Supports advanced parameter binding and SpEL expressions into SQL queries
 * <ol>
 * <li><b>Template variables</b>:
 * <ol>
 * <li>{@code #entityName} - the simple class name of the domain entity</li>
 * </ol>
 * </li>
 * <li><b>Method parameter expressions</b>: Parameters are exposed for indexed access ([0] is the first query method's
 * param) or via the name declared using @Param. The actual SpEL expression binding is triggered by '?#'. Example:
 * ?#{[0]} or ?#{#myParamName}</li>
 * <li><b>Advanced SpEL expressions</b>: While advanced parameter binding is a very useful feature, the real power of
 * SpEL stems from the fact, that the expressions can refer to framework abstractions or other application components
 * through SpEL EvaluationContext extension model.</li>
 * </ol>
 * Examples:
 * <pre>
 * {@code @Query}(value = "SELECT * from #{#entityName} where email = :email")
 * User searchUserByEmail({@code @Param}("email") String email);
 *
 * {@code @Query}(value = "SELECT * from #{#entityName} where country = ?#{[0]} and city = ?#{[1]}")
 * List<User> searchUsersByCity({@code @Param}("country") String country, {@code @Param}("city") String city,
 * Pageable pageable);
 *
 * {@code @Query}(value = "SELECT * from #{#entityName} where email = ?")
 * User searchUserByEmail(String email);
 *
 * {@code @Query}(value = "SELECT * from #{#entityName} where lucene = ?#{
 * luceneQueryBuilder.search().refresh(true).filter(luceneQueryBuilder.match('city',#city)).build()}")
 * List<User> searchUsersByCity({@code @Param}("city") String city, Pageable pageable);
 * </pre>
 * </li>
 * <li> Supports SpEL expressions into Text queries ({@link TextQuery}). Examples:
 * <pre>
 * {@code @Query}(textQuery = true, value = "email: #{#email}")
 * User searchUserByEmail({@code @Param}("email") String email);
 *
 * {@code @Query}(textQuery = true, value = "#{#textToSearch}")
 * List<User> searchUsersByText({@code @Param}("textToSearch") String text, Pageable pageable);
 *
 * {@code @Query}(textQuery = true, value = "#{[0]}")
 * List<User> searchUsersByText(String textToSearch, Pageable pageable);
 *
 * {@code @Query}(textQuery = true, value = "#{luceneQueryBuilder.search().refresh(true).filter(luceneQueryBuilder
 * .match('city', #city)).build()}")
 * List<User> searchUserByCity({@code @Param}("city") String city, Pageable pageable);
 * </pre>
 * </li>
 * <li> Supports dynamic query and tuning at runtime by using {@link DynamicQueryConfig} method parameter. Examples:
 * <pre>
 * {@code @Query}(value = "SELECT * from #{#entityName} where email = :email")
 * User searchUserByEmailWithQueryTuning({@code @Param}("email") String email,
 *     {@code @Param}("ignoredUsedAsQueryTuning") DynamicQueryConfig config);
 *
 * {@code @Query}(dynamicQuery = true)
 * List<User> searchUsersByCityWithDynamicQuery({@code @Param}("country") String country, {@code @Param}("city") String city,
 * {@code @Param}("ignoredUsedAsDynamicQueryAndTuning") DynamicQueryConfig config, Pageable pageable);
 *
 * ...
 * DynamicQueryConfig onlyTunning = new DynamicQueryConfig().setCollocated(true);
 * repo.searchUserByEmailWithQueryTuning("user@mail.com", onlyTunning);
 *
 * DynamicQueryConfig withDynamicQuery = new DynamicQueryConfig().value("SELECT * from #{#entityName} where
 *     country = ?#{[0] and city = ?#{[1]}").setForceFieldsQuery(true).setLazy(true).setCollocated(true);
 * repo.searchUsersByCityWithDynamicQuery("Spain", "Madrid", withDynamicQuery, new PageRequest(0, 100));
 *
 * </pre>
 * </li>
 * </ol>
 *
 * @author Apache Ignite Team
 * @author Manuel Núñez (manuel.nunez@hawkore.com)
 */
@SuppressWarnings("unchecked")
public class IgniteRepositoryQuery implements RepositoryQuery {
    /**
     * Defines the way how to process query result
     */
    private enum ReturnStrategy {
        /** Need to return only one value. */
        ONE_VALUE,

        /** Need to return one cache entry */
        CACHE_ENTRY,

        /** Need to return list of cache entries */
        LIST_OF_CACHE_ENTRIES,

        /** Need to return list of values */
        LIST_OF_VALUES,

        /** Need to return list of lists */
        LIST_OF_LISTS,

        /** Need to return slice */
        SLICE_OF_VALUES,

        /** Slice of cache entries */
        SLICE_OF_CACHE_ENTRIES,

        /** Slice of lists */
        SLICE_OF_LISTS,

        /** Need to return Page of values */
        PAGE_OF_VALUES,

        /** Need to return stream of values */
        STREAM_OF_VALUES,
    }

    /** */
    private final Class<?> type;

    /** Domain entitiy field descriptors mapped to their names in lower case. */
    private final Map<String, Field> domainEntitiyFields;

    /** */
    private final IgniteQuery staticQuery;

    /** */
    private final IgniteCacheProxy<?, ?> cache;

    /** */
    private final Method mtd;

    /** */
    private final RepositoryMetadata metadata;

    /** */
    private final ProjectionFactory factory;

    /** */
    private final ReturnStrategy staticReturnStgy;

    /** Detect if returned data from method is projected */
    private final boolean hasProjection;

    /** Whether projection is dynamic (provided as method parameter) */
    private final boolean hasDynamicProjection;

    /** Dynamic projection parameter index */
    private final int dynamicProjectionIndex;

    /** Dynamic query configuration */
    private final int dynamicQueryConfigurationIndex;

    /** The return query method */
    private final QueryMethod qMethod;

    /** The return domain class of QueryMethod */
    private final Class<?> returnedDomainClass;

    /** */
    private final SpelExpressionParser expressionParser;

    /** Could provide ExtensionAwareQueryMethodEvaluationContextProvider */
    private final QueryMethodEvaluationContextProvider queryMethodEvaluationContextProvider;

    /** Static query configuration. */
    private final DynamicQueryConfig staticQueryConfiguration;

    /** Conversion service. */
    private final ConversionService conversionService;

    /**
     * Instantiates a new Ignite repository query.
     *
     * @param metadata                             Metadata.
     * @param staticQuery                          Query.
     * @param mtd                                  Method.
     * @param factory                              Factory.
     * @param cache                                Cache.
     * @param staticQueryConfiguration             the query configuration
     * @param queryMethodEvaluationContextProvider the query method evaluation context provider
     * @param conversionService                    Conversion service.
     */
    public IgniteRepositoryQuery(
        RepositoryMetadata metadata,
        @Nullable IgniteQuery staticQuery,
        Method mtd,
        ProjectionFactory factory,
        IgniteCacheProxy<?, ?> cache,
        @Nullable DynamicQueryConfig staticQueryConfiguration,
        QueryMethodEvaluationContextProvider queryMethodEvaluationContextProvider,
        ConversionService conversionService) {
        this.metadata = metadata;
        this.mtd = mtd;
        this.factory = factory;
        type = metadata.getDomainType();

        domainEntitiyFields = Arrays.stream(type.getDeclaredFields())
            .collect(Collectors.toMap(field -> field.getName().toLowerCase(), field -> field));

        this.cache = cache;

        this.staticQueryConfiguration = staticQueryConfiguration;
        this.staticQuery = staticQuery;

        if (this.staticQuery != null)
            staticReturnStgy = calcReturnType(mtd, this.staticQuery.isFieldQuery());
        else
            staticReturnStgy = null;

        expressionParser = new SpelExpressionParser();
        this.queryMethodEvaluationContextProvider = queryMethodEvaluationContextProvider;
        this.conversionService = conversionService;

        qMethod = getQueryMethod();

        // control projection
        hasDynamicProjection = getQueryMethod().getParameters().hasDynamicProjection();
        hasProjection = hasDynamicProjection || getQueryMethod().getResultProcessor().getReturnedType()
            .isProjecting();

        dynamicProjectionIndex = qMethod.getParameters().getDynamicProjectionIndex();

        returnedDomainClass = getQueryMethod().getReturnedObjectType();

        dynamicQueryConfigurationIndex = getDynamicQueryConfigurationIndex(qMethod);

        // ensure dynamic query configuration param exists if dynamicQuery = true
        if (dynamicQueryConfigurationIndex == -1 && this.staticQuery == null) {
            throw new IllegalStateException(
                "When passing dynamicQuery = true via org.apache.ignite.springdata.repository.config.Query "
                    + "annotation, you must provide a non null method parameter of type DynamicQueryConfig");
        }
    }

    /**
     * {@inheritDoc} @param val the val
     *
     * @return the object
     */
    @Override public Object execute(Object[] val) {
        Object[] parameters = val;

        // cfg via Query annotation (dynamicQuery = false)
        DynamicQueryConfig cfg = staticQueryConfiguration;

        // or condition to allow query tunning
        if (cfg == null || dynamicQueryConfigurationIndex != -1) {
            DynamicQueryConfig newCfg = (DynamicQueryConfig)val[dynamicQueryConfigurationIndex];
            parameters = ArrayUtils.removeElement(parameters, dynamicQueryConfigurationIndex);
            if (newCfg != null) {
                // upset query configuration
                cfg = newCfg;
            }
        }
        // query configuration is required, via Query annotation or per parameter (within provided val param)
        if (cfg == null) {
            throw new IllegalStateException(
                "Unable to execute query. When passing dynamicQuery = true via org.apache.ignite.springdata"
                    + ".repository.cfg.Query annotation, you must provide a non null method parameter of type "
                    + "DynamicQueryConfig");
        }

        IgniteQuery qry = getQuery(cfg);

        ReturnStrategy returnStgy = getReturnStgy(qry);

        Query iQry = prepareQuery(qry, cfg, returnStgy, parameters);

        QueryCursor qryCursor;

        try {
            qryCursor = cache.query(iQry);
        }
        catch (IllegalArgumentException e) {
            if (cache instanceof IgniteClientCacheProxy) {
                throw new IllegalStateException(String.format("Query of type %s is not supported by thin client." +
                    " Check %s#%s method configuration or use Ignite node instance to connect to the Ignite cluster.",
                    iQry.getClass().getSimpleName(), mtd.getDeclaringClass().getName(), mtd.getName()), e);
            }

            throw e;
        }

        return transformQueryCursor(qry, returnStgy, parameters, qryCursor);
    }

    /** {@inheritDoc} */
    @Override public QueryMethod getQueryMethod() {
        return new QueryMethod(mtd, metadata, factory);
    }

    /** */
    private <T extends Parameter> int getDynamicQueryConfigurationIndex(QueryMethod method) {
        Iterator<T> it = (Iterator<T>)method.getParameters().iterator();
        int i = 0;
        boolean found = false;
        int idx = -1;
        while (it.hasNext()) {
            T param = it.next();

            if (DynamicQueryConfig.class.isAssignableFrom(param.getType())) {
                if (found) {
                    throw new IllegalStateException("Invalid '" + method.getName() + "' repository method signature. "
                        + "Only ONE DynamicQueryConfig parameter is allowed");
                }

                found = true;
                idx = i;
            }

            i++;
        }
        return idx;
    }

    /**
     * @param mtd Method.
     * @param isFieldQry Is field query.
     * @return Return strategy type.
     */
    private ReturnStrategy calcReturnType(Method mtd, boolean isFieldQry) {
        Class<?> returnType = mtd.getReturnType();

        if (returnType == Slice.class) {
            if (isFieldQry) {
                if (hasAssignableGenericReturnTypeFrom(ArrayList.class, mtd))
                    return ReturnStrategy.SLICE_OF_LISTS;
            }
            else if (hasAssignableGenericReturnTypeFrom(Cache.Entry.class, mtd))
                return ReturnStrategy.SLICE_OF_CACHE_ENTRIES;
            return ReturnStrategy.SLICE_OF_VALUES;
        }
        else if (returnType == Page.class)
            return ReturnStrategy.PAGE_OF_VALUES;
        else if (returnType == Stream.class)
            return ReturnStrategy.STREAM_OF_VALUES;
        else if (Cache.Entry.class.isAssignableFrom(returnType))
            return ReturnStrategy.CACHE_ENTRY;
        else if (Iterable.class.isAssignableFrom(returnType)) {
            if (isFieldQry) {
                if (hasAssignableGenericReturnTypeFrom(ArrayList.class, mtd))
                    return ReturnStrategy.LIST_OF_LISTS;
            }
            else if (hasAssignableGenericReturnTypeFrom(Cache.Entry.class, mtd))
                return ReturnStrategy.LIST_OF_CACHE_ENTRIES;
            return ReturnStrategy.LIST_OF_VALUES;
        }
        else
            return ReturnStrategy.ONE_VALUE;
    }

    /**
     * @param cls Class.
     * @param mtd Method.
     * @return if {@code mtd} return type is assignable from {@code cls}
     */
    private boolean hasAssignableGenericReturnTypeFrom(Class<?> cls, Method mtd) {
        Type genericReturnType = mtd.getGenericReturnType();

        if (!(genericReturnType instanceof ParameterizedType))
            return false;

        Type[] actualTypeArgs = ((ParameterizedType)genericReturnType).getActualTypeArguments();

        if (actualTypeArgs.length == 0)
            return false;

        if (actualTypeArgs[0] instanceof ParameterizedType) {
            ParameterizedType type = (ParameterizedType)actualTypeArgs[0];

            Class<?> type1 = (Class)type.getRawType();

            return type1.isAssignableFrom(cls);
        }

        if (actualTypeArgs[0] instanceof Class) {
            Class typeArg = (Class)actualTypeArgs[0];

            return typeArg.isAssignableFrom(cls);
        }

        return false;
    }

    /**
     * @param cfg Config.
     */
    private IgniteQuery getQuery(@Nullable DynamicQueryConfig cfg) {
        if (staticQuery != null)
            return staticQuery;

        if (cfg != null && (StringUtils.hasText(cfg.value()) || cfg.textQuery())) {
            return new IgniteQuery(
                cfg.value(),
                !cfg.textQuery() && (isFieldQuery(cfg.value()) || cfg.forceFieldsQuery()),
                cfg.textQuery(),
                false,
                true,
                IgniteQueryGenerator.getOptions(mtd)
            );
        }

        throw new IllegalStateException("Unable to obtain a valid query. When passing dynamicQuery = true via org"
            + ".apache.ignite.springdata.repository.config.Query annotation, you must"
            + " provide a non null method parameter of type DynamicQueryConfig with a "
            + "non empty value (query string) or textQuery = true");
    }

    /**
     * @param qry Query.
     */
    private ReturnStrategy getReturnStgy(IgniteQuery qry) {
        if (staticReturnStgy != null)
            return staticReturnStgy;

        if (qry != null)
            return calcReturnType(mtd, qry.isFieldQuery());

        throw new IllegalStateException("Unable to obtain a valid return strategy. When passing dynamicQuery = true "
            + "via org.apache.ignite.springdata.repository.config.Query annotation, "
            + "you must provide a non null method parameter of type "
            + "DynamicQueryConfig with a non empty value (query string) or textQuery "
            + "= true");
    }

    /**
     * @param cls Class.
     */
    private static boolean isPrimitiveOrWrapper(Class<?> cls) {
        return cls.isPrimitive() ||
            Boolean.class.equals(cls) ||
            Byte.class.equals(cls) ||
            Character.class.equals(cls) ||
            Short.class.equals(cls) ||
            Integer.class.equals(cls) ||
            Long.class.equals(cls) ||
            Float.class.equals(cls) ||
            Double.class.equals(cls) ||
            Void.class.equals(cls) ||
            String.class.equals(cls) ||
            UUID.class.equals(cls);
    }

    /**
     * @param prmtrs    Prmtrs.
     * @param qryCursor Query cursor.
     * @return Query cursor or slice
     */
    @Nullable
    private Object transformQueryCursor(IgniteQuery qry,
        ReturnStrategy returnStgy,
        Object[] prmtrs,
        QueryCursor qryCursor) {
        final Class<?> retCls;

        if (hasProjection) {
            if (hasDynamicProjection)
                retCls = (Class<?>)prmtrs[dynamicProjectionIndex];
            else
                retCls = returnedDomainClass;
        }
        else
            retCls = returnedDomainClass;

        if (qry.isFieldQuery()) {
            // take control over single primite result from queries, i.e. DELETE, SELECT COUNT, UPDATE ...
            boolean singlePrimitiveResult = isPrimitiveOrWrapper(retCls);

            FieldsQueryCursor<?> fieldQryCur = (FieldsQueryCursor<?>)qryCursor;

            Function<List<?>, ?> cWrapperTransformFunction = null;

            if (type.equals(retCls))
                cWrapperTransformFunction = row -> rowToEntity(row, fieldQryCur);
            else {
                if (hasProjection || singlePrimitiveResult) {
                    if (singlePrimitiveResult)
                        cWrapperTransformFunction = row -> row.get(0);
                    else {
                        // Map row -> projection class
                        cWrapperTransformFunction = row -> factory
                            .createProjection(retCls, rowToMap(row, fieldQryCur));
                    }
                }
                else
                    cWrapperTransformFunction = row -> rowToMap(row, fieldQryCur);
            }

            QueryCursorWrapper<?, ?> cWrapper = new QueryCursorWrapper<>((QueryCursor<List<?>>)qryCursor,
                cWrapperTransformFunction);

            switch (returnStgy) {
                case PAGE_OF_VALUES:
                    return new PageImpl(cWrapper.getAll(), (Pageable)prmtrs[prmtrs.length - 1], 0);
                case LIST_OF_VALUES:
                    return cWrapper.getAll();
                case STREAM_OF_VALUES:
                    return cWrapper.stream();
                case ONE_VALUE:
                    Iterator<?> iter = cWrapper.iterator();
                    if (iter.hasNext()) {
                        Object resp = iter.next();
                        U.closeQuiet(cWrapper);
                        return resp;
                    }
                    return null;
                case SLICE_OF_VALUES:
                    return new SliceImpl(cWrapper.getAll(), (Pageable)prmtrs[prmtrs.length - 1], true);
                case SLICE_OF_LISTS:
                    return new SliceImpl(qryCursor.getAll(), (Pageable)prmtrs[prmtrs.length - 1], true);
                case LIST_OF_LISTS:
                    return qryCursor.getAll();
                default:
                    throw new IllegalStateException();
            }
        }
        else {
            Function<Cache.Entry<?, ?>, ?> cWrapperTransformFunction;

            if (hasProjection && !type.equals(retCls))
                cWrapperTransformFunction = row -> factory.createProjection(retCls, row.getValue());
            else
                cWrapperTransformFunction = Cache.Entry::getValue;

            QueryCursorWrapper<Cache.Entry<?, ?>, ?> cWrapper = new QueryCursorWrapper<>(
                (QueryCursor<Cache.Entry<?, ?>>)qryCursor, cWrapperTransformFunction);

            switch (returnStgy) {
                case PAGE_OF_VALUES:
                    return new PageImpl(cWrapper.getAll(), (Pageable)prmtrs[prmtrs.length - 1], 0);
                case LIST_OF_VALUES:
                    return cWrapper.getAll();
                case STREAM_OF_VALUES:
                    return cWrapper.stream();
                case ONE_VALUE:
                    Iterator<?> iter1 = cWrapper.iterator();
                    if (iter1.hasNext()) {
                        Object resp = iter1.next();
                        U.closeQuiet(cWrapper);
                        return resp;
                    }
                    return null;
                case CACHE_ENTRY:
                    Iterator<?> iter2 = qryCursor.iterator();
                    if (iter2.hasNext()) {
                        Object resp2 = iter2.next();
                        U.closeQuiet(qryCursor);
                        return resp2;
                    }
                    return null;
                case SLICE_OF_VALUES:
                    return new SliceImpl(cWrapper.getAll(), (Pageable)prmtrs[prmtrs.length - 1], true);
                case SLICE_OF_CACHE_ENTRIES:
                    return new SliceImpl(qryCursor.getAll(), (Pageable)prmtrs[prmtrs.length - 1], true);
                case LIST_OF_CACHE_ENTRIES:
                    return qryCursor.getAll();
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * Extract bindable values
     *
     * @param values            values invoking query method
     * @param queryMethodParams query method parameter definitions
     * @param queryBindings     All parameters found on query string that need to be binded
     * @return new list of parameters
     */
    private Object[] extractBindableValues(Object[] values,
        Parameters<?, ?> queryMethodParams,
        List<ParameterBinding> queryBindings) {
        // no binding params then exit
        if (queryBindings.isEmpty())
            return values;

        Object[] newValues = new Object[queryBindings.size()];

        // map bindable parameters from query method: (index/name) - index
        HashMap<String, Integer> methodParams = new HashMap<>();

        // create an evaluation context for custom query
        EvaluationContext qryEvalCtx = queryMethodEvaluationContextProvider
            .getEvaluationContext(queryMethodParams, values);

        // By default qryEvalCtx:
        // - make accesible query method parameters by index:
        // @Query("select u from User u where u.age = ?#{[0]}")
        // List<User> findUsersByAge(int age);
        // - make accesible query method parameters by name:
        // @Query("select u from User u where u.firstname = ?#{#customer.firstname}")
        // List<User> findUsersByCustomersFirstname(@Param("customer") Customer customer);

        // query method param's index by name and position
        queryMethodParams.getBindableParameters().forEach(p -> {
            if (p.isNamedParameter()) {
                // map by name (annotated by @Param)
                methodParams.put(p.getName().get(), p.getIndex());
            }
            // map by position
            methodParams.put(String.valueOf(p.getIndex()), p.getIndex());
        });

        // process all parameters on query and extract new values to bind
        for (int i = 0; i < queryBindings.size(); i++) {
            ParameterBinding p = queryBindings.get(i);

            if (p.isExpression()) {
                // Evaluate SpEl expressions (synthetic parameter value) , example ?#{#customer.firstname}
                newValues[i] = expressionParser.parseExpression(p.getExpression()).getValue(qryEvalCtx);
            }
            else {
                // Extract parameter value by name or position respectively from invoking values
                newValues[i] = values[methodParams.get(
                    p.getName() != null ? p.getName() : String.valueOf(p.getRequiredPosition() - 1))];
            }
        }

        return newValues;
    }

    /**
     * @param qry        Query.
     * @param config     Config.
     * @param returnStgy Return stgy.
     * @param values     Values.
     * @return prepared query for execution
     */
    private Query prepareQuery(IgniteQuery qry, DynamicQueryConfig config, ReturnStrategy returnStgy, Object[] values) {
        Object[] parameters = values;

        String qryStr = qry.qryStr();

        Query gry;

        checkRequiredPageable(returnStgy, values);

        if (!qry.isTextQuery()) {
            boolean isParamDependent;

            if (!qry.isAutogenerated()) {
                StringQuery squery = new ExpressionBasedStringQuery(qryStr, metadata, expressionParser);
                qryStr = squery.getQueryString();
                parameters = extractBindableValues(parameters, getQueryMethod().getParameters(),
                    squery.getParameterBindings());

                isParamDependent = isParameterDependent(squery);
            }
            else {
                // remove dynamic projection from parameters
                if (hasDynamicProjection)
                    parameters = ArrayUtils.remove(parameters, dynamicProjectionIndex);

                isParamDependent = qry.isParameterDependent();
            }

            switch (qry.options()) {
                case SORTING:
                    qryStr = IgniteQueryGenerator
                        .addSorting(new StringBuilder(qryStr), (Sort)values[values.length - 1])
                        .toString();
                    if (qry.isAutogenerated())
                        parameters = Arrays.copyOfRange(parameters, 0, values.length - 1);
                    break;
                case PAGINATION:
                    qryStr = IgniteQueryGenerator
                        .addPaging(new StringBuilder(qryStr), (Pageable)values[values.length - 1])
                        .toString();
                    if (qry.isAutogenerated())
                        parameters = Arrays.copyOfRange(parameters, 0, values.length - 1);
                    break;
                default:
            }

            if (isParamDependent) {
                T2<String, Object[]> parseRes = ParameterBindingParser.INSTANCE.processParameterDependentClauses(
                    qryStr,
                    parameters
                );

                qryStr = parseRes.get1();

                parameters = parseRes.get2();
            }

            if (qry.isFieldQuery()) {
                SqlFieldsQuery sqlFieldsQry = new SqlFieldsQuery(qryStr);
                sqlFieldsQry.setArgs(parameters);

                sqlFieldsQry.setCollocated(config.collocated());
                sqlFieldsQry.setDistributedJoins(config.distributedJoins());
                sqlFieldsQry.setEnforceJoinOrder(config.enforceJoinOrder());
                sqlFieldsQry.setLazy(config.lazy());
                sqlFieldsQry.setLocal(config.local());

                if (config.parts() != null && config.parts().length > 0)
                    sqlFieldsQry.setPartitions(config.parts());

                sqlFieldsQry.setTimeout(config.timeout(), TimeUnit.MILLISECONDS);

                gry = sqlFieldsQry;
            }
            else {
                SqlQuery sqlQry = new SqlQuery(type, qryStr);
                sqlQry.setArgs(parameters);

                sqlQry.setDistributedJoins(config.distributedJoins());
                sqlQry.setLocal(config.local());

                if (config.parts() != null && config.parts().length > 0)
                    sqlQry.setPartitions(config.parts());

                sqlQry.setTimeout(config.timeout(), TimeUnit.MILLISECONDS);

                gry = sqlQry;
            }
        }
        else {
            int pageSize = -1;

            switch (qry.options()) {
                case PAGINATION:
                    pageSize = ((Pageable)parameters[parameters.length - 1]).getPageSize();
                    break;
            }

            // check if qryStr contains SpEL template expressions and evaluate them if any
            if (qryStr.contains("#{")) {
                EvaluationContext qryEvalCtx = queryMethodEvaluationContextProvider
                    .getEvaluationContext(getQueryMethod().getParameters(),
                        values);

                Object eval = expressionParser.parseExpression(qryStr, ParserContext.TEMPLATE_EXPRESSION)
                    .getValue(qryEvalCtx);

                if (!(eval instanceof String)) {
                    throw new IllegalStateException(
                        "TextQuery with SpEL expressions must produce a String response, but found " + eval.getClass()
                            .getName()
                            + ". Please, check your expression: " + qryStr);
                }
                qryStr = (String)eval;
            }

            TextQuery textQry = new TextQuery(type, qryStr, config.limit());

            textQry.setLocal(config.local());

            if (pageSize > -1)
                textQry.setPageSize(pageSize);

            gry = textQry;
        }
        return gry;
    }

    /**
     * @param row SQL result row.
     * @param cursor SQL query result cursor through which {@param row} was obtained.
     * @return SQL result row values mapped to corresponding column names.
     */
    private static Map<String, Object> rowToMap(final List<?> row, FieldsQueryCursor<?> cursor) {
        // use treemap with case insensitive property name
        final TreeMap<String, Object> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < row.size(); i++) {
            // don't want key or val columns
            final String fieldName = cursor.getFieldName(i).toLowerCase();

            if (!KEY_FIELD_NAME.equalsIgnoreCase(fieldName) && !VAL_FIELD_NAME.equalsIgnoreCase(fieldName))
                map.put(fieldName, row.get(i));
        }

        return map;
    }

    /**
     * convert row ( with list of field values) into domain entity
     *
     * @param row SQL query result row.
     * @param cursor SQL query result cursor through which {@param row} was obtained.
     * @return Entitiy instance.
     */
    private <V> V rowToEntity(List<?> row, FieldsQueryCursor<?> cursor) {
        Constructor<?> ctor;

        try {
            ctor = type.getDeclaredConstructor();

            ctor.setAccessible(true);
        }
        catch (NoSuchMethodException | SecurityException ignored) {
            ctor = null;
        }

        try {
            Object res = ctor == null ? GridUnsafe.allocateInstance(type) : ctor.newInstance();

            for (int i = 0; i < row.size(); i++) {
                Field entityField = domainEntitiyFields.get(cursor.getFieldName(i).toLowerCase());

                if (entityField != null)
                    FieldUtils.writeField(entityField, res, convert(row.get(i), entityField.getType()), true);
            }

            return (V)res;
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IgniteException("Unable to allocate instance of domain entity class " + type.getName(), e);
        }
    }

    /**
     * @param source Source object.
     * @param targetType Target type.
     * @return Converted object.
     */
    private <T> T convert(Object source, Class<?> targetType) {
        if (conversionService.canConvert(source.getClass(), targetType))
            return (T)conversionService.convert(source, targetType);

        return (T)source;
    }

    /**
     * Validates operations that requires Pageable parameter
     *
     * @param returnStgy Return stgy.
     * @param prmtrs     Prmtrs.
     */
    private void checkRequiredPageable(ReturnStrategy returnStgy, Object[] prmtrs) {
        try {
            if (returnStgy == ReturnStrategy.PAGE_OF_VALUES || returnStgy == ReturnStrategy.SLICE_OF_VALUES
                || returnStgy == ReturnStrategy.SLICE_OF_CACHE_ENTRIES) {
                Pageable page = (Pageable)prmtrs[prmtrs.length - 1];
                page.isPaged();
            }
        }
        catch (NullPointerException | IndexOutOfBoundsException | ClassCastException e) {
            throw new IllegalStateException(
                "For " + returnStgy.name() + " you must provide on last method parameter a non null Pageable instance");
        }
    }

    /**
     * Ignite QueryCursor wrapper.
     * <p>
     * Ensures closing underline cursor when there is no data.
     *
     * @param <T> input type
     * @param <V> transformed output type
     */
    public static class QueryCursorWrapper<T, V> extends AbstractCollection<V> implements QueryCursor<V> {
        /**
         * Delegate query cursor.
         */
        private final QueryCursor<T> delegate;

        /**
         * Transformer.
         */
        private final Function<T, V> transformer;

        /**
         * Instantiates a new Query cursor wrapper.
         *
         * @param delegate    delegate QueryCursor with T input elements
         * @param transformer Function to transform T to V elements
         */
        public QueryCursorWrapper(final QueryCursor<T> delegate, final Function<T, V> transformer) {
            this.delegate = delegate;
            this.transformer = transformer;
        }

        /** {@inheritDoc} */
        @Override public Iterator<V> iterator() {
            final Iterator<T> it = delegate.iterator();

            return new Iterator<V>() {
                /** */
                @Override public boolean hasNext() {
                    if (!it.hasNext()) {
                        U.closeQuiet(delegate);
                        return false;
                    }
                    return true;
                }

                /** */
                @Override public V next() {
                    final V r = transformer.apply(it.next());
                    if (r != null)
                        return r;
                    throw new NoSuchElementException();
                }
            };
        }

        /** {@inheritDoc} */
        @Override public void close() {
            U.closeQuiet(delegate);
        }

        /** {@inheritDoc} */
        @Override public List<V> getAll() {
            final List<V> data = new ArrayList<>();
            delegate.forEach(i -> data.add(transformer.apply(i)));
            U.closeQuiet(delegate);
            return data;
        }

        /** {@inheritDoc} */
        @Override public int size() {
            return 0;
        }
    }

    /**
     * @return Whether specified query contains clauses which string representation depends on the query arguments.
     */
    private boolean isParameterDependent(StringQuery qry) {
        for (ParameterBinding binding : qry.getParameterBindings()) {
            if (binding instanceof StringQuery.InParameterBinding)
                return true;
        }

        return false;
    }
}
