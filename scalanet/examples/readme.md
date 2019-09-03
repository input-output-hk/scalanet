### Scalanet examples
In order to run examples in this folder you should run
```bash
mill scalanet.examples.assembly
java -cp out/scalanet/examples/assembly/dest/out.jar <main class> [args]
```

### Kademlia Console
This is a test app for kademlia, creating a simple console which allows the user to manually query for, add and 
remove entries from the kademlia node.

After assembling the examples out.jar, run the following command to get a list of available command line options:
```bash
java -cp out/scalanet/examples/assembly/dest/out.jar io.iohk.scalanet.kconsole.App -h
```

Here is an example of starting a simple, two-node network

```bash
# First console
$ java -cp out/scalanet/examples/assembly/dest/out.jar io.iohk.scalanet.kconsole.App

# ... log output ...
Initialized with node record {"id":"a98e6fa629b7b4ae679748ad65915f1bf1178ac0","messaging-address":"localhost:52570","routing-address":"localhost:52569"}

 Command summary:
 get    <nodeId hex>  perform a lookup for the given nodeId and prints the record returned (if any).
 add    <node record> adds the given node record to this node. The record format should be the same as that returned by get.
 remove <nodeId hex>  remove the given nodeId from this nodes kbuckets.
 dump                 dump the contents of this nodes kbuckets to the console.
 help                 print this message.
 exit                 shutdown the node and quit the application.

>

# In a second console start another node, bootstrapping it from the the first...
$ java -cp out/scalanet/examples/assembly/dest/out.jar io.iohk.scalanet.kconsole.App -b '{"id":"a98e6fa629b7b4ae679748ad65915f1bf1178ac0","messaging-address":"localhost:52570","routing-address":"localhost:52569"}'

Initialized with node record {"id":"442c01efed34c16002e5943932f2c765d45c3baa","messaging-address":"localhost:52561","routing-address":"localhost:52560"}

 Command summary:
 get    <nodeId hex>  perform a lookup for the given nodeId and prints the record returned (if any).
 add    <node record> adds the given node record to this node. The record format should be the same as that returned by get.
 remove <nodeId hex>  remove the given nodeId from this nodes kbuckets.
 dump                 dump the contents of this nodes kbuckets to the console.
 help                 print this message.
 exit                 shutdown the node and quit the application.

# execute a dump command to display the current contents of the node's kbuckets
> dump

{"id":"442c01efed34c16002e5943932f2c765d45c3baa","messaging-address":"localhost:52561","routing-address":"localhost:52560"}
{"id":"a98e6fa629b7b4ae679748ad65915f1bf1178ac0","messaging-address":"localhost:52570","routing-address":"localhost:52569"}
```
