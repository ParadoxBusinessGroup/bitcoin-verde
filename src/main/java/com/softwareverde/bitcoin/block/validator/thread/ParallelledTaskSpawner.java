package com.softwareverde.bitcoin.block.validator.thread;

import com.softwareverde.bitcoin.server.database.DatabaseConnectionFactory;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParallelledTaskSpawner<T, S> {
    protected static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool();

    protected final DatabaseConnectionFactory _databaseConnectionFactory;
    protected final DatabaseManagerCache _databaseManagerCache;
    protected List<ValidationTask<T, S>> _validationTasks = null;
    protected TaskHandlerFactory<T, S> _taskHandlerFactory;

    public void setTaskHandlerFactory(final TaskHandlerFactory<T, S> taskHandlerFactory) {
        _taskHandlerFactory = taskHandlerFactory;
    }

    public ParallelledTaskSpawner(final DatabaseConnectionFactory databaseConnectionFactory, final DatabaseManagerCache databaseManagerCache) {
        _databaseConnectionFactory = databaseConnectionFactory;
        _databaseManagerCache = databaseManagerCache;
    }

    public void executeTasks(final List<T> items, final int maxThreadCount) {
        final int totalItemCount = items.getSize();
        final int threadCount = Math.min(maxThreadCount, Math.max(1, (totalItemCount / maxThreadCount)));
        final int itemsPerThread = (totalItemCount / threadCount);

        final ImmutableListBuilder<ValidationTask<T, S>> listBuilder = new ImmutableListBuilder<>(threadCount);

        for (int i = 0; i < threadCount; ++i) {
            final int startIndex = i * itemsPerThread;
            final int remainingItems = (items.getSize() - startIndex);
            final int itemCount = ( (i < (threadCount - 1)) ? Math.min(itemsPerThread, remainingItems) : remainingItems);

            final ValidationTask<T, S> validationTask = new ValidationTask<>(_databaseConnectionFactory, _databaseManagerCache, items, _taskHandlerFactory.newInstance());
            validationTask.setStartIndex(startIndex);
            validationTask.setItemCount(itemCount);
            validationTask.enqueueTo(THREAD_POOL);
            listBuilder.add(validationTask);
        }

        _validationTasks = listBuilder.build();
    }

    public List<S> waitForResults() {
        final ImmutableListBuilder<S> listBuilder = new ImmutableListBuilder<>();

        for (int i = 0; i < _validationTasks.getSize(); ++i) {
            final ValidationTask<T, S> validationTask = _validationTasks.get(i);
            final S result = validationTask.getResult();
            if (result == null) { return null; }

            listBuilder.add(result);
        }
        return listBuilder.build();
    }

    public void abort() {
        for (int i = 0; i < _validationTasks.getSize(); ++i) {
            final ValidationTask<T, S> validationTask = _validationTasks.get(i);
            validationTask.abort();
        }
    }
}
