package sjsonnet

import ujson.JsVisitor

/**
 * A `upickle.core.Visitor` specialized to work with JSON types. Forwards the not-JSON-related
 * methods to their JSON equivalents.
 *
 * This is a vendored variant of [[JsVisitor]] that routes integral values through `visitInt64`
 * rather than collapsing everything onto `visitFloat64`, so that [[Val.Int64]] and [[Val.Dec128]]
 * can be rendered without a trip through `Double`.
 *
 * NOTE: the default `visitInt64` here goes through the generic `visitFloat64StringParts` string
 * path. Renderers with an optimized long path (see `BaseCharRenderer`) must override `visitInt64`
 * directly — [[Val.Int64]] is the default representation for integers, so inheriting this default
 * would put the common case on the slow path.
 */
trait JsonVisitor[-T, +J] extends JsVisitor[T, J] {
  override def visitUInt64(i: Long, index: Int): J = {
    if (i < 0) visitFloat64StringParts(java.lang.Long.toUnsignedString(i), -1, -1, index)
    else visitInt64(i, index)
  }

  override def visitInt32(i: Int, index: Int): J =
    visitInt64(i, index)

  override def visitFloat32(f: Float, index: Int): J =
    visitFloat64(f, index)

  override def visitInt64(i: Long, index: Int): J =
    visitFloat64StringParts(i.toString, -1, -1, index)

  override def visitFloat64(d: Double, index: Int): J = {
    d match {
      case Double.PositiveInfinity        => visitString("Infinity", index)
      case Double.NegativeInfinity        => visitString("-Infinity", index)
      case d if java.lang.Double.isNaN(d) => visitString("NaN", index)
      case d                              =>
        val i = d.toLong
        if (i == d) visitInt64(i, index)
        else visitFloat64String(d.toString, index)
    }
  }
}
