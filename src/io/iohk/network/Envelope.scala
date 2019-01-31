package io.iohk.network

/**
  *
  * @param transaction
  * @param containerId
  * @param destinationDescriptor a function from NodeInfo to Boolean expressing which nodes are intended to process this tx
  *                              It is the user's responsibility to validate that the destination nodes can handle the
  *                              ledger with id ledgerId
  * @tparam D the type of the wrapped content.
  */
case class Envelope[+D](content: D, containerId: ContainerId, destinationDescriptor: DestinationDescriptor)
