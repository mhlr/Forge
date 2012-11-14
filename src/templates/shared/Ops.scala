package ppl.dsl.forge
package templates
package shared

import java.io.{BufferedWriter, FileWriter, PrintWriter}
import scala.tools.nsc.io._
import scala.collection.mutable.ArrayBuffer
import scala.virtualization.lms.common._
import core._
import Utilities._

trait BaseGenOps extends ForgeCodeGenBase {  
  val IR: ForgeApplicationRunner with ForgeExp with ForgeOpsExp
  import IR._

  /**
   * Utility methods
   */  
  def baseOpsCls(grp: Rep[DSLGroup]) = {
    if (OpsGrp(grp).ops.exists(o => nameClashes(o).length > 1)) "Base with OverloadHack"
    else "Base"
  }
  
  // used with overloading
  // if after converting Ts and Vars to Reps there are duplicates, remove them   
  def unique(ops: List[Rep[DSLOp]]): List[Rep[DSLOp]] = {
    val filtered = scala.collection.mutable.ArrayBuffer[Rep[DSLOp]]()
    val canonical = scala.collection.mutable.HashSet[String]()
    
    // add to filtered only if canonical version doesn't already exist
    for (o <- ops) {
      val t = o.name + makeOpArgsWithType(o) + makeOpImplicitArgs(o)
      if (!canonical.contains(t)) {
        filtered += o
        canonical += t
      }
    }
    filtered.toList
  }
    
  // normal args
  def makeOpArgs(o: Rep[DSLOp]) = "(" + o.args.zipWithIndex.map(t => opArgPrefix + t._2).mkString(",") + ")"
  def makeOpFutureArgs(o: Rep[DSLOp]) = "(" + o.args.zipWithIndex.map(t => { val arg = opArgPrefix + t._2; if (t._1.stage == now) "unit("+arg+")" else arg }).mkString(",") + ")"
  def makeOpArgsWithType(o: Rep[DSLOp], map: Rep[DSLType] => String = repify) = "(" + o.args.zipWithIndex.map(t => opArgPrefix + t._2 + ": " + map(t._1)).mkString(",") + ")"
  def makeOpArgsWithNowType(o: Rep[DSLOp]) = makeOpArgsWithType(o, repifySome)
  
  // untyped implicit args
  def makeImplicitCtxBounds(tpePars: List[Rep[TypePar]]) = {
    tpePars.flatMap(a => a.ctxBounds.map(b => "implicitly["+b.name+"["+quote(a)+"]]")).mkString(",")
  }  
  def makeOpImplicitCtxBounds(o: Rep[DSLOp]) = makeImplicitCtxBounds(o.tpePars)    
  def makeImplicitArgs(implicitArgs: List[Rep[DSLType]], ctxBoundsStr: String = "") = {
    // ctxBounds must come before regular implicits
    val ctxBounds2 = if (ctxBoundsStr == "") "" else ctxBoundsStr+","
    if (implicitArgs.length > 0) "(" + ctxBounds2 + implicitArgs.zipWithIndex.map(t => implicitOpArgPrefix + t._2).mkString(",") + ")"
    else ""    
  }
  def makeOpImplicitArgs(o: Rep[DSLOp]) = makeImplicitArgs(o.implicitArgs, makeOpImplicitCtxBounds(o)) // explicitly passing implicits requires passing ctxBounds, too
  def makeOpImplicitArgsWithOverload(o: Rep[DSLOp], asVals: Boolean = false) = makeImplicitArgs(implicitArgsWithOverload(o), makeOpImplicitCtxBounds(o))
  
  // typed implicit args with context bounds (only needed for instance methods)
  // 'without' is used to subtract bounds that are already in scope
  def implicitCtxBoundsWithType(tpePars: List[Rep[TypePar]], without: List[Rep[TypePar]] = Nil) = {
    val withoutBounds = without.flatMap(a => a.ctxBounds)
    tpePars.flatMap(a => a.ctxBounds.diff(withoutBounds).map(b => tpe(b.name+"["+quote(a)+"]"))).distinct // a little hacky to use tpe like this
  }  
  def makeImplicitArgsWithCtxBoundsWithType(implicitArgs: List[Rep[DSLType]], tpePars: List[Rep[TypePar]], without: List[Rep[TypePar]] = Nil, asVals: Boolean = false) = {
    makeImplicitArgsWithType(implicitCtxBoundsWithType(tpePars, without) ++ implicitArgs, asVals)    
  }
  
