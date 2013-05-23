/*
 * ContinuableFutureTask.java
 * 
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The author designates this
 * particular file as subject to the "Classpath" exception as provided
 * for in the LICENSE file that accompanied this code.
 * 
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * This code is modeled upon and borrows heavily from the OpenJDK class
 * java.util.concurrent.FutureTask. This is the notice accompanying the
 * original code:
 * 
 * ### ORIGINAL COPYRIGHT NOTICE ###
 * 
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 * 
 * ### END OF ORIGINAL COPYRIGHT NOTICE ###
 * 
 * Copyright (c) 2013 Stephen J. Scheck. All rights reserved.
 * 
 */

package meme.singularsyntax.concurrency;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.apache.commons.javaflow.Continuation;
import org.slf4j.LoggerFactory;

/**
 * {@link java.util.concurrent.RunnableFuture} implementation that supports re-submission and continuation of
 * tasks via the <a href="http://commons.apache.org/sandbox/commons-javaflow/">Apache Commons Javaflow</a> continuations API.
 * 
 * <p>The typical use pattern of <tt>ContinuableFutureTask<V></tt> class proceeds like this:
 * 
 * <ol>
 *   <li>A class implementing {@link java.util.concurrent.Callable} defines
 *   some computational task that relies on use of asynchronous APIs and services,
 *   such as networked information retrieval, database queries, etc.</li>
 *   <li>The <tt>Callable</tt> is submitted to an
 *   {@link ContinuationExecutorCompletionService}, which wraps the task
 *   inside a <tt>ContinuableFutureTask</tt>. <tt>ContinuableFutureTask</tt>
 *   encapsulates a {@link org.apache.commons.javaflow.Continuation}
 *   object which will capture the task's execution state as it progresses
 *   through a series of computations and asynchronous service calls.</li>
 *   <li>Whenever the task calls upon such an asynchronous service method,
 *   it arranges for the {@link Continuation#suspend()} method to be called soon after.
 *   This causes the task to be suspended at the point of call, and returns the thread
 *   currently executing the task to its thread pool (as supplied by <tt>ContinuationExecutorCompletionService</tt>).</li>
 *   <li>The task supplies whatever callback or delegate mechanism is used by
 *   the asynchronous API with a means to resubmit itself to the <tt>ContinuationExecutorCompletionService</tt>
 *   when the asynchronous service replies.</li>
 *   <li>Upon re-submission, the <tt>ContinuationExecutorCompletionService</tt>
 *   maps the task to its <tt>ContinuableFutureTask</tt> and calls
 *   {@link org.apache.commons.javaflow.Continuation#continueWith(org.apache.commons.javaflow.Continuation)}
 *   to resume execution at the last suspend point.</li>
 *   <li>Eventually, the task completes, and standard mechanisms provided
 *   by {@link link java.util.concurrent.Future} and {@link link java.util.concurrent.CompletionService}
 *   interfaces can be used to retrieve the task result, wait for its completion, etc.</li>
 * </ol>
 * 
 * <p>A <tt>ContinuableFutureTask</tt> can be used to wrap a <tt>Callable</tt> or
 * {@link java.lang.Runnable} object.  Because <tt>ContinuableFutureTask</tt>
 * implements <tt>Runnable</tt>, a <tt>ContinuableFutureTask</tt> can be
 * submitted to an {@link java.util.concurrent.Executor} for execution.
 * 
 * <p>Because of differences in how the notion of task completion is implemented,
 * <tt>ContinuableFutureTask</tt> should not be used with
 * {@link java.util.concurrent.ExecutorCompletionService}. If that is done, behavior
 * is undefined, but in particular it has been observed that <tt>ExecutorCompletionService</tt>
 * considers a <tt>ContinuableFutureTask</tt> to be complete after the underlying
 * task invokes <tt>Continuation.suspend()</tt>, which is probably not the desired
 * outcome for most use cases. This is because <tt>ExecutorCompletionService</tt>
 * considers a return from the <tt>run</tt> method of the internal <tt>Runnable</tt>
 * it wraps the user task in as completion, without calling <tt>isDone</tt> on the
 * task to check its completion state.
 * 
 * <p>As noted, when used with <tt>ContinuationExecutorCompletionService</tt>,
 * there is a one-to-one correspondence between a <tt>Callable</tt> (or <tt>Runnable</tt>)
 * submitted to it and the <tt>ContinuableFutureTask</tt> that is created to
 * track computation continuation and completion state. As such, two independent
 * executions of the same <tt>Callable</tt> must use distinct object instances (or
 * distinct <tt>ContinuationExecutorCompletionService</tt> instances). If different
 * behavior or mechanisms for tracking are desired, applications can directly construct
 * <tt>ContinuableFutureTask</tt> instances wrapping <tt>Callable</tt> or <tt>Runnable</tt>
 * and implement their own mechanisms for task submission, re-submission, and tracking.
 *
 * @author sscheck
 * @param <V> The result type returned by this ContinuableFutureTask's <tt>get</tt> method
 * @see ContinuationExecutorCompletionService
 * 
 */
