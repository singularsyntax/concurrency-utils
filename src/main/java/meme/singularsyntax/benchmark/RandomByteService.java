/*
 * RandomByteService.java
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

import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates a network service that offers a simple API to queue a request that
 * returns some random bytes as a response. Queued requests are non-blocking,
 * and responses are returned after a Gaussian-distributed delay time, according
 * to a configured maximum delay value.
 * 
 * @author sscheck
 * @see RandomByteServiceCallback
 * 
 */
public class RandomByteService
{
	private final static double DEFAULT_MAX_DELAY_SEC = 10.0;
	private final static int DEFAULT_RESPONSE_BYTES = 10;
	private final static String RUNNER_THREAD_NAME = "RandomByteService-RunnerThread-1";

	private final double maxDelaySec;
	private final Random randGen;
	private final DelayQueue<Request> requestQueue;
	private final Thread runnerThread;

	private class Runner implements Runnable {

		private final AtomicBoolean running = new AtomicBoolean(true);

		@Override
		public void run() {
			while (running.get() && (! Thread.currentThread().isInterrupted())) {
				try {
					List<Request> requests = new LinkedList<Request>();

					// block until some request is ready
					requests.add(requestQueue.take());

					// opportunistically grab any further requests whose delay has expired
					requestQueue.drainTo(requests);

					for (Request request : requests) {
						byte[] responseBytes = new byte[DEFAULT_RESPONSE_BYTES];
						randGen.nextBytes(responseBytes);
						request.callback.response(request, responseBytes, null);
					}

				} catch (InterruptedException e) {
					running.set(false);
				}
			}
		}
	}

	/**
	 * Request submitted by clients of RandomByteService to ask for some random
	 * bytes of data.
	 * 
	 */
	public static class Request implements Delayed {
		private byte[] data;
		private long expiryMsec;
		private final RandomByteServiceCallback callback;

		public Request(RandomByteServiceCallback callback) {
			this.callback = callback;
		}

		public long getExpiryMsec() {
			return expiryMsec;
		}

		public void setExpiryMsec(long expiryMsec) {
			this.expiryMsec = expiryMsec;
		}

		public byte[] getData() {
			return data;
		}

		public void setData(byte[] data) {
			this.data = data;
		}

		@Override
		public int compareTo(Delayed that) {
			return (int) (this.getDelay(TimeUnit.MILLISECONDS) - that.getDelay(TimeUnit.MILLISECONDS));
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(expiryMsec - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Interface implemented by clients of RandomByteService whose response()
	 * method is called when the service "returns" a response.
	 * 
	 */
	public interface RandomByteServiceCallback {
		public void response(Request request, byte[] response, Throwable ex);
	}

	public RandomByteService(double maxDelaySec) {
		this.maxDelaySec = maxDelaySec;
		this.randGen = new Random();
		this.requestQueue = new DelayQueue<Request>();
		this.runnerThread = new Thread(new Runner());

		runnerThread.setDaemon(true);
		runnerThread.setName(RUNNER_THREAD_NAME);
		runnerThread.start();
	}

	public RandomByteService() {
		this(DEFAULT_MAX_DELAY_SEC);
	}

	public void queueRequest(Request request) {
		long delayMsec = TimeUnit.MILLISECONDS.convert(Double.valueOf(randGen
				.nextGaussian() + (maxDelaySec / 2.0)).longValue(), TimeUnit.SECONDS);

		request.setExpiryMsec(System.currentTimeMillis() + delayMsec);
		requestQueue.put(request);
	}

	public void shutdown() {
		runnerThread.interrupt();
	}
}