  // typed implicit args without context bounds
  def makeImplicitArgsWithType(implicitArgs: List[Rep[DSLType]], asVals: Boolean = false) = {
    val prefix = if (asVals == true) "val " else ""
    if (implicitArgs.length > 0) "(implicit " + implicitArgs.zipWithIndex.map(t => prefix + implicitOpArgPrefix + t._2 + ": " + t._1.name).mkString(",") + ")"
    else ""    
  } 
  def makeOpImplicitArgsWithType(o: Rep[DSLOp], asVals: Boolean = false) = makeImplicitArgsWithType(o.implicitArgs, asVals)
  def makeOpImplicitArgsWithOverloadWithType(o: Rep[DSLOp], asVals: Boolean = false) = makeImplicitArgsWithType(implicitArgsWithOverload(o), asVals)
  
  // overload name clash resolution using implicit hack
  def nameClashes(o: Rep[DSLOp]) = OpsGrp.values.toList.flatMap(g => g.ops.filter(_.name == o.name))
  def nameClashId(o: Rep[DSLOp]) = {
    val clashes = nameClashes(o)
    if (clashes.length > 1) (clashes.indexOf(o)+1).toString else ""    
  }
  def implicitArgsWithOverload(o: Rep[DSLOp]) = {    
    val i = nameClashId(o)
    if (i != "") {
      o.implicitArgs :+ tpe("Overloaded" + i)
    }
    else {
      o.implicitArgs
    }
  }      
  def needDisambiguate(o: Rep[DSLOp]) = {
    // TODO: when do we need to disambiguate implicit args? (explicitly pass them). the fundamental reason is if there are
    // multiple possible implicits in scope.. how do we know? should we just always pass them explicitly? (this could improve staging performance, too)
     
    // the issue with nameClashes is that if the receiver method (e.g. string_+) also requires overload implicits, the ones from OverloadHack are also in scope
    // we should be able to solve this by de-prioritizing the OverloadHack vals somehow
    (nameClashes(o).length > 1 && unique(OpsGrp(o.grp).ops).contains(o)) // inefficient!! -> should refactor things so that we can store this with the op when it is created.
  }
  
  // method names
  def makeFullArgs(o: Rep[DSLOp], makeArgs: Rep[DSLOp] => String) = makeTpeArgs(o.tpePars) + makeArgs(o) + (if (needDisambiguate(o)) makeOpImplicitArgsWithOverload(o) else "")  
  def makeOpMethodName(o: Rep[DSLOp]) = {
    // adding the nameClashId is another way to avoid chaining the Overload implicit, but the weird method names that result are confusing
    val i = "" //nameClashId(o)    
    o.style match {
      case `static` => o.grp.name.toLowerCase + i + "_object_" + o.name.toLowerCase
      case _ => o.grp.name.toLowerCase + i + "_" + o.name.toLowerCase
    }
  }
  def makeOpMethodNameWithArgs(o: Rep[DSLOp]) = makeOpMethodName(o) + makeFullArgs(o, makeOpArgs)
  def makeOpMethodNameWithFutureArgs(o: Rep[DSLOp]) = makeOpMethodName(o) + makeFullArgs(o, makeOpFutureArgs)
    
  def makeSimpleMethod(o: Rep[DSLOp]) = {
    "def " + o.name + makeTpeArgsWithBounds(o.tpePars) + makeOpArgsWithNowType(o) + makeOpImplicitArgsWithOverloadWithType(o) + " = " + makeOpMethodNameWithFutureArgs(o)
  }
  
  
  /**
   * Delite op sanity checking
   */
  def check(d: DeliteOpType, o: Rep[DSLOp]) { 
    d match {
      case zip:Zip =>
       if (zip.tpePars.productIterator.exists(a => a.isInstanceOf[TypePar] && !o.tpePars.contains(a))) err("zipWith op with undefined type arg: " + o.name)
       if (zip.argIndices.productIterator.asInstanceOf[Iterator[Int]].exists(a => a < 0 || a > o.args.length)) err("zipWith op with illegal arg parameter: " + o.name)
    }
  }
  
