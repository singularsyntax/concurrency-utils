/*
 * Benchmark.java
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
 * Copyright (c) 2013 Stephen J. Scheck. All rights reserved.
 * 
 */

package meme.singularsyntax.benchmark;

import gnu.getopt.Getopt;
import gnu.getopt.LongOpt;

import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import meme.singularsyntax.benchmark.RandomByteService.RandomByteServiceCallback;
import meme.singularsyntax.benchmark.RandomByteService.Request;
import meme.singularsyntax.concurrency.ContinuationExecutorCompletionService;

import org.apache.commons.javaflow.Continuation;
import org.slf4j.LoggerFactory;

/**
 * Benchmark utility to compare performance between continuation and synchronous
 * models for simulated network I/O using ContinuableFutureTask and ordinary
 * java.util.concurrent.FutureTask.
 * 
 * @author sscheck
 * 
 */
public class Benchmark
{
	private static enum ExecutionStrategy {
		SYNCHRONOUS,
		CONTINUATION
	}

	private static enum WorkQueueStrategy {
		SYNCHRONOUS,
		BOUNDED,
		UNBOUNDED
	}

	private static interface ServiceDispatch {
		public byte[] sendRequest(Callable<byte[]> task, Request request);
		public void recvResponse(Callable<byte[]> task, Request request, byte[] response, Throwable ex);
	}

	private static class SynchronousDispatch implements ServiceDispatch {

		private final RandomByteService randomByteService;

		public SynchronousDispatch(RandomByteService randomByteService) {
			this.randomByteService = randomByteService;
		}

		@Override
		public byte[] sendRequest(Callable<byte[]> task, Request request) {
			randomByteService.queueRequest(request);

			synchronized (request) {
				while (request.getData() == null) {
					try {
						request.wait();
					} catch (InterruptedException e) {
						// restore thread interrupt flag
						Thread.currentThread().interrupt();
					}
				}
			}

			return request.getData();
		}

		@Override
		public void recvResponse(Callable<byte[]> task, Request request,
				byte[] response, Throwable ex) {

			synchronized (request) {
				request.setData(response);
				request.notify();
			}
		}
	}

	private static class ContinuationDispatch implements ServiceDispatch {

		private final CompletionService<byte[]> completionService;
		private final RandomByteService randomByteService;

		public ContinuationDispatch(RandomByteService randomByteService,
				CompletionService<byte[]> completionService) {

			this.randomByteService = randomByteService;
			this.completionService = completionService;
		}

		@Override
		public byte[] sendRequest(Callable<byte[]> task, Request request) {
			randomByteService.queueRequest(request);

			LoggerFactory.getLogger("Javaflow").info(String.format("SUSP: task %s [%s]", task.toString(), Thread.currentThread().getName()));
			Continuation.suspend();

			return request.getData();
		}

		@Override
		public void recvResponse(Callable<byte[]> task, Request request,
				byte[] response, Throwable ex) {

			request.setData(response);
			LoggerFactory.getLogger("Javaflow").info(String.format("RSUB: task %s [%s]", task.toString(), Thread.currentThread().getName()));
			completionService.submit(task);
		}
	}

	private static class Task implements Callable<byte[]>, RandomByteServiceCallback {

		private final int computeSteps;
		private final ServiceDispatch dispatch;

		public Task(int computeSteps, ServiceDispatch dispatch) {
			this.computeSteps = computeSteps;
			this.dispatch = dispatch;
		}

		@Override
		public byte[] call() throws Exception {
			byte[] data = null;
			MessageDigest md = MessageDigest.getInstance("SHA-1");

			for (int ii = 0; ii < computeSteps; ii++) {
				data = dispatch.sendRequest(this, new Request(this));
				md.update(data);
			}

			return md.digest();
		}

		@Override
		public void response(Request request, byte[] response, Throwable ex) {
			dispatch.recvResponse(this, request, response, ex);
		}
	}

	private static class Application {