public class ContinuableFutureTask<V> implements RunnableFuture<V>
{
	/** Synchronization control for ContinuableFutureTask */
	private final Sync sync;

	private final Callable<V> task;

	Callable<V> getTask() { return task; }

	/**
	 * Creates a <tt>ContinuableFutureTask</tt> that will, upon running, execute the
	 * given <tt>Callable</tt>.
	 *
	 * @param  callable the callable task
	 * @throws NullPointerException if callable is null
	 */
	public ContinuableFutureTask(Callable<V> callable) {
		if (callable == null)
			throw new NullPointerException();
		sync = new Sync(callable);
		task = callable;
	}

	/**
	 * Creates a <tt>ContinuableFutureTask</tt> that will, upon running, execute the
	 * given <tt>Runnable</tt>, and arrange that <tt>get</tt> will return the
	 * given result on successful completion.
	 *
	 * @param runnable the runnable task
	 * @param result the result to return on successful completion. If
	 * you don't need a particular result, consider using
	 * constructions of the form:
	 * <tt>Future&lt;?&gt; f = new ContinuableFutureTask&lt;Object&gt;(runnable, null)</tt>
	 * @throws NullPointerException if runnable is null
	 */
	public ContinuableFutureTask(Runnable runnable, V result) {
		sync = new Sync(Executors.callable(runnable, result));
		task = Executors.callable(runnable, result);
	}

	public boolean isCancelled() {
		return sync.innerIsCancelled();
	}

	public boolean isDone() {
		return sync.innerIsDone();
	}

	public boolean cancel(boolean mayInterruptIfRunning) {
		return sync.innerCancel(mayInterruptIfRunning);
	}

	/**
	 * @throws CancellationException {@inheritDoc}
	 */
	public V get() throws InterruptedException, ExecutionException {
		return sync.innerGet();
	}

