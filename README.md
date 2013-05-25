Concurrency-Utils
=================

Concurrency-Utils provides a very lightweight (just two classes) library
that offers implementations of the `java.util.concurrent` `RunnableFuture` and
`CompletionService` interfaces which integrate Apache Commons Javaflow
continuations into java.util.concurrent's task and execution model. This
provides a couple of benefits: suspend-able and re-submittable tasks, and
a means to consume asynchronous I/O APIs and services using apparently
straight-flowing, linear coding style with a minimum of effort.

The library also includes a simple simulator for a network service with an
asynchronous API and a class for benchmarking continuations-enabled asynch
performance against an approach which uses a synchronous facade over the
asynchronous API (i.e. submit an asynch request, then use synchronization
primitives to immediately block the calling thread until the service responds).

The continuation-enabled asynchronous approach appears to offer comparable
(or better, for some scenarios) compute performance characteristics to the
synchronous facade approach, and may offer further advantages in simplifying
tuning and lessening memory and thread footprint. A brief discussion of some
benchmark results is given below.


REFERENCES
----------

<http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/package-summary.html>  
<http://commons.apache.org/sandbox/commons-javaflow/>  
<http://en.wikipedia.org/wiki/Continuation>


INSTALLATION
------------

Concurrency-Utils depends on the javaflow-maven-plugin here:

  https://github.com/singularsyntax/javaflow-maven-plugin

Clone the repository, change to its directory, then run the following command
to install into your local Maven repository:

    mvn install

Then change into the concurrency-utils directory and run the above Maven
install command again to build and install into the local repository. The
library can then be used in your own projects by including it as a Maven
dependency in your project's POM:

    <dependency>
      <groupId>meme.singularsyntax.java</groupId>
      <artifactId>concurrency-utils</artifactId>
      <version>1.0-SNAPSHOT</version>
    </dependency>


USAGE
-----

See the Javadoc and class comments for the classes `ContinuableFutureTask` and
`ContinuationExecutorCompletionService` in package `meme.singularsyntax.concurrency`
as well as documentation for `java.util.concurrent` for basic usage explanations.
The package `meme.singularsyntax.benchmark` has two classes which together provide
an example of the continuation-enabled asynchronous I/O model described above:
`RandomByteService`, which implements a simple network service simulation with
an asynchronous request submission API, and `Benchmark`, which allows the user to
compare synchronous and asynchronous performance using a variety of parameters
to tune thread pool characteristics, task queuing behavior, and service load:

    mvn exec:java -Dexec.args="--help"


BENCHMARKS
----------

Benchmarks were performed using the `Benchmark` class mentioned above. It proceeds
by sending a configurable number of requests-per-second for a specified period (in
these benchmarks, 100/second for 1 minute) to the `RandomByteService` which queues
the them and responds asynchronously. As its name indicates, it simply generates
and returns random sequences of bytes after a random, Gaussian-distributed delay
around a specified mean (5 seconds here). The service is designed to roughly
simulate latent, low-bandwidth wireless networks similar to those deployed by a
company I used to work for. It is worth emphasizing that latency (typical response
delays of a few seconds or more) and asynchronous response are two key assumptions
taken for granted in proposing a continuation-enabled asynchronous I/O model, and
that such assumptions may not match more common workloads. This kind of workload
fits well for a monitoring application which has to continuously poll many thousands
or millions of end-points over a low-bandwidth, latent wireless network, but may not
fit as well for the high-volume web site/service traffic patterns typical of many
consumer-facing web properties. In the former case, the application generates a
predictable number of requests (based on known deployment numbers and schedules)
to a "backend service" (the wireless network and nodes) whereas in the case of a
web site, the application *is* the backend service and receives a more unpredictable
number of requests from the public internet.

The`java.util.concurrent.ThreadPoolExecutor` class offers three dominant models for
task queuing and thread pool growth behavior: synchronous, bounded, and unbounded
queuing. The first employs a `SynchronousQueue` and essentially spawns a new thread
for each new task submitted to the executor. The second and third employ bounded or
unbounded queues (`ArrayBlockingQueue` and `LinkedBlockingQueue`, respectively) which
gate the creation of new threads when the current or core pool size is insufficient
to accommodate incoming tasks. The primary difference is that unbounded queues allow
infinite queuing (and therefore, no pool growth), while bounded ones will reject
tasks when both the queue limit and maximum thread pool size are reached (see Javadocs
for `java.util.concurrent.ThreadPoolExecutor`).

Benchmarks exercised all three queuing models, for both synchronous and asynchronous
approaches as explained previously. The distribution file BENCHMARKS.typescript has
the raw results of the commands used to test various combinations of modes. Although
the results only show one execution for each, multiple runs were performed for each
combination and results were consistent within a minor range of variation. Some of the
combinations have been omitted from the typescript file after determining that they
are not competitive. For example, synchronous bounded and unbounded execution will
always be equivalent or worse to synchronous/synchronous (determined by the setting
for their maximum or core pool size settings, respectively), and therefore the latter
is the appropriate baseline for comparison against asynchronous modes.

Results indicate slightly better performance for the continuation approach across the
board, as measured by wall-clock times. However, the similarity in wall clock timings
for sync and asynch (continuation) obscures the fact that async execution with
continuations is able to achieve its slight performance advantage using many less
threads than synchronous (4000+ versus 8). This is because continuations provide a
kind of "user-space context switching". Also, continuations allow for the user-space
task to "hint" to the OS that they are eligible for an immediate context switch,
thereby avoiding waste of the remainder of their allocated quanta. This has a similar
effect to the implicit context switch that may occur in the implementation of some
OSes when blocking system calls are made.

There is some controversy about whether synchronous or asynchronous I/O and their
attendant threading assumptions offer better performance (see, for example
<http://paultyma.blogspot.com/2008/03/writing-java-multithreaded-servers.html>).
However, if other design requirements impose an asynchronous design, the benchmarks
here demonstrate that adding continuations into the mix offers some nice benefits -
linear coding style, improved responsiveness due to "user-space context switching" -
while suffering no disadvantage compared to a "synchronous facade" approach. If
minimizing the total number of compute threads (which also simplifies some aspects
of tuning and scaling for memory footprint) is an important design requirement,
the approach implemented by this library is worth consideration.


AUTHOR
------

Stephen J. Scheck <singularsyntax@gmail.com>
