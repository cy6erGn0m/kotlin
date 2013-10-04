/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.storage;

import com.intellij.openapi.util.Computable;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.containers.ConcurrentWeakValueHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.utils.ExceptionUtils;
import org.jetbrains.jet.utils.WrappedValues;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LockBasedStorageManager implements StorageManager {

    public static final StorageManager NO_LOCKS = new LockBasedStorageManager(NoLock.INSTANCE) {
        @Override
        public String toString() {
            return "NO_LOCKS";
        }
    };

    protected final Lock lock;

    public LockBasedStorageManager() {
        this(new ReentrantLock());
    }

    private LockBasedStorageManager(@NotNull Lock lock) {
        this.lock = lock;
    }

    @NotNull
    @Override
    public <K, V> MemoizedFunctionToNotNull<K, V> createMemoizedFunction(
            @NotNull Function<K, V> compute, @NotNull ReferenceKind valuesReferenceKind
    ) {
        ConcurrentMap<K, Object> map = createConcurrentMap(valuesReferenceKind);
        return new MapBasedMemoizedFunctionToNotNull<K, V>(lock, map, compute);
    }

    @NotNull
    @Override
    public <K, V> MemoizedFunctionToNullable<K, V> createMemoizedFunctionWithNullableValues(
            @NotNull Function<K, V> compute, @NotNull ReferenceKind valuesReferenceKind
    ) {
        ConcurrentMap<K, Object> map = createConcurrentMap(valuesReferenceKind);
        return new MapBasedMemoizedFunction<K, V>(lock, map, compute);
    }

    private static <K, V> ConcurrentMap<K, V> createConcurrentMap(ReferenceKind referenceKind) {
        return (referenceKind == ReferenceKind.WEAK) ? new ConcurrentWeakValueHashMap<K, V>() : new ConcurrentHashMap<K, V>();
    }

    @NotNull
    @Override
    public <T> NotNullLazyValue<T> createLazyValue(@NotNull Computable<T> computable) {
        return new LockBasedNotNullLazyValue<T>(lock, computable);
    }

    @NotNull
    @Override
    public <T> NotNullLazyValue<T> createRecursionTolerantLazyValue(
            @NotNull Computable<T> computable, @NotNull final T onRecursiveCall
    ) {
        return new LockBasedNotNullLazyValue<T>(lock, computable) {
            @Override
            protected T recursionDetected(boolean firstTime) {
                return onRecursiveCall;
            }
        };
    }

    @NotNull
    @Override
    public <T> NotNullLazyValue<T> createLazyValueWithPostCompute(
            @NotNull Computable<T> computable,
            final Function<Boolean, T> onRecursiveCall,
            @NotNull final Consumer<T> postCompute
    ) {
        return new LockBasedNotNullLazyValue<T>(lock, computable) {
            @Nullable
            @Override
            protected T recursionDetected(boolean firstTime) {
                if (onRecursiveCall == null) {
                    return super.recursionDetected(firstTime);
                }
                return onRecursiveCall.fun(firstTime);
            }

            @Override
            protected void postCompute(@NotNull T value) {
                postCompute.consume(value);
            }
        };
    }

    @NotNull
    @Override
    public <T> NullableLazyValue<T> createNullableLazyValue(@NotNull Computable<T> computable) {
        return new LockBasedLazyValue<T>(lock, computable);
    }

    @NotNull
    @Override
    public <T> NullableLazyValue<T> createRecursionTolerantNullableLazyValue(@NotNull Computable<T> computable, final T onRecursiveCall) {
        return new LockBasedLazyValue<T>(lock, computable) {
            @Override
            protected T recursionDetected(boolean firstTime) {
                return onRecursiveCall;
            }
        };
    }

    @NotNull
    @Override
    public <T> NullableLazyValue<T> createNullableLazyValueWithPostCompute(
            @NotNull Computable<T> computable, @NotNull final Consumer<T> postCompute
    ) {
        return new LockBasedLazyValue<T>(lock, computable) {
            @Override
            protected void postCompute(@Nullable T value) {
                postCompute.consume(value);
            }
        };
    }

    @Override
    public <T> T compute(@NotNull Computable<T> computable) {
        lock.lock();
        try {
            return computable.compute();
        }
        finally {
            lock.unlock();
        }
    }

    private static class LockBasedLazyValue<T> implements NullableLazyValue<T> {

        private enum NotValue {
            NOT_COMPUTED,
            COMPUTING,
            RECURSION_WAS_DETECTED
        }

        private final Lock lock;
        private final Computable<T> computable;

        @Nullable
        private volatile Object value = NotValue.NOT_COMPUTED;

        public LockBasedLazyValue(@NotNull Lock lock, @NotNull Computable<T> computable) {
            this.lock = lock;
            this.computable = computable;
        }

        @Override
        public boolean isComputed() {
            return value != NotValue.NOT_COMPUTED && value != NotValue.COMPUTING;
        }

        @Override
        public T compute() {
            Object _value = value;
            if (!(value instanceof NotValue)) return WrappedValues.unescapeThrowable(_value);

            lock.lock();
            try {
                _value = value;
                if (!(_value instanceof NotValue)) return WrappedValues.unescapeThrowable(_value);

                if (_value == NotValue.COMPUTING) {
                    value = NotValue.RECURSION_WAS_DETECTED;
                    return recursionDetected(/*firstTime = */ true);
                }

                if (_value == NotValue.RECURSION_WAS_DETECTED) {
                    return recursionDetected(/*firstTime = */ false);
                }

                value = NotValue.COMPUTING;
                try {
                    T typedValue = computable.compute();
                    value = typedValue;
                    postCompute(typedValue);
                    return typedValue;
                }
                catch (Throwable throwable) {
                    if (value == NotValue.COMPUTING) {
                        // Store only if it's a genuine result, not something thrown through recursionDetected()
                        value = WrappedValues.escapeThrowable(throwable);
                    }
                    throw ExceptionUtils.rethrow(throwable);
                }
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * @param firstTime {@code true} when recursion has been just detected, {@code false} otherwise
         * @return a value to be returned on a recursive call or subsequent calls
         */
        @Nullable
        protected T recursionDetected(boolean firstTime) {
            throw new IllegalStateException("Recursive call in a lazy value");
        }

        protected void postCompute(T value) {
            // Doing something in post-compute helps prevent infinite recursion
        }
    }

    private static class LockBasedNotNullLazyValue<T> extends LockBasedLazyValue<T> implements NotNullLazyValue<T> {

        public LockBasedNotNullLazyValue(@NotNull Lock lock, @NotNull Computable<T> computable) {
            super(lock, computable);
        }

        @Override
        @NotNull
        public T compute() {
            T result = super.compute();
            assert result != null : "compute() returned null";
            return result;
        }
    }

    private static class MapBasedMemoizedFunction<K, V> implements MemoizedFunctionToNullable<K, V> {
        private final Lock lock;
        private final ConcurrentMap<K, Object> cache;
        private final Function<K, V> compute;

        public MapBasedMemoizedFunction(@NotNull Lock lock, @NotNull ConcurrentMap<K, Object> map, @NotNull Function<K, V> compute) {
            this.lock = lock;
            this.cache = map;
            this.compute = compute;
        }

        @Override
        @Nullable
        public V fun(@NotNull K input) {
            Object value = cache.get(input);
            if (value != null) return WrappedValues.unescapeExceptionOrNull(value);

            lock.lock();
            try {
                value = cache.get(input);
                if (value != null) return WrappedValues.unescapeExceptionOrNull(value);

                try {
                    V typedValue = compute.fun(input);
                    Object oldValue = cache.put(input, WrappedValues.escapeNull(typedValue));
                    assert oldValue == null : "Race condition detected";

                    return typedValue;
                }
                catch (Throwable throwable) {
                    Object oldValue = cache.put(input, WrappedValues.escapeThrowable(throwable));
                    assert oldValue == null : "Race condition detected";

                    throw ExceptionUtils.rethrow(throwable);
                }
            }
            finally {
                lock.unlock();
            }
        }
    }

    private static class MapBasedMemoizedFunctionToNotNull<K, V> extends MapBasedMemoizedFunction<K, V> implements MemoizedFunctionToNotNull<K, V> {

        public MapBasedMemoizedFunctionToNotNull(
                @NotNull Lock lock,
                @NotNull ConcurrentMap<K, Object> map,
                @NotNull Function<K, V> compute
        ) {
            super(lock, map, compute);
        }

        @NotNull
        @Override
        public V fun(@NotNull K input) {
            V result = super.fun(input);
            assert result != null : "compute() returned null";
            return result;
        }
    }
}
