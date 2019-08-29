It seems to be the case that addressing can't be implemented as part
of the basic peer groups (UDP, TCP, DTLS, TLS) because their
underlying protocols do not support addressing itself (session endpoints
identify communications but not the actual actor behind them).
Lets see this with an example, Alice and Carlos could live behind a NAT
and share a single IP, if they both start channels to Bob, then Bob would
be able to distinguish the channels, but there is no information in the
session endpoints (IP/Port pairs) that could allow Bob to know which
conversation comes from Alice and which come from Carlos. Alice, Bob and
Carlos would need to agree on how to identify conversations to their
identities.

One option to do this, is to send a control message at the start of a
conversation that estates who is the sender of the message. This is
implemented in ControlMessageAddressing,
An alternative option is to add a header to messages to identify their
source. This is implemented in HeaderAddressing. Both options present
advantages and disadvantages:
Control message based
- Pros
  + Minimal information overhead: The identifier is sent only once.
- Cons
  + If we don't send the control message at the very start, then we can
    not define a value for the method `to` in `Channel` instances we
    create for the `server` stream.
  + The control message could get lost, then we would need to implement
    an identification request message.

Header based
- Pros
  + We will never fail to detect the source of a message.
- Cons
  + We can't define a value for the method `to` in `Channel` instances
    we create for the `server` stream until we receive the first message
    from the client. This forces to either separate connection creation
    events from channel creation events (Done in
    HeaderAddressingWithConnection) or add a control message to this
    approach too (which brings the control message loss problem to this
    approach too (This was implemented in HeaderAddressing).
  + There is information overhead, a client only needs one identifier to
    associate to the session endpoint (IP/Port) of a set of messages.
    Once the IP/Port is associated to an id, resending the id does not
    provide useful data at protocol level.

In both cases we present a problem related to connection based protocols.
In connection based protocols like TCP, scalanet creates a Channel
instance as soon as a connection is accepted. This is a problem when we
try to obtain the identifier of a connection at the server side, because
no message is sent by the client yet. However, the channel is created and
a value needs to be provided for the method `to` (in Channel interface).
We could act in two ways to overcome that issue:
1. Start a "handshake" from the server side requesting to the client to
   send an identification message (this is the control message approach)
2. Do not create a Channel instance on connection reception and only
   create it on the first message arrival event. We could add a new event
   `NewConnectionReceived` to provide a wrapper for the low level
   connection concep (implemented with the `Connection` trait).

Note that, at the point of connection reception (before receiving the
first message), there is no way to know who is the actor behind a IP/Port
pair.

Problems
- When to send the control message?
- WHat to do with the not used header address in subsequent messages?
- Should we hold the task until the addressing handshake is complete?