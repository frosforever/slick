package scala.slick.ast
package opt

import scala.slick.util.Logging
import Util._
import scala.collection.mutable.{HashSet, HashMap}
import scala.slick.SLICKException

/**
 * Basic optimizers for the SLICK AST
 */
object Optimizer extends Logging {

  def apply(n: Node): Node = {
    if(logger.isDebugEnabled) {
      logger.debug("source:", n)
    }
    val n2 = localizeRefs(n)
    if(logger.isDebugEnabled) {
      AnonSymbol.assignNames(n2, prefix = "s", force = true)
      if(n2 ne n) logger.debug("localized refs:", n2)
    }
    val n3 = ReconstructProducts(n2)
    if(n3 ne n2) logger.debug("products reconstructed:", n3)
    val n4 = (new Inliner)(n3)
    if(n4 ne n3) logger.debug("refs inlined:", n4)
    val n5 = assignUniqueSymbols(n4)
    if((n5 ne n4) && logger.isDebugEnabled) {
      AnonSymbol.assignNames(n5, prefix = "u")
      logger.debug("unique symbols:", n5)
    }
    n5
  }

  /** Replace GlobalSymbols by AnonSymbols and collect them in a LetDynamic */
  def localizeRefs(tree: Node): Node = {
    val map = new HashMap[AnonSymbol, Node]
    val newNodes = new HashMap[AnonSymbol, Node]
    val tr = new Transformer {
      def replace = {
        case r: RefNode => r.nodeMapReferences {
          case GlobalSymbol(name, target) =>
            val s = new AnonSymbol
            map += s -> target
            newNodes += s -> target
            s
          case s => s
        }
      }
    }
    val tree2 = tr.once(tree)
    while(!newNodes.isEmpty) {
      val m = newNodes.toMap
      newNodes.clear()
      m.foreach { case (sym, n) => map += sym -> tr.once(n) }
    }
    if(map.isEmpty) tree2 else LetDynamic(map.toSeq, tree2)
  }

  /**
   * Ensure that all symbol definitions in a tree are unique
   */
  def assignUniqueSymbols(tree: Node): Node = {
    val seen = new HashSet[AnonSymbol]
    def tr(n: Node, replace: Map[AnonSymbol, AnonSymbol]): Node = n match {
      case r @ Ref(a: AnonSymbol) => replace.get(a).fold(r)(Ref(_))
      case d: DefNode =>
        var defs = replace
        d.nodeMapScopedChildren { (symO, ch) =>
          val r = tr(ch, defs)
          symO match {
            case Some(a: AnonSymbol) =>
              if(seen.contains(a)) defs += a -> new AnonSymbol
              else seen += a
            case _ =>
          }
          r
        }.nodeMapGenerators {
          case a: AnonSymbol => defs.getOrElse(a, a)
          case s => s
        }
      case l: LetDynamic =>
        throw new SLICKException("Dynamic scopes should be eliminated before assigning unique symbols")
      case n => n.nodeMapChildren(tr(_, replace))
    }
    tr(tree, Map())
  }
}
