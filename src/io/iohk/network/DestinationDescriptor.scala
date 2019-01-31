package io.iohk.network

sealed trait DestinationDescriptor {
  def apply(v1: NodeId): Boolean
}

case object Everyone extends DestinationDescriptor {
  override def apply(v1: NodeId): Boolean = true
}

case class SingleNode(nodeId: NodeId) extends DestinationDescriptor {
  override def apply(v1: NodeId): Boolean = nodeId == v1
}

case class SetOfNodes(set: Set[NodeId]) extends DestinationDescriptor {
  override def apply(v1: NodeId): Boolean = set.contains(v1)
}

case class Not(destinationDescriptor: DestinationDescriptor) extends DestinationDescriptor {
  override def apply(v1: NodeId): Boolean = !destinationDescriptor(v1)
}

case class And(a: DestinationDescriptor, b: DestinationDescriptor) extends DestinationDescriptor {
  override def apply(v1: NodeId): Boolean = a(v1) && b(v1)
}

case class Or(a: DestinationDescriptor, b: DestinationDescriptor) extends DestinationDescriptor {
  override def apply(v1: NodeId): Boolean = a(v1) || b(v1)
}
