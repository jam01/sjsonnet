package sjsonnet

import java.util

import upickle.core.{ArrVisitor, ObjVisitor, Visitor}

import scala.collection.mutable

/** Parse JSON directly into a literal `Val` */
class ValVisitor(pos: Position) extends JsonVisitor[Val, Val] { self =>

  override def visitJsonableObject(length: Int, index: Int): ObjVisitor[Val, Val] =
    visitObject(length, index)

  def visitArray(length: Int, index: Int): ArrVisitor[Val, Val] = new ArrVisitor[Val, Val] {
    val a = new mutable.ArrayBuilder.ofRef[Eval]
    if (length >= 0) a.sizeHint(length)
    def subVisitor: Visitor[?, ?] = self
    def visitValue(v: Val, index: Int): Unit = a.+=(v)
    def visitEnd(index: Int): Val = Val.Arr(pos, a.result())
  }

  def visitObject(length: Int, index: Int): ObjVisitor[Val, Val] = new ObjVisitor[Val, Val] {
    val cache = new java.util.HashMap[Any, Val]()
    val allKeys = new util.LinkedHashMap[String, java.lang.Boolean]
    var key: String = _
    def subVisitor: Visitor[?, ?] = self
    def visitKey(index: Int): upickle.core.StringVisitor.type = upickle.core.StringVisitor
    def visitKeyValue(s: Any): Unit = key = s.toString
    def visitValue(v: Val, index: Int): Unit = {
      cache.put(key, v)
      allKeys.put(key, false)
    }
    def visitEnd(index: Int): Val = new Val.Obj(pos, null, true, null, null, cache, allKeys)
  }

  def visitNull(index: Int): Val = Val.Null(pos)

  def visitFalse(index: Int): Val = Val.False(pos)

  def visitTrue(index: Int): Val = Val.True(pos)

  // Val.Num.apply owns the whole routing decision, including the `-0` rule that used to be
  // special-cased here (#926) and the overflow-safe widening for integers wider than a Long (#1019).
  def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int): Val =
    Val.Num(pos, s.toString, decIndex, expIndex)

  def visitString(s: CharSequence, index: Int): Val = Val.Str(pos, s.toString)

  override def visitInt64(l: Long, index: Int): Val = Val.Int64(pos, l)

  override def visitFloat64(d: Double, index: Int): Val = Val.Float64(pos, d)
}