		private static final ExecutionStrategy DEFAULT_EXECUTION_STRATEGY = ExecutionStrategy.SYNCHRONOUS;
		private static final WorkQueueStrategy DEFAULT_WORK_QUEUE_STRATEGY = WorkQueueStrategy.SYNCHRONOUS;

		private static final int DEFAULT_WORK_QUEUE_CAPACITY = 100;
		private static final int DEFAULT_CORE_POOL_SIZE = 100;
		private static final long DEFAULT_KEEP_ALIVE_TIME = 600; // 10 minutes
		private static final int DEFAULT_REQUESTS_PER_SECOND = 10;
		private static final int DEFAULT_RUN_TIME_IN_SECONDS = 60;
		private static final int DEFAULT_MAXIMUM_POOL_SIZE = DEFAULT_REQUESTS_PER_SECOND *
				DEFAULT_RUN_TIME_IN_SECONDS;

		private static final int DEFAULT_COMPUTE_STEPS = 10;
		private static final long DEFAULT_THREAD_STACK_SIZE = 1024 * 1024; // 1 megabyte

		private static final boolean DEFAULT_QUIET_OUTPUT = false;

		private CompletionService<byte[]> completionService;
		private RandomByteService randomByteService;
		private ServiceDispatch serviceDispatch;
		private ThreadPoolExecutor threadPool;
		private ThreadFactory threadFactory;

		private int requestsPerSecond;
		private int runTime;
		private int computeSteps;
		private long threadStackSize;

		private boolean quiet;

		public Application(String[] argv) {
			init(argv);
		}

		public CompletionService<byte[]> getCompletionService() {
			return completionService;
		}

		public RandomByteService getRandomByteService() {
			return randomByteService;
		}

		public ServiceDispatch getServiceDispatch() {
			return serviceDispatch;
		}

		public ThreadPoolExecutor getThreadPool() {
			return threadPool;
		}

		public int getRequestsPerSecond() {
			return requestsPerSecond;
		}

		public int getRunTime() {
			return runTime;
		}

		public int getComputeSteps() {
			return computeSteps;
		}

		public long getThreadStackSize() {
			return threadStackSize;
		}

		public boolean isQuiet() {
			return quiet;
		}

