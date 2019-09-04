
### QoS coding experiment
All peer groups support messaging, but they do it in different ways.
We say that each peer group implementation provides a certain _quality of service_.

This can mean a few things:
* Behavioural characteristics. Message ordering, maximum message sizes, reliable message delivery, etc.
* Data provided. Can I get the address of a remote peer, it's public key, ...
* Multiplexing. Is this implemented?
* Security. Can I tell specify the conditions necessary to talk to other peers?

Scalanet supports messaging in a consistent manner, but clearly quality of service differences 
between peer group implementations will affect applications and N+1 level peer groups.
To this end, we are looking at two new requirements.
1. When an application needs to assume certain quality of service it can do so
2. If an application exchanges one peer group implementation for another and quality or service assumptions
are not met, the code will stop compiling.
3. Some peer group implementations will support _configurable_ quality of service (for instance they might allow the
user to toggle message ordering or increase the maximum message size).

Here are a few things to think about:
* Boolean characteristics like reliable delivery seem to lend themselves well to marker traits, allowing apps to
write code like
```scala
type ReliablePeerGroup = PeerGroup with ReliableDelivery
val peerGroup: ReliablePeerGroup = ???
```
Either an implementation provides the characteristic or it does not.
* A quality of service statement such as _the maximum message size is 2Mb_ is not so easy to express with traits.
* Suppose we want to create a Peer group with several QoS toggles
 ```scala
 def udpPlusPlus[M](reliableDelivery: Boolean, orderedMessaging: Boolean)
```
This is also problematic to express with traits, because the truth table for two boolean parameters means we have four possible
configurations, and we do not wish the number of types to explode with the number of parameters.