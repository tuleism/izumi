package izumi.distage.model.plan.repr

import izumi.distage.model.plan.ExecutableOp
import izumi.distage.model.plan.ExecutableOp.MonadicOp._
import izumi.distage.model.plan.ExecutableOp.ProxyOp._
import izumi.distage.model.plan.ExecutableOp.WiringOp._
import izumi.distage.model.plan.ExecutableOp.{CreateSet, ImportDependency, InstantiationOp, WiringOp, _}
import izumi.distage.model.plan.operations.OperationOrigin
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.Wiring.MonadicWiring._
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.Wiring.SingletonWiring._
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.Wiring._
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.{Association, DIKey, Provider, Wiring}
import izumi.fundamentals.platform.strings.IzString._

trait OpFormatter {
  def format(op: ExecutableOp): String
}

object OpFormatter {
  def formatBindingPosition(origin: OperationOrigin): String = {
    origin match {
      case OperationOrigin.UserBinding(binding) =>
        binding.origin.toString
      case OperationOrigin.SyntheticBinding(binding) =>
        binding.origin.toString
      case OperationOrigin.Unknown =>
        "(<unknown>)"
    }
  }

  def apply(keyFormatter: KeyFormatter, typeFormatter: TypeFormatter): OpFormatter = new OpFormatter.Impl(keyFormatter, typeFormatter)

  class Impl
  (
    keyFormatter: KeyFormatter,
    typeFormatter: TypeFormatter,
  ) extends OpFormatter {

    import keyFormatter.formatKey
    import typeFormatter.formatType

    override def format(op: ExecutableOp): String = {
      op match {
        case i: InstantiationOp =>
          i match {
            case CreateSet(target, tpe, members, origin) =>
              val repr = doFormat(formatType(tpe), members.map(formatKey).toSeq, "newset", ('[', ']'), ('{', '}'))
              val pos = formatBindingPosition(origin)
              s"${formatKey(target)} $pos := $repr"

            case ExecuteEffect(target, effectKey, _, effectHKTypeCtor, origin) =>
              val pos = formatBindingPosition(origin)
              s"${formatKey(target)} $pos := effect[$effectHKTypeCtor]${formatKey(effectKey)}"

            case AllocateResource(target, effectKey, _, effectHKTypeCtor, origin) =>
              val pos = formatBindingPosition(origin)
              s"${formatKey(target)} $pos := allocate[$effectHKTypeCtor]${formatKey(effectKey)}"

            case w: WiringOp =>
              w match {
                case CallProvider(target, wiring, origin) =>
                  formatOp(target, wiring, origin)
                case CallFactoryProvider(target, wiring, origin) =>
                  formatOp(target, wiring, origin)
                case UseInstance(target, wiring, origin) =>
                  val pos = formatBindingPosition(origin)
                  if (wiring.instance != null) {
                    s"${formatKey(target)} $pos := value ${wiring.instance.getClass.getName}#${wiring.instance.hashCode()}"
                  } else {
                    s"${formatKey(target)} $pos := null"
                  }
                case ReferenceKey(target, wiring, origin) =>
                  val pos = formatBindingPosition(origin)
                  s"${formatKey(target)} $pos := ref ${formatKey(wiring.key)}"
              }
          }

        case ImportDependency(target, references, origin) =>
          val pos = formatBindingPosition(origin)
          val hint = if (references.nonEmpty) {
            s"// required for ${references.map(formatKey).mkString(" and ")}"
          } else {
            " // no dependees"
          }
          s"${formatKey(target)} $pos := import $target $hint".trim

        case p: ProxyOp =>
          p match {
            case MakeProxy(proxied, forwardRefs, origin, byNameAllowed) =>

              val pos = formatBindingPosition(origin)
              val kind = if (byNameAllowed) {
                "proxy.light"
              } else {
                "proxy.cogen"
              }

              s"""${formatKey(p.target)} $pos := $kind(${forwardRefs.map(s => s"${formatKey(s)}: deferred").mkString(", ")}) {
                 |${format(proxied).shift(2)}
                 |}""".stripMargin

            case ProxyOp.InitProxy(target, dependencies, _, origin) =>
              val pos = formatBindingPosition(origin)
              s"${formatKey(target)} $pos -> init(${dependencies.map(formatKey).mkString(", ")})"

          }
      }
    }

    private def formatOp(target: DIKey, deps: Wiring, origin: OperationOrigin): String = {
      val op = formatWiring(deps)
      val pos = formatBindingPosition(origin)
      s"${formatKey(target)} $pos := $op"
    }

    private def formatWiring(deps: Wiring): String = {
      deps match {
        //        case Constructor(instanceType, associations, prefix) =>
        //          doFormat(formatType(instanceType), formatPrefix(prefix) ++ associations.map(formatDependency), "make", ('[', ']'), ('(', ')'))
        //
        //        case AbstractSymbol(instanceType, associations, prefix) =>
        //          doFormat(formatType(instanceType), formatPrefix(prefix) ++ associations.map(formatDependency), "impl", ('[', ']'), ('{', '}'))

        case Function(provider, associations) =>
          doFormat(formatFunction(provider), associations.map(formatDependency), "call", ('(', ')'), ('{', '}'))

        //        case Factory(factoryType, factoryIndex, dependencies) =>
        //          val wirings = factoryIndex.map {
        //            w =>
        //              s"${w.factoryMethod}: ${formatType(w.factoryMethod.finalResultType)} ~= ${formatWiring(w.wireWith)}".shift(2)
        //          }
        //
        //          val depsRepr = dependencies.map(formatDependency)
        //
        //          doFormat(
        //            formatType(factoryType)
        //            , wirings ++ depsRepr
        //            , "factory", ('(', ')'), ('{', '}')
        //          )

        case FactoryFunction(provider, factoryIndex, dependencies) =>
          val wirings = factoryIndex.map {
            case (idx, w) =>
              s"${w.factoryMethod}[$idx]: ${formatType(w.factoryMethod.finalResultType)} ~= ${formatWiring(w.productWiring)}".shift(2)
          }.toSeq

          val depsRepr = dependencies.map(formatDependency)

          doFormat(
            formatFunction(provider)
            , wirings ++ depsRepr
            , "call factory", ('(', ')'), ('{', '}')
          )

        case other@(_: Effect | _: Resource | _: Instance | _: Reference) =>
          s"UNEXPECTED WIREABLE: $other"
      }
    }

    private def formatDependency(association: Association): String = {
      association match {
        case p: Association.Parameter =>
          val fname = if (p.isByName) {
            s"=> ${p.name}"
          } else {
            p.name
          }

          s"""arg $fname: ${formatType(p.tpe)} = lookup(${formatKey(p.key)})"""
      }
    }

    private def formatFunction(provider: Provider): String = {
      s"${provider.fun}(${provider.argTypes.map(formatType).mkString(", ")}): ${formatType(provider.ret)}"
    }

    //    private def formatPrefix(prefix: Option[DIKey]): Seq[String] = {
    //      prefix.toSeq.map(p => s".prefix = lookup(${formatKey(p)})")
    //    }

    private def doFormat(impl: String, depRepr: Seq[String], opName: String, opFormat: (Char, Char), delim: (Char, Char)): String = {
      val sb = new StringBuilder()
      sb.append(s"$opName${opFormat._1}$impl${opFormat._2} ${delim._1}")
      if (depRepr.nonEmpty) {
        sb.append("\n")
        sb.append(depRepr.mkString("\n").shift(2))
        sb.append(s"\n${delim._2}")
      } else {
        sb.append(delim._2)
      }

      sb.toString()
    }

  }

}