package com.anprosit.android.promise.internal;

import android.os.Bundle;
import android.os.Handler;

import com.anprosit.android.promise.Promise;
import com.anprosit.android.promise.ResultCallback;
import com.anprosit.android.promise.Task;
import com.anprosit.android.promise.internal.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Created by Hirofumi Nakagawa on 13/07/12.
 */
public class PromiseImpl<I, O> extends Promise<I, O> implements PromiseContext {
	private List<Task<?, ?>> mTasks = new ArrayList<Task<?, ?>>();

	private final Handler mHandler;

	private int mIndex;

	private ResultCallback<?> mResultCallback;

	private CountDownLatch mLatch = new CountDownLatch(1);

	protected State mState = State.READY;

	public PromiseImpl(Handler handler) {
		mHandler = handler;
	}

	@Override
	public <NO> Promise<I, NO> then(Task<O, NO> task) {
		synchronized (this) {
			addTask(task);
		}
		return (Promise<I, NO>) this;
	}

	@Override
	public <NO> Promise<I, NO> then(Promise<O, NO> promise) {
		synchronized (this) {
			addTasks(promise.anatomy());
		}
		return (Promise<I, NO>) this;
	}

	@Override
	public <NO> Promise<I, NO> thenOnMainThread(Task<O, NO> task) {
		return thenOnMainThread(task, 0);
	}

	@Override
	public <NO> Promise<I, NO> thenOnMainThread(Task<O, NO> task, long delay) {
		synchronized (this) {
			addTask(new HandlerThreadTask(delay));
			addTask(task);
		}
		return (Promise<I, NO>) this;
	}

	@Override
	public <NO> Promise<I, NO> thenOnAsyncThread(Task<O, NO> task) {
		return thenOnAsyncThread(task, 0);
	}

	@Override
	public <NO> Promise<I, NO> thenOnAsyncThread(Task<O, NO> task, long delay) {
		synchronized (this) {
			addTask(new AsyncThreadTask(delay));
			addTask(task);
		}
		return (Promise<I, NO>) this;
	}

	@Override
	public synchronized void execute(I value, ResultCallback<O> resultCallback) {
		if (getState() != State.READY)
			throw new IllegalStateException("Promise#execute method must be called in READY state");

		mResultCallback = resultCallback;

		Task<Object, ?> next = (Task<Object, ?>) getNextTask();

		mState = State.DOING;

		if (next == null) {
			done(value);
			return;
		}

		next.execute(value, this);
	}

	@Override
	public synchronized Collection<Task<?, ?>> anatomy() {
		if (mState != State.READY)
			throw new IllegalStateException("Promise#anatomy method must be called in READY state");
		destroy();
		return mTasks;
	}

	@Override
	public synchronized void done(final Object result) {
		if (mState != State.DOING)
			return;

		mState = State.DONE;

		final ResultCallback<Object> callback = (ResultCallback<Object>) mResultCallback;
		if (callback == null)
			return;

		mHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					if (getState() == State.DONE)
						callback.onCompleted(result);
				} finally {
					mLatch.countDown();
				}
			}
		});
	}

	@Override
	public synchronized boolean isCompleted() {
		return mState == State.DONE;
	}

	@Override
	public synchronized boolean isFailed() {
		return mState == State.FAILED;
	}

	@Override
	public synchronized void cancel() {
		if (mState != State.DOING && mState != State.READY)
			return;
		mState = State.CANCELLED;
		mLatch.countDown();
	}

	@Override
	public synchronized boolean isCancelled() {
		return mState == State.CANCELLED;
	}

	@Override
	public synchronized void destroy() {
		mState = State.DESTROYED;
		mLatch.countDown();
	}

	@Override
	public void await() {
		ThreadUtils.checkNotMainThread();
		try {
			mLatch.await();
		} catch (InterruptedException exp) {
		}
	}

	@Override
	public synchronized void fail(final Bundle result, final Exception exception) {
		if (mState != State.DOING)
			return;

		mState = State.FAILED;

		final ResultCallback<?> callback = mResultCallback;
		if (callback == null)
			return;

		mHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					if (getState() == State.FAILED)
						callback.onFailed(result, exception);
				} finally {
					mLatch.countDown();
				}
			}
		});
	}

	@Override
	public void yield(final int code, final Bundle value) {
		if (mState != State.DOING)
			return;

		final ResultCallback<?> callback = mResultCallback;
		if (callback == null)
			return;

		mHandler.post(new Runnable() {
			@Override
			public void run() {
				if (getState() == State.DOING)
					callback.onYield(code, value);
			}
		});
	}

	@Override
	public synchronized State getState() {
		return mState;
	}

	@Override
	public synchronized Task<?, ?> getNextTask() {
		if (mIndex >= mTasks.size())
			return null;
		return mTasks.get(mIndex++);
	}

	private void addTask(Task<?, ?> task) {
		mTasks.add(task);
	}

	private void addTasks(Collection<Task<?, ?>> tasks) {
		mTasks.addAll(tasks);
	}
}