		private void init(String[] argv) {

			ExecutionStrategy execStrategy = DEFAULT_EXECUTION_STRATEGY;
			WorkQueueStrategy workQueueStrategy = DEFAULT_WORK_QUEUE_STRATEGY;

			int workQueueCapacity = DEFAULT_WORK_QUEUE_CAPACITY;
			int corePoolSize = DEFAULT_CORE_POOL_SIZE;
			int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE;
			long keepAliveTime = DEFAULT_KEEP_ALIVE_TIME;

			requestsPerSecond = DEFAULT_REQUESTS_PER_SECOND;
			runTime = DEFAULT_RUN_TIME_IN_SECONDS;
			computeSteps = DEFAULT_COMPUTE_STEPS;
			threadStackSize = DEFAULT_THREAD_STACK_SIZE;

			quiet = DEFAULT_QUIET_OUTPUT;

			int c;
			String arg;

			LongOpt[] longOpts = {
				new LongOpt("execution-strategy", LongOpt.REQUIRED_ARGUMENT, null, 'e'),
				new LongOpt("work-queue-strategy", LongOpt.REQUIRED_ARGUMENT, null, 'q'),
				new LongOpt("work-queue-capacity", LongOpt.REQUIRED_ARGUMENT, null, 'c'),
				new LongOpt("core-pool-size", LongOpt.REQUIRED_ARGUMENT, null, 'p'),
				new LongOpt("maximum-pool-size", LongOpt.REQUIRED_ARGUMENT, null, 'm'),
				new LongOpt("keep-alive-time", LongOpt.REQUIRED_ARGUMENT, null, 'k'),
				new LongOpt("requests-per-second", LongOpt.REQUIRED_ARGUMENT, null, 'r'),
				new LongOpt("run-time", LongOpt.REQUIRED_ARGUMENT, null, 't'),
				new LongOpt("compute-steps", LongOpt.REQUIRED_ARGUMENT, null, 's'),
				new LongOpt("thread-stack-size", LongOpt.REQUIRED_ARGUMENT, null, 1),
				new LongOpt("quiet", LongOpt.NO_ARGUMENT, null, 2),
				new LongOpt("help", LongOpt.NO_ARGUMENT, null, 'h')
			};

			Getopt g = new Getopt("benchmark", argv, ":e:q:c:p:m:k:r:t:s:hW;", longOpts);

			while ((c = g.getopt()) != -1) {
				switch (c) {
					case 1:
						arg = g.getOptarg();
						threadStackSize = Long.valueOf(arg) * 1024;
						break;

					case 2:
						quiet = true;
						break;

					case 'e':
						arg = g.getOptarg();
						execStrategy = ExecutionStrategy.valueOf(arg.toUpperCase());
						if (execStrategy == null)
							throw new IllegalArgumentException("Illegal execution strategy: " + arg);

						break;

					case 'q':
						arg = g.getOptarg();
						workQueueStrategy = WorkQueueStrategy.valueOf(arg.toUpperCase());
						if (workQueueStrategy == null)
							throw new IllegalArgumentException("Illegal work queue strategy: " + arg);

						break;

					case 'c':
						arg = g.getOptarg();
						workQueueCapacity = Integer.valueOf(arg);
						break;

					case 'p':
						arg = g.getOptarg();
						corePoolSize = Integer.valueOf(arg);
						break;

					case 'm':
						arg = g.getOptarg();
						maximumPoolSize = Integer.valueOf(arg);
						break;

					case 'k':
						arg = g.getOptarg();
						keepAliveTime = Long.valueOf(arg);
						break;

					case 'r':
						arg = g.getOptarg();
						requestsPerSecond = Integer.valueOf(arg);
						break;

					case 't':
						arg = g.getOptarg();
						runTime = Integer.valueOf(arg);
						break;

					case 's':
						arg = g.getOptarg();
						computeSteps = Integer.valueOf(arg);
						break;

					case 'h':
						printHelp(longOpts);
						System.exit(0);
						break;

					case ':':
						System.err.println("Missing option argument" );
						break;

					case '?':
						System.err.println("Ambiguous long option: " + g.getOptarg());
						break;

					default:
						throw new IllegalArgumentException("Unknown command line option: " + c);
				}
			}

			BlockingQueue<Runnable> workQueue = null;

			switch (workQueueStrategy) {
				case SYNCHRONOUS:
					workQueue = new SynchronousQueue<Runnable>();
					maximumPoolSize = Integer.MAX_VALUE;
					break;

				case BOUNDED:
					workQueue = new ArrayBlockingQueue<Runnable>(workQueueCapacity);
					break;

				case UNBOUNDED:
					workQueue = new LinkedBlockingQueue<Runnable>();
					break;
			}

			threadFactory = new ThreadFactory() {

				private static final String THREAD_NAME_FORMAT = "Benchmark-Pool-Thread-%d";
				private final AtomicInteger threadNumber = new AtomicInteger();

				@Override
				public Thread newThread(Runnable runnable) {
					Thread thread = new Thread(null, runnable,
							String.format(THREAD_NAME_FORMAT, threadNumber.incrementAndGet()),
							threadStackSize);

					thread.setDaemon(true);

					return thread;
				}
			};

			threadPool = createThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, workQueue, threadFactory);
			completionService = createCompletionService(execStrategy, threadPool);
			randomByteService = new RandomByteService();

			switch (execStrategy) {
				case SYNCHRONOUS:
					serviceDispatch = new SynchronousDispatch(randomByteService);
					break;

				case CONTINUATION:
					serviceDispatch = new ContinuationDispatch(randomByteService, completionService);
					break;
			}

			System.out.println("Configuration:");
			System.out.println("  Execution Strategy: " + execStrategy);
			System.out.println("  Work Queue Strategy: " + workQueueStrategy);
			System.out.println("  Work Queue Capacity: " + workQueueCapacity);
			System.out.println("  Core Pool Size: " + corePoolSize);
			System.out.println("  Maximum Pool Size: " + maximumPoolSize);
			System.out.println("  Keep Alive Time (Seconds): " + keepAliveTime);
			System.out.println("  Requests Per Second: " + requestsPerSecond);
			System.out.println("  Run Time (Seconds): " + runTime);
			System.out.println("  Compute Steps: " + computeSteps);
			System.out.println("  Thread Stack Size (bytes): " + threadStackSize);

