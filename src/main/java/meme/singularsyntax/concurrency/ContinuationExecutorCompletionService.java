/*
 * ContinuationExecutorCompletionService.java
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
 * java.util.concurrent.ExecutorCompletionService. This is the notice
 * accompanying the original code:
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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link java.util.concurrent.CompletionService} implementation designed to be used in concert with
 * {@link ContinuableFutureTask}, which wraps submitted tasks to support
 * re-submission and continuation via the <a href="http://commons.apache.org/sandbox/commons-javaflow/">Apache Commons Javaflow</a> continuations
 * API. See the Javadoc for <tt>ContinuableFutureTask</tt> for a detailed
 * discussion of usage.
 * 
 * Object semantics are used for <tt>equals</tt> and <tt>hashCode</tt> methods of the
 * {@link java.util.concurrent.Callable} or {@link java.lang.Runnable}
 * tasks that are passed to the completion service by invoking <tt>submit</tt>.
 * This implies a one-to-one relationship between task instances and {@link java.util.concurrent.Future}
 * returned (and therefore, continuability of the task instance).
 * 
 * @author sscheck
 * @see ContinuableFutureTask
 * 
 */
public class ContinuationExecutorCompletionService<V> implements CompletionService<V>
{
	private final Executor executor;
	private final BlockingQueue<ContinuableFutureTask<V>> completionQueue;
	private final ConcurrentMap<Object, ContinuableFutureTask<V>> taskFutureMap;

	/**
	 * FutureTask extension to enqueue and manage task-to-future mapping upon
	 * completion.
	 */
	private class QueueingFuture extends FutureTask<Void> {

		private final ContinuableFutureTask<V> future;

		public QueueingFuture(ContinuableFutureTask<V> future) {
			super(future, null);
			this.future = future;
		}

		protected void done() {
			// Since ContinuableFutureTask can be re-submitted, reaching here
			// does not mean the entire computation is done - only add to
			// completion queue when the future itself indicates completion.
			if (future.isDone())
				completionQueue.add(future);
		}
	}

	private ContinuableFutureTask<V> mapTaskToFuture(Object task, ContinuableFutureTask<V> newFuture) {
		ContinuableFutureTask<V> oldFuture = taskFutureMap.putIfAbsent(task, newFuture);
		newFuture = (oldFuture == null) ? newFuture : oldFuture;
		return newFuture;
	}

	private ContinuableFutureTask<V> newTaskFor(Callable<V> task) {
		ContinuableFutureTask<V> future = mapTaskToFuture(task, new ContinuableFutureTask<V>(task));

		if (future.isDone() || future.isCancelled())
			throw new IllegalStateException("submit() called on finished or cancelled task");

		return future;
	}

	private ContinuableFutureTask<V> newTaskFor(Runnable task, V result) {
		ContinuableFutureTask<V> future = mapTaskToFuture(task, new ContinuableFutureTask<V>(task, result));

		if (future.isDone() || future.isCancelled())
			throw new IllegalStateException("submit() called on finished or cancelled task");

		return future;
	}

	/**
	 * Creates an ContinuationExecutorCompletionService using the supplied
	 * executor for base task execution and a
	 * {@link LinkedBlockingQueue} as a completion queue.
	 *
	 * @param executor the executor to use
	 * @throws NullPointerException if executor is {@code null}
	 */
	public ContinuationExecutorCompletionService(Executor executor) {
		if (executor == null)
			throw new NullPointerException();
		this.executor = executor;
		this.completionQueue = new LinkedBlockingQueue<ContinuableFutureTask<V>>();
		this.taskFutureMap = new ConcurrentHashMap<Object, ContinuableFutureTask<V>>();
	}

	/**
	 * Creates an ContinuationExecutorCompletionService using the supplied
	 * executor for base task execution and the supplied queue as its
	 * completion queue.
	 *
	 * @param executor the executor to use
	 * @param completionQueue the queue to use as the completion queue
	 *        normally one dedicated for use by this service. This
	 *        queue is treated as unbounded -- failed attempted
	 *        {@code Queue.add} operations for completed taskes cause
	 *        them not to be retrievable.
	 * @throws NullPointerException if executor or completionQueue are {@code null}
	 */
	public ContinuationExecutorCompletionService(Executor executor,
			BlockingQueue<ContinuableFutureTask<V>> completionQueue) {
		if (executor == null || completionQueue == null)
			throw new NullPointerException();
		this.executor = executor;
		this.completionQueue = completionQueue;
		this.taskFutureMap = new ConcurrentHashMap<Object, ContinuableFutureTask<V>>();
	}

	public Future<V> submit(Callable<V> task) {
		if (task == null) throw new NullPointerException();
		ContinuableFutureTask<V> f = newTaskFor(task);
		executor.execute(new QueueingFuture(f));
		return f;
	}

	public Future<V> submit(Runnable task, V result) {
		if (task == null) throw new NullPointerException();
		ContinuableFutureTask<V> f = newTaskFor(task, result);
		executor.execute(new QueueingFuture(f));
		return f;
	}

	public Future<V> take() throws InterruptedException {
		ContinuableFutureTask<V> future = completionQueue.take();
		taskFutureMap.remove(future.getTask());
		return future;
	}

	public Future<V> poll() {
		ContinuableFutureTask<V> future = completionQueue.poll();
		taskFutureMap.remove(future.getTask());
		return future;
	}

	public Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
		ContinuableFutureTask<V> future = completionQueue.poll(timeout, unit);
		taskFutureMap.remove(future.getTask());
		return future;
	}
}
