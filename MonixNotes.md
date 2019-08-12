# Writing to Observers
When we write to Observers, it is important to note that onNext returns a Future[Ack].
This means, when we want to write two data items, we must wait for the first to be
acknowledged.

# Writing Observers
We can think of possible Observer implementations using a variety of mental models.
1. A reservoir. As we push more data by calling onNext it starts filling up. When we
   connect a Consumer it drains.
2. A balloon. As we push more data calling onNext, it expands to some extent but the
   more data we push, we harder the ballon pushes back. When we connect a Consumer it
   deflates.
3. A gate. Initially the gate is shut and we cannot push any data. The Future[Ack] returned by
   onNext will not complete. When we connect a Consumer the gate opens. A gate never 
   buffers data.
4. A drain pipe. When we push data into the pipe and nothing is connected to the other end, 
   the data is spilt and lost. When we connect a Consumer, the data passes
   into that.

Behaviours can be combined in various ways. So, for example, once a reservoir    
becomes full, it could start leaking like a drain pipe or it could become like
a gate and stop receiving more data.
   
# Connector building blocks
Often, we have some Observable stream of data and we would like to consume the stream
in two places in our program. This presents a problem.

Imagine, for example, a reservoir half full. Once we connect the first Consumer, the 
reservoir immediately beings draining into it. If, one microsecond later, we connect
a second Consumer, that second Consumer will have missed some of the data sent to
the first Consumer.

If you are familiar with reactive programming, you might be aware there is a class
of Observables with a `connect` method, which allows the caller to signal that
all his Consumers are connected and he is ready to receive data.

The problem with this approach, in concurrent programmes, is if the two Consumers
connect from separate threads, which one connected second and should then call
connect?

To solve this we provide special connectors that can start draining data automatically
when a condition is met, such as two subscribers being connected.

There are canned implementations for splitting data two ways and three ways or a
PredicateConnector that can evaluate an arbitrary predicate upon the addition of
a subscriber. It implements the balloon/gate/drain/etc strategy while the predicate
is false and begins draining once the predicate returns true.