	/**
	 * @throws CancellationException {@inheritDoc}
	 */
	public V get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		return sync.innerGet(unit.toNanos(timeout));
	}

	/**
	 * Protected method invoked when this task transitions to state
	 * <tt>isDone</tt> (whether normally or via cancellation). The
	 * default implementation does nothing.  Subclasses may override
	 * this method to invoke completion callbacks or perform
	 * bookkeeping. Note that you can query status inside the
	 * implementation of this method to determine whether this task
	 * has been cancelled.
	 */
	protected void done() { }

	/**
	 * Sets the result of this Future to the given value unless
	 * this future has already been set or has been cancelled.
	 * This method is invoked internally by the <tt>run</tt> method
	 * upon successful completion of the computation.
	 * @param v the value
	 */
	protected void set(V v) {
		sync.innerSet(v);
	}

	/**
	 * Causes this future to report an <tt>ExecutionException</tt>
	 * with the given throwable as its cause, unless this Future has
	 * already been set or has been cancelled.
	 * This method is invoked internally by the <tt>run</tt> method
	 * upon failure of the computation.
	 * @param t the cause of failure
	 */
	protected void setException(Throwable t) {
		sync.innerSetException(t);
	}

	/**
	 * Sets this Future to the result of its computation
	 * unless it has been cancelled.
	 */
	public void run() {
		sync.innerRun();
	}

	/**
	 * Executes the computation without setting its result, and then
	 * resets this Future to initial state, failing to do so if the
	 * computation encounters an exception or is cancelled.  This is
	 * designed for use with tasks that intrinsically execute more
	 * than once.
	 * @return true if successfully run and reset
	 */
	protected boolean runAndReset() {
		return sync.innerRunAndReset();
	}

	/**
	 * Call context and runner for user Callable<V> tasks, for bridging with
	 * Javaflow Continuation.continueWith() which requires a Runnable.
	 * 
	 */
	private final class CallContext implements Callable<Boolean>, Runnable {

		private final Callable<V> userTask;
		private volatile boolean completed;
		private volatile V result;
		private volatile Exception exception;
		private volatile Continuation continuation;
		private volatile Thread runner;

		public CallContext(Callable<V> userTask) {
			this.userTask = userTask;
			this.continuation = Continuation.startSuspendedWith(this);
			LoggerFactory.getLogger("Javaflow").info(String.format("INIT: task %s [%s]", userTask.toString(), Thread.currentThread().getName()));
		}

		@Override
		public void run() {
			try {
				result = userTask.call();
				completed = true;
			} catch (Exception e) {
				exception = e;
			} finally {
				LoggerFactory.getLogger("Javaflow").info(String.format("EXIT: %s [%s]", userTask.toString(), Thread.currentThread().getName()));
				Continuation.exit();
			}
		}

		@Override
		public synchronized Boolean call() throws Exception {
			if (continuation == null)
				throw new Exception("Computation completed and re-submitted without reset.");

			LoggerFactory.getLogger("Javaflow").info(String.format("CONT: task %s [%s]", userTask.toString(), Thread.currentThread().getName()));

			runner = Thread.currentThread();
			continuation = Continuation.continueWith(continuation);
			runner = null;

			if (exception != null)
				throw exception;

			return isCompleted();
		}

		public boolean isCompleted() {
			return completed;
		}

		public V getResult() {
			return result;
		}

		public void interrupt() {
			if (runner != null)
				runner.interrupt();
		}
	}

	/**
	 * Synchronization control for ContinuableFutureTask. Note that this must be
	 * a non-static inner class in order to invoke the protected
	 * <tt>done</tt> method. For clarity, all inner class support
	 * methods are same as outer, prefixed with "inner".
	 *
	 * Uses AQS sync state to represent run status
	 */
	private final class Sync extends AbstractQueuedSynchronizer {
		private static final long serialVersionUID = 1L;

		/** State value representing that task is ready to run */
		private static final int READY     = 0;
		/** State value representing that task is running */
		private static final int RUNNING   = 1;
		/** State value representing that task ran */
		private static final int RAN       = 2;
		/** State value representing that task was cancelled */
		private static final int CANCELLED = 4;

		/** Holds the underlying callable and continuation state */
		private final CallContext callContext;

		/** The result to return from get() */
		private V result;
		/** The exception to throw from get() */
		private Throwable exception;

		/**
		 * Flag indicating task completion. When raised after set/cancel, this
		 * indicates that the results are accessible.  Must be
		 * volatile, to ensure visibility upon completion.
		 */
		private volatile boolean done;

		Sync(Callable<V> callable) {
			this.callContext = new CallContext(callable);
		}

		private boolean ranOrCancelled(int state) {
			return (state & (RAN | CANCELLED)) != 0;
		}

		/**
		 * Implements AQS base acquire to succeed if ran or cancelled
		 */
		protected int tryAcquireShared(int ignore) {
			return innerIsDone() ? 1 : -1;
		}

		/**
		 * Implements AQS base release to always signal after setting
		 * final done status by raising done flag.
		 */
		protected boolean tryReleaseShared(int ignore) {
			done = true;
			return true;
		}

		boolean innerIsCancelled() {
			return getState() == CANCELLED;
		}

		boolean innerIsDone() {
			return ranOrCancelled(getState()) && done;
		}

		V innerGet() throws InterruptedException, ExecutionException {
			acquireSharedInterruptibly(0);
			if (getState() == CANCELLED)
				throw new CancellationException();
			if (exception != null)
				throw new ExecutionException(exception);
			return result;
		}

		V innerGet(long nanosTimeout) throws InterruptedException, ExecutionException, TimeoutException {
			if (!tryAcquireSharedNanos(0, nanosTimeout))
				throw new TimeoutException();
			if (getState() == CANCELLED)
				throw new CancellationException();
			if (exception != null)
				throw new ExecutionException(exception);
			return result;
		}

		void innerSet(V v) {
			for (;;) {
				int s = getState();
				if (s == RAN)
					return;
				if (s == CANCELLED) {
					// aggressively release to set runner to null,
					// in case we are racing with a cancel request
					// that will try to interrupt runner
					releaseShared(0);
					return;
				}
				if (compareAndSetState(s, RAN)) {
					result = v;
					releaseShared(0);
					done();
					return;
				}
			}
		}

		void innerSetException(Throwable t) {
			for (;;) {
				int s = getState();
				if (s == RAN)
					return;
				if (s == CANCELLED) {
					// aggressively release to set runner to null,
					// in case we are racing with a cancel request
					// that will try to interrupt runner
					releaseShared(0);
					return;
				}
				if (compareAndSetState(s, RAN)) {
					exception = t;
					releaseShared(0);
					done();
					return;
				}
			}
		}

		boolean innerCancel(boolean mayInterruptIfRunning) {
			for (;;) {
				int s = getState();
				if (ranOrCancelled(s))
					return false;
				if (compareAndSetState(s, CANCELLED))
					break;
			}
			if (mayInterruptIfRunning) {
				if (!done)
					callContext.interrupt();
			}
			releaseShared(0);
			done();
			return true;
		}

		void innerRun() {
			if (!compareAndSetState(READY, RUNNING))
				return;

			if (getState() == RUNNING) { // recheck after setting thread
				boolean completed;

				try {
					completed = callContext.call();
				} catch (Throwable ex) {
					setException(ex);
					return;
				}

				if (completed)
					set(callContext.getResult());

				// allow for re-submission if task didn't complete
				compareAndSetState(RUNNING, READY);

			} else {
				releaseShared(0); // cancel
			}
		}

		boolean innerRunAndReset() {
			if (!compareAndSetState(READY, RUNNING))
				return false;
			try {
				done = false;
				if (getState() == RUNNING)
					callContext.call(); // don't set result
				done = true;
				return compareAndSetState(RUNNING, READY);
			} catch (Throwable ex) {
				setException(ex);
				return false;
			}
		}
	}
}