			System.out.println("JVM:");
			System.out.println("  Available Processors: " + Runtime.getRuntime().availableProcessors());
			System.out.println("  Free Memory: " + Runtime.getRuntime().freeMemory());
			System.out.println("  Max Memory: " + Runtime.getRuntime().maxMemory());
			System.out.println("  Total Memory: " + Runtime.getRuntime().totalMemory());
		}

		private CompletionService<byte[]> createCompletionService(ExecutionStrategy execStrategy, Executor threadPool) {

			CompletionService<byte[]> completionService = null;

			switch (execStrategy) {
				case SYNCHRONOUS:
					completionService = new ExecutorCompletionService<byte[]>(threadPool);
					break;

				case CONTINUATION:
					completionService = new ContinuationExecutorCompletionService<byte[]>(threadPool);
					break;
			}

			return completionService;
		}

		private ThreadPoolExecutor createThreadPoolExecutor(int corePoolSize,
				int maximumPoolSize, long keepAliveTime, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {

			return new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime,
					TimeUnit.SECONDS, workQueue, threadFactory);
		}

		private void printHelp(LongOpt[] longOpts) {
			for (LongOpt opt : longOpts) {
				System.out.printf("-%s --%s <%s>\n", (char) opt.getVal(), opt.getName(),
					opt.getHasArg() == LongOpt.OPTIONAL_ARGUMENT ? "optional" : "required");
			}
		}
	}

	private static String toHexString(byte[] data) {
		StringBuilder sb = new StringBuilder();

		for (byte b : data)
			sb.append(String.format("%02X", b));

		return sb.toString();
	}

	public static void main(String[] argv) {
		Application app = new Application(argv);
		app.getThreadPool().prestartAllCoreThreads();

		int rejectedTasks = 0;
		long begTicks = System.currentTimeMillis();
		long endTicks;

		for (int seconds = 0; seconds < app.getRunTime(); seconds++) {
			try {
				for (int requests = 0; requests < app.getRequestsPerSecond(); requests++) {
					Task task = new Task(app.getComputeSteps(), app.getServiceDispatch());

					try {
						app.getCompletionService().submit(task);						
					} catch (RejectedExecutionException e) {
						rejectedTasks++;
					}
				}

				Thread.sleep(1000L);

			} catch (InterruptedException ignored) {}
		}

		int remaining = (app.getRequestsPerSecond() * app.getRunTime()) - rejectedTasks;
		while (remaining > 0) {
			try {
				Future<byte[]> future = app.getCompletionService().take();
				if (! app.isQuiet())
					System.out.println(toHexString(future.get()));
				remaining--;
			} catch (InterruptedException e) {
				// ignored
			} catch (ExecutionException e) {
				System.err.println(e.getMessage());
			} 
		}

		endTicks = System.currentTimeMillis();
		System.out.printf("Finished processing in %d msec\n", endTicks - begTicks);

		System.out.println("Thread Pool Statistics:");
		System.out.println("  Active Count: " + app.getThreadPool().getActiveCount());
		System.out.println("  Completed Task Count: " + app.getThreadPool().getCompletedTaskCount());
		System.out.println("  Core Pool Size: " + app.getThreadPool().getCorePoolSize());
		System.out.println("  Largest Pool Size: " + app.getThreadPool().getLargestPoolSize());
		System.out.println("  Maximum Pool Size: " + app.getThreadPool().getMaximumPoolSize());
		System.out.println("  Pool Size: " + app.getThreadPool().getPoolSize());
		System.out.println("  Task Count: " + app.getThreadPool().getTaskCount());
		System.out.println("  Rejected Tasks: " + rejectedTasks);

		app.getRandomByteService().shutdown();
		app.getThreadPool().shutdown();
	}
}