  /**
   * Front-end codegen
   */
  def emitOpSyntax(ops: DSLOps, stream: PrintWriter) {
    emitBlockComment("Operations", stream)
    stream.println()
    stream.println("trait " + ops.grp.name + "Ops extends " + baseOpsCls(ops.grp) + " {")
    stream.println("  this: " + dsl + " => ")
    stream.println()
        
    // static ops
    val staticOps = ops.ops.filter(e=>e.style==static)
    if (staticOps.length > 0) {
      stream.println("  object " + ops.grp.name + " {")
      for (o <- staticOps) {        
        stream.println("    " + makeSimpleMethod(o))
      }
      stream.println("  }")
    }
    stream.println()
    
    // direct ops
    val directOps = ops.ops.filter(e=>e.style==direct)
    for (o <- directOps) {
      stream.println("  " + makeSimpleMethod(o))
    }
    
    // infix ops
    val infixOps = ops.ops.filter(e=>e.style==infix)
    
    // certain ops (e.g. "apply" cannot be expressed with infix notation right now), so we use implicits as a workaround
    val needPimpClass = (noInfixList intersect infixOps.map(_.name)).nonEmpty
    val pimpStream = new StringBuilder()
    def infix_appendLine(x: StringBuilder, y: String) = x.append(y + System.getProperty("line.separator"))
    if (needPimpClass) {
      // set up a pimp-my-library style promotion
      // can only do this on DSL types
      val tpe = grpAsTpe(ops.grp)                
      pimpStream.appendLine("  implicit def repTo" + tpe.name + "Ops" + makeTpeArgsWithBounds(tpe.tpePars) + "(x: " + repify(tpe) + ") = new " + tpe.name + "OpsCls(x)")
      if (OpsGrp.exists(g => g._2.ops.exists(o => o.name == "__newVar"))) {
        pimpStream.appendLine("  implicit def varTo" + tpe.name + "Ops" + makeTpeArgsWithBounds(tpe.tpePars) + "(x: " + varify(tpe) + ") = new " + tpe.name + "OpsCls(readVar(x))")
      }
      pimpStream.appendLine("")
      pimpStream.appendLine("  class " + tpe.name + "OpsCls" + makeTpeArgsWithBounds(tpe.tpePars) + "(val " + opArgPrefix + "0: " + repify(tpe) + ") {")
    }
    
    for (o <- infixOps) {
      if (noInfixList.contains(o.name)) {
        val tpe = grpAsTpe(ops.grp)
        val otherArgs = "(" + o.args.drop(1).zipWithIndex.map(t => opArgPrefix + (t._2+1) + ": " + repifySome(t._1)).mkString(",") + ")"
        pimpStream.appendLine("    def " + o.name + makeTpeArgsWithBounds(o.tpePars.diff(tpe.tpePars)) + otherArgs
          + (makeImplicitArgsWithCtxBoundsWithType(implicitArgsWithOverload(o), o.tpePars, without = tpe.tpePars)) + " = " + makeOpMethodNameWithFutureArgs(o))
      }
      else {
        stream.print("  def infix_" + o.name + makeTpeArgsWithBounds(o.tpePars))
        stream.print(makeOpArgsWithNowType(o))
        stream.print(makeOpImplicitArgsWithOverloadWithType(o))
        stream.println(" = " + makeOpMethodNameWithFutureArgs(o))        
      }
    }
    stream.println()      
    
    if (needPimpClass) {
      pimpStream.appendLine("  }")
      stream.println(pimpStream)
    }
    
    // abstract methods
    for (o <- unique(ops.ops)) {
      stream.print("  def " + makeOpMethodName(o) + makeTpeArgsWithBounds(o.tpePars))
      stream.print(makeOpArgsWithType(o))
      stream.print(makeOpImplicitArgsWithOverloadWithType(o) + ": " + repify(o.retTpe))
      stream.println()
    }
    
    stream.println("}")
  }
}