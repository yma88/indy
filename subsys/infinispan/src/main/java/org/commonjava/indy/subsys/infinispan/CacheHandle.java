/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.subsys.infinispan;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.commonjava.indy.metrics.IndyMetricsManager;
import org.infinispan.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;
import static org.commonjava.indy.metrics.IndyMetricsConstants.METER;
import static org.commonjava.indy.metrics.IndyMetricsConstants.TIMER;

/**
 * Holder class that helps manage the shutdown process for things that use Infinispan.
 */
public class CacheHandle<K,V>
{
    private String name;

    private Cache<K,V> cache;

    private IndyMetricsManager metricsManager;

    private String metricPrefix;

    private boolean stopped;

    protected CacheHandle(){}

    public CacheHandle( String named, Cache<K, V> cache, IndyMetricsManager metricsManager, String metricPrefix )
    {
        this.name = named;
        this.cache = cache;
        this.metricsManager = metricsManager;
        this.metricPrefix = metricPrefix;
    }

    // useful for testing
    public CacheHandle( String named, Cache<K, V> cache )
    {
        this.name = named;
        this.cache = cache;
    }

    //FIXME: as new CacheProvider construction mechanism used a original cache to construct FastLocalCacheProvider,
    //       here we need to expose the wrapped cache out. Need to think some alternative way later to fix this.
    @Deprecated
    public Cache<K,V> getCache(){
        return cache;
    }

    public String getName()
    {
        return name;
    }

    public <R> R execute( Function<Cache<K, V>, R> operation )
    {
        if ( !stopped )
        {
            Timer.Context context = startMetrics( "execute" );

            try
            {
                return operation.apply( cache );
            }
            catch ( RuntimeException e )
            {
                // this may happen if the cache is in the process of shutting down
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.error( "Failed to complete operation: " + e.getMessage(), e );
            }
            finally
            {
                if ( context != null )
                {
                    context.stop();
                }
            }
        }
        else
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.error( "Cannot complete operation. Cache {} is shutting down.", name );
        }

        return null;
    }

    public void stop()
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Cache {} is shutting down!", name );
        this.stopped = true;
    }

    public boolean containsKey( K key )
    {
        return execute( cache -> !stopped && cache.containsKey( key ) );
    }

    public V put( K key, V value )
    {
        return execute( cache -> cache.put( key, value ) );
    }

    public V putIfAbsent( K key, V value )
    {
        return execute( ( c ) -> c.putIfAbsent( key, value ) );
    }

    public V computeIfAbsent( K key, Function<? super K, ? extends V> mappingFunction )
    {
        return execute( c -> c.computeIfAbsent( key, mappingFunction ) );
    }

    public V remove( K key )
    {
        return execute( cache -> cache.remove( key ) );
    }

    public V get( K key )
    {
        return execute( cache -> cache.get( key ) );
    }

    public void beginTransaction()
            throws NotSupportedException, SystemException
    {
        Timer.Context context = startMetrics( "beginTransaction" );

        try
        {
            AtomicReference<NotSupportedException> suppEx = new AtomicReference<>();
            AtomicReference<SystemException> sysEx = new AtomicReference<>();
            execute( ( c ) -> {
                try
                {
                    c.getAdvancedCache().getTransactionManager().begin();
                }
                catch ( NotSupportedException e )
                {
                    suppEx.set( e );
                }
                catch ( SystemException e )
                {
                    sysEx.set( e );
                }

                return null;
            } );

            if ( suppEx.get() != null )
            {
                throw suppEx.get();
            }

            if ( sysEx.get() != null )
            {
                throw sysEx.get();
            }
        }
        finally
        {
            if ( context != null )
            {
                context.stop();
            }
        }
    }

    public void rollback()
            throws SystemException
    {
        Timer.Context context = startMetrics( "rollback" );

        try
        {
            AtomicReference<SystemException> sysEx = new AtomicReference<>();
            execute( ( c ) -> {
                try
                {
                    c.getAdvancedCache().getTransactionManager().rollback();
                }
                catch ( SystemException e )
                {
                    sysEx.set( e );
                }

                return null;
            } );


            if ( sysEx.get() != null )
            {
                throw sysEx.get();
            }
        }
        finally
        {
            if ( context != null )
            {
                context.stop();
            }
        }
    }

    private Timer.Context startMetrics( String name )
    {
        if ( metricsManager != null )
        {
            metricsManager.getMeter( name( metricPrefix, name, METER ) ).mark();
            return metricsManager.getTimer( name( metricPrefix, name, TIMER ) ).time();
        }

        return null;
    }

    public void commit()
            throws SystemException, HeuristicMixedException, HeuristicRollbackException, RollbackException
    {
        Timer.Context context = startMetrics( "commit" );
        try
        {
            AtomicReference<SystemException> sysEx = new AtomicReference<>();
            AtomicReference<HeuristicMixedException> hmEx = new AtomicReference<>();
            AtomicReference<HeuristicRollbackException> hrEx = new AtomicReference<>();
            AtomicReference<RollbackException> rEx = new AtomicReference<>();
            execute( ( c ) -> {
                try
                {
                    c.getAdvancedCache().getTransactionManager().commit();
                }
                catch ( SystemException e )
                {
                    sysEx.set( e );
                }
                catch ( HeuristicMixedException e )
                {
                    hmEx.set( e );
                }
                catch ( HeuristicRollbackException e )
                {
                    hrEx.set( e );
                }
                catch ( RollbackException e )
                {
                    rEx.set( e );
                }

                return null;
            } );

            if ( sysEx.get() != null )
            {
                throw sysEx.get();
            }

            if ( hmEx.get() != null )
            {
                throw hmEx.get();
            }

            if ( hrEx.get() != null )
            {
                throw hrEx.get();
            }

            if ( rEx.get() != null )
            {
                throw rEx.get();
            }
        }
        finally
        {
            if ( context != null )
            {
                context.stop();
            }
        }
    }

    public int getTransactionStatus()
            throws SystemException
    {
        AtomicReference<SystemException> sysEx = new AtomicReference<>();

        Integer result = execute( ( c ) -> {
            try
            {
                return c.getAdvancedCache().getTransactionManager().getStatus();
            }
            catch ( SystemException e )
            {
                sysEx.set( e );
            }

            return null;
        } );

        if ( sysEx.get() != null )
        {
            throw sysEx.get();
        }

        return result;
    }

    public Object getLockOwner( K key )
    {
        return execute( ( c ) -> c.getAdvancedCache().getLockManager().getOwner( key ) );
    }

    public boolean isLocked( K key )
    {
        return execute( ( c ) -> c.getAdvancedCache().getLockManager().isLocked( key ) );
    }

    public void lock( K... keys )
    {
        execute( ( c ) -> c.getAdvancedCache().lock( keys ) );
    }

    public Set<K> cacheKeySetByFilter( Predicate<K> filter )
    {
        return this.cache.keySet().stream().filter( filter ).collect( Collectors.toSet() );
    }

    public boolean isEmpty(){
        return execute( c->c.isEmpty() );
    }

}
